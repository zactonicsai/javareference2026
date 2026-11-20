# ---------------------------------------------------------------------------------------------------------------
# Istio control plane (official Helm charts) + ingress gateway bound to the public IP
# ---------------------------------------------------------------------------------------------------------------
resource "helm_release" "istio_base" {
  name             = "istio-base"
  repository       = var.istio_chart_repo
  chart            = "base"
  version          = var.istio_version
  namespace        = var.istio_namespace
  create_namespace = true
  wait             = true
  timeout          = 300
  description      = "Istio CRDs and cluster roles"

  set = [
    { name = "defaultRevision", value = "default" },
  ]
}

resource "helm_release" "istiod" {
  name        = "istiod"
  repository  = var.istio_chart_repo
  chart       = "istiod"
  version     = var.istio_version
  namespace   = var.istio_namespace
  wait        = true
  timeout     = 600
  description = "Istio control plane"

  values = [yamlencode({
    pilot = {
      autoscaleEnabled = true
      autoscaleMin     = var.high_availability ? 2 : 1
      autoscaleMax     = 3
      env              = var.native_sidecars ? { ENABLE_NATIVE_SIDECARS = "true" } : {}
    }
    global = {
      # A PDB with minAvailable=1 on a single replica blocks node drains (and AKS upgrades). Only with HA.
      defaultPodDisruptionBudget = { enabled = var.high_availability }
      logAsJson                  = true
    }
    meshConfig = {
      accessLogFile         = var.access_logs ? "/dev/stdout" : ""
      accessLogEncoding     = "JSON"
      enablePrometheusMerge = true
      defaultConfig = {
        holdApplicationUntilProxyStarts = true # app containers start after the proxy is ready
      }
    }
  })]

  depends_on = [helm_release.istio_base]
}

resource "helm_release" "gateway" {
  name        = var.gateway_release_name
  repository  = var.istio_chart_repo
  chart       = "gateway"
  version     = var.istio_version
  namespace   = kubernetes_namespace_v1.ingress.metadata[0].name
  wait        = true # also waits for the LoadBalancer IP
  timeout     = 600
  description = "Istio ingress gateway (Envoy) on the public IP"

  values = [yamlencode({
    service = {
      type = "LoadBalancer"
      annotations = {
        "service.beta.kubernetes.io/azure-pip-name"                     = local.pip_name
        "service.beta.kubernetes.io/azure-load-balancer-resource-group" = local.pip_rg
      }
      loadBalancerSourceRanges = var.allowed_source_cidrs
      externalTrafficPolicy    = "Local" # keep client IPs; Azure probes the health-check node port
      ports = [
        { name = "status-port", port = 15021, protocol = "TCP", targetPort = 15021 },
        { name = "http2", port = 80, protocol = "TCP", targetPort = 80 },
        { name = "https", port = 443, protocol = "TCP", targetPort = 443 },
      ]
    }
    autoscaling = {
      enabled                        = true
      minReplicas                    = var.high_availability ? 2 : 1
      maxReplicas                    = var.gateway_max_replicas
      targetCPUUtilizationPercentage = 80
    }
    podDisruptionBudget = var.high_availability ? { minAvailable = 1 } : {}
    resources = {
      requests = { cpu = "100m", memory = "128Mi" }
      limits   = { cpu = "2", memory = "1Gi" }
    }
    labels = {
      "app.kubernetes.io/part-of" = "aks-sso-platform"
    }
  })]

  depends_on = [helm_release.istiod, kubernetes_secret_v1.gateway_tls]
}

# ---------------------------------------------------------------------------------------------------------------
# Gateway resource, mesh policy and monitoring (local chart in ./charts/gateway-config)
# ---------------------------------------------------------------------------------------------------------------
locals {
  gateway_config_dir  = "${path.module}/charts/gateway-config"
  gateway_config_hash = sha1(join("", [for f in sort(fileset(local.gateway_config_dir, "**")) : filesha1("${local.gateway_config_dir}/${f}")]))
}

resource "helm_release" "gateway_config" {
  name        = "gateway-config"
  chart       = local.gateway_config_dir
  namespace   = kubernetes_namespace_v1.ingress.metadata[0].name
  wait        = true
  timeout     = 300
  description = "Istio Gateway ${var.gateway_name}, mesh PeerAuthentication, PodMonitors"

  values = [yamlencode({
    publicUrl = local.public_url
    gateway = {
      name           = var.gateway_name
      selector       = { istio = trimprefix(var.gateway_release_name, "istio-") }
      hosts          = ["*"]
      httpsRedirect  = var.https_redirect
      credentialName = kubernetes_secret_v1.gateway_tls.metadata[0].name
    }
    peerAuthentication = {
      enabled   = true
      namespace = var.istio_namespace
      mode      = var.mtls_mode
    }
    monitoring = {
      podMonitors    = true
      istioNamespace = var.istio_namespace
    }
    chartHash = local.gateway_config_hash # forces an upgrade when a template changes
  })]

  depends_on = [helm_release.gateway]
}

# Read back the Service after the gateway release (helm --wait guarantees the LB IP is assigned).
data "kubernetes_service_v1" "gateway" {
  metadata {
    name      = var.gateway_release_name
    namespace = var.ingress_namespace
  }
  depends_on = [helm_release.gateway]
}
