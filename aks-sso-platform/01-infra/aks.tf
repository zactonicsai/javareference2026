# ---------------------------------------------------------------------------------------------------------------
# AKS cluster (Base SKU, Azure CNI overlay + Cilium, system + user pools, monitoring add-ons)
#
# Deliberately NOT enabled here: the Istio add-on. 02-istio-gateway installs upstream Istio with Helm so the full
# Istio API (Gateway / VirtualService / AuthorizationPolicy) is available and the version is yours to choose.
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_kubernetes_cluster" "aks" {
  name                = var.cluster_name
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  dns_prefix          = var.cluster_name
  kubernetes_version  = var.kubernetes_version
  sku_tier            = var.sku_tier
  node_resource_group = local.node_rg
  tags                = local.tags

  # --- upgrades & hygiene -------------------------------------------------------------------------------------
  automatic_upgrade_channel    = "patch"     # stay on the latest patch of the current minor
  node_os_upgrade_channel      = "NodeImage" # weekly node images with OS security fixes
  image_cleaner_enabled        = true
  image_cleaner_interval_hours = 48
  cost_analysis_enabled        = var.sku_tier != "Free" # Standard/Premium only

  maintenance_window_auto_upgrade {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Sunday"
    start_time  = "02:00"
    utc_offset  = "+00:00"
  }

  maintenance_window_node_os {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Saturday"
    start_time  = "02:00"
    utc_offset  = "+00:00"
  }

  # --- identity & security ------------------------------------------------------------------------------------
  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks.id]
  }

  oidc_issuer_enabled       = true # workload identity federation for pods
  workload_identity_enabled = true
  azure_policy_enabled      = true # Azure Policy add-on (deployment safeguards, baseline policies)
  local_account_disabled    = var.disable_local_accounts

  dynamic "azure_active_directory_role_based_access_control" {
    for_each = length(var.admin_group_object_ids) > 0 ? [1] : []
    content {
      tenant_id              = data.azurerm_client_config.current.tenant_id
      azure_rbac_enabled     = true
      admin_group_object_ids = var.admin_group_object_ids
    }
  }

  dynamic "api_server_access_profile" {
    for_each = length(var.api_server_authorized_ip_ranges) > 0 ? [1] : []
    content {
      authorized_ip_ranges = var.api_server_authorized_ip_ranges
    }
  }

  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }

  dynamic "microsoft_defender" {
    for_each = var.enable_defender ? [1] : []
    content {
      log_analytics_workspace_id = azurerm_log_analytics_workspace.law.id
    }
  }

  # --- networking ---------------------------------------------------------------------------------------------
  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay" # pods get IPs from pod_cidr, not from the subnet
    network_policy      = "cilium"  # NetworkPolicy enforcement
    network_data_plane  = "cilium"  # eBPF data plane
    load_balancer_sku   = "standard"
    outbound_type       = var.outbound_type
    pod_cidr            = var.pod_cidr
    service_cidr        = var.service_cidr
    dns_service_ip      = var.dns_service_ip

    dynamic "load_balancer_profile" {
      for_each = var.outbound_type == "loadBalancer" ? [1] : []
      content {
        managed_outbound_ip_count = 1
      }
    }

    dynamic "nat_gateway_profile" {
      for_each = var.outbound_type == "managedNATGateway" ? [1] : []
      content {
        managed_outbound_ip_count = 1
        idle_timeout_in_minutes   = 4
      }
    }
  }

  # --- monitoring ---------------------------------------------------------------------------------------------
  oms_agent {
    log_analytics_workspace_id      = azurerm_log_analytics_workspace.law.id
    msi_auth_for_monitoring_enabled = true # Container Insights (logs, live data, node/pod health)
  }

  monitor_metrics {} # Managed Prometheus agent; the data collection rule in monitoring.tf wires it up

  # --- node pools ---------------------------------------------------------------------------------------------
  default_node_pool {
    name                         = "system"
    vm_size                      = var.system_vm_size
    os_sku                       = var.os_sku
    os_disk_type                 = var.os_disk_type
    os_disk_size_gb              = var.os_disk_size_gb
    auto_scaling_enabled         = true
    min_count                    = var.system_min_count
    max_count                    = var.system_max_count
    vnet_subnet_id               = azurerm_subnet.nodes.id
    zones                        = local.zones
    only_critical_addons_enabled = true # taint CriticalAddonsOnly: user workloads go to the user pool
    temporary_name_for_rotation  = "systemtmp"
    max_pods                     = 110

    upgrade_settings {
      max_surge                     = "33%"
      drain_timeout_in_minutes      = 30
      node_soak_duration_in_minutes = 0
    }

    node_labels = {
      "workload" = "system"
    }
    tags = local.tags
  }

  auto_scaler_profile {
    balance_similar_node_groups   = true
    expander                      = "least-waste"
    scale_down_delay_after_add    = "5m"
    scale_down_unneeded           = "5m"
    skip_nodes_with_local_storage = false
    skip_nodes_with_system_pods   = true
  }

  lifecycle {
    ignore_changes = [
      default_node_pool[0].node_count, # the autoscaler owns this
    ]
  }

  depends_on = [
    time_sleep.rbac_propagation,
    azurerm_subnet_network_security_group_association.nodes,
  ]
}

resource "azurerm_kubernetes_cluster_node_pool" "user" {
  name                  = "user"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.aks.id
  mode                  = "User"
  vm_size               = var.user_vm_size
  os_sku                = var.os_sku
  os_disk_type          = var.os_disk_type
  os_disk_size_gb       = var.os_disk_size_gb
  auto_scaling_enabled  = true
  min_count             = var.user_min_count
  max_count             = var.user_max_count
  vnet_subnet_id        = azurerm_subnet.nodes.id
  zones                 = local.zones
  max_pods              = 110
  tags                  = local.tags

  upgrade_settings {
    max_surge                     = "33%"
    drain_timeout_in_minutes      = 30
    node_soak_duration_in_minutes = 0
  }

  node_labels = {
    "workload" = "apps"
  }

  lifecycle {
    ignore_changes = [node_count]
  }
}
