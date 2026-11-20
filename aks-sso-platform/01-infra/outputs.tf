output "resource_group_name" {
  value = azurerm_resource_group.rg.name
}

output "node_resource_group" {
  value = azurerm_kubernetes_cluster.aks.node_resource_group
}

output "location" {
  value = azurerm_resource_group.rg.location
}

output "cluster_name" {
  value = azurerm_kubernetes_cluster.aks.name
}

output "cluster_id" {
  value = azurerm_kubernetes_cluster.aks.id
}

output "kubernetes_version" {
  value = azurerm_kubernetes_cluster.aks.kubernetes_version
}

output "kube_config_command" {
  description = "Run this to point kubectl at the cluster."
  value       = "az aks get-credentials --resource-group ${azurerm_resource_group.rg.name} --name ${azurerm_kubernetes_cluster.aks.name} --overwrite-existing"
}

output "oidc_issuer_url" {
  description = "Use with federated credentials for workload identity."
  value       = azurerm_kubernetes_cluster.aks.oidc_issuer_url
}

# ---- hand-off to 02-istio-gateway ------------------------------------------------------------------------------
output "ingress_public_ip" {
  value = azurerm_public_ip.ingress.ip_address
}

output "ingress_public_ip_name" {
  value = azurerm_public_ip.ingress.name
}

output "ingress_public_ip_resource_group" {
  value = azurerm_resource_group.rg.name
}

output "ingress_fqdn" {
  description = "DNS name of the public IP; the self-signed gateway certificate is issued for it."
  value       = azurerm_public_ip.ingress.fqdn
}

# ---- network -----------------------------------------------------------------------------------------------------
output "vnet_id" {
  value = azurerm_virtual_network.vnet.id
}

output "nodes_subnet_id" {
  value = azurerm_subnet.nodes.id
}

output "nodes_nsg_name" {
  value = azurerm_network_security_group.nodes.name
}

output "allowed_source_cidrs" {
  value = var.allowed_source_cidrs
}

# ---- monitoring --------------------------------------------------------------------------------------------------
output "log_analytics_workspace_id" {
  value = azurerm_log_analytics_workspace.law.id
}

output "monitor_workspace_id" {
  value = azurerm_monitor_workspace.prometheus.id
}

output "prometheus_query_endpoint" {
  value = azurerm_monitor_workspace.prometheus.query_endpoint
}

output "grafana_endpoint" {
  value = var.enable_grafana ? azurerm_dashboard_grafana.grafana[0].endpoint : ""
}

# ---- registry ----------------------------------------------------------------------------------------------------
output "acr_name" {
  value = var.create_acr ? azurerm_container_registry.acr[0].name : ""
}

output "acr_login_server" {
  value = var.create_acr ? azurerm_container_registry.acr[0].login_server : ""
}

output "next_steps" {
  value = <<-EOT

    1. kubectl:   az aks get-credentials --resource-group ${azurerm_resource_group.rg.name} --name ${azurerm_kubernetes_cluster.aks.name} --overwrite-existing
    2. check:     scripts/test.sh   and   scripts/cluster-info.sh
    3. gateway:   cd ../02-istio-gateway && scripts/apply.sh
                  (public IP ${azurerm_public_ip.ingress.ip_address} / ${azurerm_public_ip.ingress.fqdn})
  EOT
}
