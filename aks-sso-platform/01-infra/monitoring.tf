# ---------------------------------------------------------------------------------------------------------------
# Logs: Log Analytics (Container Insights + control-plane diagnostics + NSG events)
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_log_analytics_workspace" "law" {
  name                = "${var.cluster_name}-law"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  sku                 = "PerGB2018"
  retention_in_days   = var.log_retention_days
  daily_quota_gb      = 5 # safety cap for a demo; raise for production
  tags                = local.tags
}

resource "azurerm_monitor_diagnostic_setting" "aks" {
  name                       = "control-plane-logs"
  target_resource_id         = azurerm_kubernetes_cluster.aks.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.law.id

  # kube-audit is very chatty (and costly); kube-audit-admin keeps only the mutating calls.
  dynamic "enabled_log" {
    for_each = toset(["kube-apiserver", "kube-audit-admin", "kube-controller-manager", "kube-scheduler", "cluster-autoscaler", "guard"])
    content {
      category = enabled_log.value
    }
  }
}

resource "azurerm_monitor_diagnostic_setting" "nsg" {
  name                       = "nsg-events"
  target_resource_id         = azurerm_network_security_group.nodes.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.law.id

  enabled_log {
    category = "NetworkSecurityGroupEvent"
  }
  enabled_log {
    category = "NetworkSecurityGroupRuleCounter"
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Metrics: Managed Prometheus (Azure Monitor workspace + data collection rule bound to the cluster)
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_monitor_workspace" "prometheus" {
  name                = "${var.cluster_name}-amw"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_monitor_data_collection_endpoint" "prometheus" {
  name                = "MSProm-${local.short_loc}-${var.cluster_name}"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  kind                = "Linux"
  tags                = local.tags
}

resource "azurerm_monitor_data_collection_rule" "prometheus" {
  name                        = "MSProm-${local.short_loc}-${var.cluster_name}"
  location                    = azurerm_resource_group.rg.location
  resource_group_name         = azurerm_resource_group.rg.name
  data_collection_endpoint_id = azurerm_monitor_data_collection_endpoint.prometheus.id
  kind                        = "Linux"
  description                 = "Managed Prometheus metrics from ${var.cluster_name}"
  tags                        = local.tags

  destinations {
    monitor_account {
      monitor_account_id = azurerm_monitor_workspace.prometheus.id
      name               = "MonitoringAccount1"
    }
  }

  data_flow {
    streams      = ["Microsoft-PrometheusMetrics"]
    destinations = ["MonitoringAccount1"]
  }

  data_sources {
    prometheus_forwarder {
      streams = ["Microsoft-PrometheusMetrics"]
      name    = "PrometheusDataSource"
    }
  }
}

resource "azurerm_monitor_data_collection_rule_association" "prometheus" {
  name                    = "MSProm-${local.short_loc}-${var.cluster_name}"
  target_resource_id      = azurerm_kubernetes_cluster.aks.id
  data_collection_rule_id = azurerm_monitor_data_collection_rule.prometheus.id
  description             = "Deleting this association stops Prometheus collection for the cluster."
}

# ---------------------------------------------------------------------------------------------------------------
# Alerts: an action group (e-mail) + the handful of platform-level metric alerts every cluster should have
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_monitor_action_group" "platform" {
  name                = "${var.cluster_name}-alerts"
  resource_group_name = azurerm_resource_group.rg.name
  short_name          = "aksalerts"
  tags                = local.tags

  dynamic "email_receiver" {
    for_each = var.alert_email != "" ? [var.alert_email] : []
    content {
      name                    = "platform-team"
      email_address           = email_receiver.value
      use_common_alert_schema = true
    }
  }
}

locals {
  metric_alerts = {
    node-cpu-high = {
      description = "Average node CPU above 80% for 15 minutes"
      metric      = "node_cpu_usage_percentage"
      aggregation = "Average"
      operator    = "GreaterThan"
      threshold   = 80
      severity    = 2
      dimension   = null
    }
    node-memory-high = {
      description = "Average node working-set memory above 80% for 15 minutes"
      metric      = "node_memory_working_set_percentage"
      aggregation = "Average"
      operator    = "GreaterThan"
      threshold   = 80
      severity    = 2
      dimension   = null
    }
    pods-failed = {
      description = "One or more pods in Failed phase"
      metric      = "kube_pod_status_phase"
      aggregation = "Average"
      operator    = "GreaterThan"
      threshold   = 0
      severity    = 3
      dimension   = { name = "phase", values = ["Failed"] }
    }
    pods-unschedulable = {
      description = "Cluster autoscaler reports unschedulable pods (out of capacity or quota)"
      metric      = "cluster_autoscaler_unschedulable_pods_count"
      aggregation = "Average"
      operator    = "GreaterThan"
      threshold   = 0
      severity    = 2
      dimension   = null
    }
  }
}

resource "azurerm_monitor_metric_alert" "platform" {
  for_each            = local.metric_alerts
  name                = "${var.cluster_name}-${each.key}"
  resource_group_name = azurerm_resource_group.rg.name
  scopes              = [azurerm_kubernetes_cluster.aks.id]
  description         = each.value.description
  severity            = each.value.severity
  frequency           = "PT5M"
  window_size         = "PT15M"
  auto_mitigate       = true
  tags                = local.tags

  criteria {
    metric_namespace = "Microsoft.ContainerService/managedClusters"
    metric_name      = each.value.metric
    aggregation      = each.value.aggregation
    operator         = each.value.operator
    threshold        = each.value.threshold

    dynamic "dimension" {
      for_each = each.value.dimension == null ? [] : [each.value.dimension]
      content {
        name     = dimension.value.name
        operator = "Include"
        values   = dimension.value.values
      }
    }
  }

  action {
    action_group_id = azurerm_monitor_action_group.platform.id
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Dashboards (optional): Azure Managed Grafana reading the Prometheus workspace
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_dashboard_grafana" "grafana" {
  count                             = var.enable_grafana ? 1 : 0
  name                              = substr("${var.cluster_name}-grafana", 0, 23)
  location                          = azurerm_resource_group.rg.location
  resource_group_name               = azurerm_resource_group.rg.name
  grafana_major_version             = "11"
  sku                               = "Standard"
  api_key_enabled                   = false
  deterministic_outbound_ip_enabled = false
  public_network_access_enabled     = true
  tags                              = local.tags

  identity {
    type = "SystemAssigned"
  }

  azure_monitor_workspace_integrations {
    resource_id = azurerm_monitor_workspace.prometheus.id
  }
}

resource "azurerm_role_assignment" "grafana_reads_prometheus" {
  count                = var.enable_grafana ? 1 : 0
  scope                = azurerm_monitor_workspace.prometheus.id
  role_definition_name = "Monitoring Data Reader"
  principal_id         = azurerm_dashboard_grafana.grafana[0].identity[0].principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "grafana_admin_me" {
  count                = var.enable_grafana ? 1 : 0
  scope                = azurerm_dashboard_grafana.grafana[0].id
  role_definition_name = "Grafana Admin"
  principal_id         = data.azurerm_client_config.current.object_id
}
