provider "azurerm" {
  # azurerm 4.x needs the subscription explicitly: either this variable or ARM_SUBSCRIPTION_ID in the
  # environment (scripts/apply.sh exports it from `az account show`).
  subscription_id = var.subscription_id

  # Resource-provider registration is left to the CLI user (fast `terraform plan`, no extra permissions needed).
  # Register once per subscription:  az provider register -n Microsoft.ContainerService  (and Monitor, Insights,
  # OperationalInsights, Dashboard, ContainerRegistry, Network).  Set to "core" if you prefer Terraform to do it.
  resource_provider_registrations = "none"

  features {
    resource_group {
      # Protects against `terraform destroy` wiping a resource group that still has unmanaged things in it.
      prevent_deletion_if_contains_resources = false
    }
    log_analytics_workspace {
      # Hard-delete on destroy so a re-create with the same name does not collide with a soft-deleted workspace.
      permanently_delete_on_destroy = true
    }
  }
}

data "azurerm_client_config" "current" {}
