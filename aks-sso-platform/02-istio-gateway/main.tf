# ---------------------------------------------------------------------------------------------------------------
# Public IP: reuse the one 01-infra made (by name), or create one in the node resource group
# ---------------------------------------------------------------------------------------------------------------
locals {
  use_existing_pip = var.public_ip_name != ""
  existing_pip_rg  = var.public_ip_resource_group != "" ? var.public_ip_resource_group : var.resource_group_name
  node_rg          = data.azurerm_kubernetes_cluster.aks.node_resource_group
  tags = merge({
    project    = "aks-sso-platform"
    managed-by = "terraform"
    directory  = "02-istio-gateway"
  }, var.tags)
}

data "azurerm_public_ip" "existing" {
  count               = local.use_existing_pip ? 1 : 0
  name                = var.public_ip_name
  resource_group_name = local.existing_pip_rg
}

resource "random_string" "suffix" {
  count   = local.use_existing_pip ? 0 : 1
  length  = 5
  lower   = true
  upper   = false
  numeric = true
  special = false
}

resource "azurerm_public_ip" "created" {
  count               = local.use_existing_pip ? 0 : 1
  name                = "${var.cluster_name}-ingress-pip"
  location            = data.azurerm_kubernetes_cluster.aks.location
  resource_group_name = local.node_rg # the cluster identity already has rights here
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = length(var.public_ip_zones) > 0 ? var.public_ip_zones : null
  domain_name_label   = "${var.cluster_name}-${random_string.suffix[0].result}"
  tags                = local.tags
}

locals {
  pip_name = local.use_existing_pip ? data.azurerm_public_ip.existing[0].name : azurerm_public_ip.created[0].name
  pip_rg   = local.use_existing_pip ? local.existing_pip_rg : local.node_rg
  pip_ip   = local.use_existing_pip ? data.azurerm_public_ip.existing[0].ip_address : azurerm_public_ip.created[0].ip_address
  pip_fqdn = local.use_existing_pip ? try(data.azurerm_public_ip.existing[0].fqdn, "") : azurerm_public_ip.created[0].fqdn

  # What browsers type. DNS name when the IP has one, else the bare IP.
  public_host = local.pip_fqdn != null && local.pip_fqdn != "" ? local.pip_fqdn : local.pip_ip
  public_url  = "https://${local.public_host}"
  dns_names   = concat(local.pip_fqdn != null && local.pip_fqdn != "" ? [local.pip_fqdn, "*.${local.pip_fqdn}"] : [], var.extra_dns_names)
}

# ---------------------------------------------------------------------------------------------------------------
# Self-signed certificate -> kubernetes.io/tls Secret the Gateway references (credentialName: gateway-tls)
# ---------------------------------------------------------------------------------------------------------------
resource "tls_private_key" "gateway" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_self_signed_cert" "gateway" {
  private_key_pem       = tls_private_key.gateway.private_key_pem
  validity_period_hours = var.cert_validity_hours
  early_renewal_hours   = 720 # re-issue on `apply` within 30 days of expiry
  is_ca_certificate     = false
  dns_names             = local.dns_names
  ip_addresses          = [local.pip_ip]

  subject {
    common_name  = local.public_host
    organization = var.cert_organization
  }

  allowed_uses = ["key_encipherment", "digital_signature", "server_auth"]
}

resource "kubernetes_namespace_v1" "ingress" {
  metadata {
    name = var.ingress_namespace
    labels = {
      "istio-injection"              = "enabled"
      "app.kubernetes.io/part-of"    = "aks-sso-platform"
      "app.kubernetes.io/managed-by" = "terraform"
    }
  }
  depends_on = [helm_release.istiod]
}

resource "kubernetes_secret_v1" "gateway_tls" {
  metadata {
    name      = "gateway-tls"
    namespace = kubernetes_namespace_v1.ingress.metadata[0].name
    labels = {
      "app.kubernetes.io/part-of" = "aks-sso-platform"
    }
  }
  type = "kubernetes.io/tls"
  data = {
    "tls.crt" = tls_self_signed_cert.gateway.cert_pem
    "tls.key" = tls_private_key.gateway.private_key_pem
  }
}

resource "local_file" "cert" {
  count           = var.write_cert_file ? 1 : 0
  filename        = "${path.module}/gateway.crt"
  content         = tls_self_signed_cert.gateway.cert_pem
  file_permission = "0644"
}
