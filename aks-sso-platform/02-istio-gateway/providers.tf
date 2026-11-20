provider "azurerm" {
  subscription_id                 = var.subscription_id
  resource_provider_registrations = "none"
  features {}
}

# The cluster is looked up, never created here: this directory works with any AKS cluster (01-infra or your own).
data "azurerm_kubernetes_cluster" "aks" {
  name                = var.cluster_name
  resource_group_name = var.resource_group_name
}

locals {
  # With Entra ID + local accounts enabled, kube_admin_config holds the certificate credentials; otherwise kube_config.
  kc = length(data.azurerm_kubernetes_cluster.aks.kube_admin_config) > 0 ? data.azurerm_kubernetes_cluster.aks.kube_admin_config[0] : data.azurerm_kubernetes_cluster.aks.kube_config[0]
}

provider "kubernetes" {
  host                   = local.kc.host
  client_certificate     = base64decode(local.kc.client_certificate)
  client_key             = base64decode(local.kc.client_key)
  cluster_ca_certificate = base64decode(local.kc.cluster_ca_certificate)
}

provider "helm" {
  kubernetes = {
    host                   = local.kc.host
    client_certificate     = base64decode(local.kc.client_certificate)
    client_key             = base64decode(local.kc.client_key)
    cluster_ca_certificate = base64decode(local.kc.cluster_ca_certificate)
  }
}
