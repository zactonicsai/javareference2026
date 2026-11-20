# ---------------------------------------------------------------------------------------------------------------
# Shared locals
# ---------------------------------------------------------------------------------------------------------------
locals {
  tags = merge({
    project     = "aks-sso-platform"
    environment = var.environment
    managed-by  = "terraform"
    directory   = "01-infra"
  }, var.tags)

  zones      = length(var.availability_zones) > 0 ? var.availability_zones : null
  dns_label  = var.ingress_dns_label != "" ? var.ingress_dns_label : "${var.cluster_name}-${random_string.suffix.result}"
  acr_name   = var.acr_name != "" ? var.acr_name : lower(replace("acr${var.cluster_name}${random_string.suffix.result}", "/[^a-zA-Z0-9]/", ""))
  node_rg    = "${var.resource_group_name}-nodes"
  short_loc  = replace(var.location, " ", "")
}

resource "random_string" "suffix" {
  length  = 5
  lower   = true
  upper   = false
  numeric = true
  special = false
}

# ---------------------------------------------------------------------------------------------------------------
# Resource group
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_resource_group" "rg" {
  name     = var.resource_group_name
  location = var.location
  tags     = local.tags
}

# ---------------------------------------------------------------------------------------------------------------
# Network: VNet + node subnet + NSG
#
# Two NSGs end up in the path of inbound traffic:
#   * this one (on the subnet)  - the platform's policy: what the Internet may reach (80/443 on the gateway IP)
#   * the AKS-managed NIC NSG   - opened automatically by the cloud provider for every LoadBalancer Service
# Both must allow a packet, so this subnet NSG is the single place to tighten access.
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_virtual_network" "vnet" {
  name                = "${var.cluster_name}-vnet"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  address_space       = [var.vnet_cidr]
  tags                = local.tags
}

resource "azurerm_subnet" "nodes" {
  name                 = "snet-aks-nodes"
  resource_group_name  = azurerm_resource_group.rg.name
  virtual_network_name = azurerm_virtual_network.vnet.name
  address_prefixes     = [var.nodes_subnet_cidr]

  # Keep secret-store / registry traffic on the Microsoft backbone.
  service_endpoints = ["Microsoft.KeyVault", "Microsoft.ContainerRegistry", "Microsoft.Storage"]
}

resource "azurerm_network_security_group" "nodes" {
  name                = "${var.cluster_name}-nodes-nsg"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

# 80/443 to the gateway's public IP only, from the allowed CIDRs. Azure LB rules use floating IP, so the
# destination the NSG sees *is* the public IP (not the node IP).
resource "azurerm_network_security_rule" "allow_http" {
  name                        = "AllowGatewayHTTP"
  priority                    = 100
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "Tcp"
  source_port_range           = "*"
  destination_port_range      = "80"
  source_address_prefixes     = var.allowed_source_cidrs
  destination_address_prefix  = azurerm_public_ip.ingress.ip_address
  description                 = "Istio ingress gateway (HTTP -> redirected to HTTPS by the gateway)"
  resource_group_name         = azurerm_resource_group.rg.name
  network_security_group_name = azurerm_network_security_group.nodes.name
}

resource "azurerm_network_security_rule" "allow_https" {
  name                        = "AllowGatewayHTTPS"
  priority                    = 110
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "Tcp"
  source_port_range           = "*"
  destination_port_range      = "443"
  source_address_prefixes     = var.allowed_source_cidrs
  destination_address_prefix  = azurerm_public_ip.ingress.ip_address
  description                 = "Istio ingress gateway (HTTPS, self-signed cert from 02-istio-gateway)"
  resource_group_name         = azurerm_resource_group.rg.name
  network_security_group_name = azurerm_network_security_group.nodes.name
}

# Azure's default rules already allow AzureLoadBalancer (health probes on 15021) and VirtualNetwork traffic,
# and deny everything else at 65500. This explicit deny documents the intent and wins over any broad allow
# somebody adds later with a priority > 4000.
resource "azurerm_network_security_rule" "deny_internet" {
  name                        = "DenyInternetInbound"
  priority                    = 4000
  direction                   = "Inbound"
  access                      = "Deny"
  protocol                    = "*"
  source_port_range           = "*"
  destination_port_range      = "*"
  source_address_prefix       = "Internet"
  destination_address_prefix  = "*"
  description                 = "Everything from the Internet that is not explicitly allowed above"
  resource_group_name         = azurerm_resource_group.rg.name
  network_security_group_name = azurerm_network_security_group.nodes.name
}

resource "azurerm_subnet_network_security_group_association" "nodes" {
  subnet_id                 = azurerm_subnet.nodes.id
  network_security_group_id = azurerm_network_security_group.nodes.id
}

# ---------------------------------------------------------------------------------------------------------------
# The one public entry point. Lives in *this* resource group (not the AKS node RG) so it survives cluster
# re-creation; the gateway Service in 02-istio-gateway binds to it by name.
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_public_ip" "ingress" {
  name                = "${var.cluster_name}-ingress-pip"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  allocation_method   = "Static"
  sku                 = "Standard"
  sku_tier            = "Regional"
  zones               = local.zones
  domain_name_label   = local.dns_label
  tags                = local.tags
}

# ---------------------------------------------------------------------------------------------------------------
# Cluster identity. A user-assigned identity lets us grant the permissions *before* the cluster exists
# (BYO VNet + public IP in another RG both need Network Contributor).
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_user_assigned_identity" "aks" {
  name                = "${var.cluster_name}-identity"
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  tags                = local.tags
}

resource "azurerm_role_assignment" "aks_network_contributor" {
  scope                = azurerm_resource_group.rg.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.aks.principal_id
  principal_type       = "ServicePrincipal"
}

# Role assignments take a while to replicate; creating the cluster too early fails with a subnet-permission error.
resource "time_sleep" "rbac_propagation" {
  depends_on      = [azurerm_role_assignment.aks_network_contributor]
  create_duration = "90s"
}

# ---------------------------------------------------------------------------------------------------------------
# Container registry (optional) - the Spring Boot image in 03-apps-keycloak is built here with `az acr build`.
# ---------------------------------------------------------------------------------------------------------------
resource "azurerm_container_registry" "acr" {
  count               = var.create_acr ? 1 : 0
  name                = local.acr_name
  location            = azurerm_resource_group.rg.location
  resource_group_name = azurerm_resource_group.rg.name
  sku                 = "Basic"
  admin_enabled       = false
  tags                = local.tags
}

resource "azurerm_role_assignment" "acr_pull" {
  count                            = var.create_acr ? 1 : 0
  scope                            = azurerm_container_registry.acr[0].id
  role_definition_name             = "AcrPull"
  principal_id                     = azurerm_kubernetes_cluster.aks.kubelet_identity[0].object_id
  principal_type                   = "ServicePrincipal"
  skip_service_principal_aad_check = true
}
