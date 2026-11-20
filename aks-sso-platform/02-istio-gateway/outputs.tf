output "public_ip" {
  description = "IP the gateway listens on."
  value       = local.pip_ip
}

output "public_fqdn" {
  description = "DNS name of the public IP (empty if the IP has no DNS label)."
  value       = local.pip_fqdn
}

output "public_url" {
  description = "Base URL for every service behind the gateway (03-apps-keycloak uses it)."
  value       = local.public_url
}

output "load_balancer_ip_assigned" {
  description = "IP Kubernetes reports on the gateway Service — must equal public_ip."
  value       = try(data.kubernetes_service_v1.gateway.status[0].load_balancer[0].ingress[0].ip, "")
}

output "gateway" {
  description = "Value for VirtualService.spec.gateways: <namespace>/<name>."
  value       = "${var.ingress_namespace}/${var.gateway_name}"
}

output "gateway_selector" {
  value = { istio = trimprefix(var.gateway_release_name, "istio-") }
}

output "tls_secret" {
  value = "${var.ingress_namespace}/${kubernetes_secret_v1.gateway_tls.metadata[0].name}"
}

output "certificate_pem" {
  description = "The self-signed certificate (public part). Import it into your OS/browser trust store: scripts/trust-cert.sh"
  value       = tls_self_signed_cert.gateway.cert_pem
}

output "certificate_expires" {
  value = tls_self_signed_cert.gateway.validity_end_time
}

output "certificate_sans" {
  value = concat(local.dns_names, [local.pip_ip])
}

output "istio_version" {
  value = var.istio_version
}

output "istio_namespace" {
  value = var.istio_namespace
}

output "ingress_namespace" {
  value = var.ingress_namespace
}

output "next_steps" {
  value = <<-EOT

    gateway:   ${local.public_url}   (expect HTTP 404 from istio-envoy until a VirtualService exists)
    test:      scripts/test.sh
    trust:     scripts/trust-cert.sh     (adds ./gateway.crt to your OS trust store so the browser stops warning)
    next:      cd ../03-apps-keycloak && scripts/install.sh
  EOT
}
