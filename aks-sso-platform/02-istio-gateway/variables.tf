# ---------------------------------------------------------------------------------------------------------------
# Where (any AKS cluster; scripts/apply.sh fills these from ../.platform/infra.env when 01-infra was used)
# ---------------------------------------------------------------------------------------------------------------
variable "subscription_id" {
  description = "Azure subscription. null = ARM_SUBSCRIPTION_ID from the environment."
  type        = string
  default     = null
}

variable "resource_group_name" {
  description = "Resource group of the AKS cluster."
  type        = string
}

variable "cluster_name" {
  description = "AKS cluster name."
  type        = string
}

variable "public_ip_name" {
  description = "Existing Standard public IP to bind the gateway to (01-infra output ingress_public_ip_name). Empty = create one in the node resource group."
  type        = string
  default     = ""
}

variable "public_ip_resource_group" {
  description = "Resource group of public_ip_name. Empty = resource_group_name. The cluster identity needs Network Contributor there."
  type        = string
  default     = ""
}

variable "public_ip_zones" {
  description = "Zones for a public IP created here (only when public_ip_name is empty). [] = regional."
  type        = list(string)
  default     = []
}

variable "allowed_source_cidrs" {
  description = "Client CIDRs allowed through the load balancer (loadBalancerSourceRanges). Mirror of 01-infra's NSG list."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

# ---------------------------------------------------------------------------------------------------------------
# Istio
# ---------------------------------------------------------------------------------------------------------------
variable "istio_version" {
  description = "Istio chart/version (https://istio.io/latest/news/releases/)."
  type        = string
  default     = "1.31.4"
}

variable "istio_chart_repo" {
  description = "Helm repository for the Istio charts."
  type        = string
  default     = "https://blob.istio.io/istio-release/charts"
}

variable "istio_namespace" {
  type    = string
  default = "istio-system"
}

variable "ingress_namespace" {
  type    = string
  default = "istio-ingress"
}

variable "gateway_release_name" {
  description = "Helm release name of the ingress gateway; the pods get label istio=<name without istio- prefix>."
  type        = string
  default     = "istio-ingress"
}

variable "gateway_name" {
  description = "Name of the Istio Gateway resource that VirtualServices bind to (<ingress_namespace>/<gateway_name>)."
  type        = string
  default     = "public-gateway"
}

variable "high_availability" {
  description = "true = 2+ replicas of istiod and the gateway with PodDisruptionBudgets; false = 1 replica, no PDB (lets single-node pools drain)."
  type        = bool
  default     = false
}

variable "gateway_max_replicas" {
  type    = number
  default = 5
}

variable "https_redirect" {
  description = "Answer plain HTTP with a 301 to HTTPS."
  type        = bool
  default     = true
}

variable "mtls_mode" {
  description = "Mesh-wide PeerAuthentication: PERMISSIVE (default, lets non-mesh pods such as Helm hook Jobs talk to services) or STRICT."
  type        = string
  default     = "PERMISSIVE"
  validation {
    condition     = contains(["PERMISSIVE", "STRICT"], var.mtls_mode)
    error_message = "mtls_mode must be PERMISSIVE or STRICT."
  }
}

variable "access_logs" {
  description = "Envoy access logs (JSON) on stdout of every proxy — picked up by Container Insights."
  type        = bool
  default     = true
}

variable "native_sidecars" {
  description = "Run istio-proxy as a Kubernetes native sidecar (initContainer with restartPolicy Always) so Jobs complete cleanly. Needs Kubernetes >= 1.29."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------------------------------------------
# Certificate (self-signed; swap for cert-manager/Let's Encrypt when you own a domain)
# ---------------------------------------------------------------------------------------------------------------
variable "cert_validity_hours" {
  description = "Lifetime of the self-signed certificate (8760 = 1 year). Terraform renews it 30 days before expiry on the next apply."
  type        = number
  default     = 8760
}

variable "cert_organization" {
  type    = string
  default = "appsteam"
}

variable "extra_dns_names" {
  description = "Additional SANs, e.g. [\"apps.example.com\"] if you point your own DNS at the IP."
  type        = list(string)
  default     = []
}

variable "write_cert_file" {
  description = "Write the certificate to ./gateway.crt (git-ignored) for scripts/trust-cert.sh."
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
