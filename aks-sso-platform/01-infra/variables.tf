# ---------------------------------------------------------------------------------------------------------------
# Identity / placement
# ---------------------------------------------------------------------------------------------------------------
variable "subscription_id" {
  description = "Azure subscription to deploy into. Leave null to use ARM_SUBSCRIPTION_ID from the environment."
  type        = string
  default     = null
}

variable "location" {
  description = "Azure region (e.g. centralus, eastus2, westeurope)."
  type        = string
  default     = "centralus"
}

variable "resource_group_name" {
  description = "Resource group that holds the VNet, public IP, monitoring and the AKS resource itself."
  type        = string
  default     = "rg-appsteam-platform"
}

variable "cluster_name" {
  description = "AKS cluster name. Also used as a prefix for most other resource names."
  type        = string
  default     = "aks-appsteam"
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{1,40}[a-z0-9]$", var.cluster_name))
    error_message = "cluster_name must be 3-42 chars, lowercase letters, digits and dashes."
  }
}

variable "environment" {
  description = "Environment tag (dev / test / prod)."
  type        = string
  default     = "dev"
}

variable "tags" {
  description = "Extra tags applied to every resource."
  type        = map(string)
  default     = {}
}

# ---------------------------------------------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------------------------------------------
variable "vnet_cidr" {
  description = "Address space of the platform VNet."
  type        = string
  default     = "10.10.0.0/16"
}

variable "nodes_subnet_cidr" {
  description = "Subnet for AKS nodes (and their load balancer back-ends). Must sit inside vnet_cidr."
  type        = string
  default     = "10.10.0.0/22"
}

variable "pod_cidr" {
  description = "Overlay pod CIDR (Azure CNI overlay). Must NOT overlap the VNet or any peered network."
  type        = string
  default     = "10.244.0.0/16"
}

variable "service_cidr" {
  description = "Kubernetes Service CIDR. Must NOT overlap the VNet, pod CIDR or any peered network."
  type        = string
  default     = "10.100.0.0/16"
}

variable "dns_service_ip" {
  description = "ClusterIP of kube-dns; must be inside service_cidr (not the first address)."
  type        = string
  default     = "10.100.0.10"
}

variable "allowed_source_cidrs" {
  description = <<-EOT
    Who may reach the public gateway on 80/443. Default is the whole Internet (a public website).
    Lock it down for a private demo, e.g. ["203.0.113.7/32"] -- scripts/allow-ip.sh --me does that for you.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "outbound_type" {
  description = "Egress for the nodes: loadBalancer (cheapest) or managedNATGateway (best practice for busy clusters, ~USD 33/month extra)."
  type        = string
  default     = "loadBalancer"
  validation {
    condition     = contains(["loadBalancer", "managedNATGateway"], var.outbound_type)
    error_message = "outbound_type must be loadBalancer or managedNATGateway."
  }
}

variable "ingress_dns_label" {
  description = "DNS label for the public IP (<label>.<region>.cloudapp.azure.com). Empty = <cluster_name>-<random>."
  type        = string
  default     = ""
}

variable "api_server_authorized_ip_ranges" {
  description = "Restrict the Kubernetes API server to these CIDRs (empty = open to the Internet, protected by auth only)."
  type        = list(string)
  default     = []
}

# ---------------------------------------------------------------------------------------------------------------
# Cluster
# ---------------------------------------------------------------------------------------------------------------
variable "kubernetes_version" {
  description = "Kubernetes minor/patch version. null = the region's current default (auto-upgrade keeps patches current)."
  type        = string
  default     = null
}

variable "sku_tier" {
  description = "AKS control-plane tier: Free (dev/test, no SLA) or Standard (99.95% SLA, ~USD 73/month)."
  type        = string
  default     = "Free"
  validation {
    condition     = contains(["Free", "Standard"], var.sku_tier)
    error_message = "sku_tier must be Free or Standard."
  }
}

variable "availability_zones" {
  description = "Zones for node pools and the public IP. Use [] in regions without zones."
  type        = list(string)
  default     = ["1", "2", "3"]
}

variable "system_vm_size" {
  description = "VM size of the system node pool (runs CoreDNS, metrics, monitoring agents)."
  type        = string
  default     = "Standard_D4ds_v5"
}

variable "system_min_count" {
  type    = number
  default = 1
}

variable "system_max_count" {
  type    = number
  default = 3
}

variable "user_vm_size" {
  description = "VM size of the user node pool (runs Istio gateway, Keycloak, your apps)."
  type        = string
  default     = "Standard_D4ds_v5"
}

variable "user_min_count" {
  type    = number
  default = 1
}

variable "user_max_count" {
  type    = number
  default = 5
}

variable "os_disk_type" {
  description = "Ephemeral (faster, free, needs a VM size with a temp disk >= os_disk_size_gb) or Managed."
  type        = string
  default     = "Ephemeral"
  validation {
    condition     = contains(["Ephemeral", "Managed"], var.os_disk_type)
    error_message = "os_disk_type must be Ephemeral or Managed."
  }
}

variable "os_disk_size_gb" {
  type    = number
  default = 100
}

variable "admin_group_object_ids" {
  description = "Entra ID group object IDs that get cluster-admin via Azure RBAC. Empty = Kubernetes local accounts only."
  type        = list(string)
  default     = []
}

variable "disable_local_accounts" {
  description = "Disable the local admin kubeconfig. Only set true together with admin_group_object_ids (then 02-istio-gateway needs kubelogin)."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------------------------------------------
# Monitoring
# ---------------------------------------------------------------------------------------------------------------
variable "log_retention_days" {
  description = "Log Analytics retention (30 is free; more costs per GB)."
  type        = number
  default     = 30
}

variable "alert_email" {
  description = "E-mail address for alerts (node CPU/memory pressure, failed pods, unschedulable pods). Empty = alerts created without a receiver."
  type        = string
  default     = ""
}

variable "enable_grafana" {
  description = "Create an Azure Managed Grafana instance wired to the Prometheus workspace (~USD 9/active user/month)."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------------------------------------------
# Container registry (used by 03-apps-keycloak/scripts/build-image.sh for the Spring Boot image)
# ---------------------------------------------------------------------------------------------------------------
variable "create_acr" {
  description = "Create a Basic ACR and let the cluster pull from it."
  type        = bool
  default     = true
}

variable "acr_name" {
  description = "Globally-unique ACR name (5-50 alphanumerics). Empty = derived from cluster_name + random suffix."
  type        = string
  default     = ""
}

variable "os_sku" {
  description = "Node OS: AzureLinux (Microsoft's hardened distro, recommended) or Ubuntu."
  type        = string
  default     = "AzureLinux"
  validation {
    condition     = contains(["AzureLinux", "Ubuntu"], var.os_sku)
    error_message = "os_sku must be AzureLinux or Ubuntu."
  }
}

variable "enable_defender" {
  description = "Enable Microsoft Defender for Containers on the cluster (runtime threat detection; billed per vCore)."
  type        = bool
  default     = false
}
