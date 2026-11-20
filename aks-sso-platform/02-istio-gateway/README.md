# 02-istio-gateway — Istio ingress gateway with a self-signed certificate (Terraform → Helm)

Installs upstream Istio on an existing AKS cluster and publishes **one** HTTPS entry point that every later
service plugs into with a `VirtualService`:

```
Internet ──443──▶ Azure LB (public IP from 01-infra) ──▶ istio-ingress/istio-ingress (Envoy pods, HPA)
                     │                                       │ Gateway istio-ingress/public-gateway
                     └──80──▶ 301 → https                    │   hosts: "*"   TLS: SIMPLE, secret gateway-tls
                                                             ▼
                                             VirtualService /auth  → keycloak        (03-apps-keycloak)
                                             VirtualService /      → launchpad       (03-apps-keycloak)
                                             VirtualService /x     → your next service
```

Terraform does three things, in order:

1. `helm_release` **istio-base** → **istiod** → **gateway** from the official charts
   (`https://blob.istio.io/istio-release/charts`, version `istio_version`), the gateway Service annotated with
   `service.beta.kubernetes.io/azure-pip-name` so Azure attaches *your* static IP, `externalTrafficPolicy: Local`,
   `loadBalancerSourceRanges` mirroring the NSG list.
2. `tls_self_signed_cert` (ECDSA P-256, 1 year, SANs = the IP's DNS name, `*.`that name, the IP itself, plus
   `extra_dns_names`) → Secret `istio-ingress/gateway-tls`, and a copy in `./gateway.crt` for your trust store.
3. The local chart `charts/gateway-config`: the `Gateway` (80 → redirect, 443 → TLS), the mesh-wide
   `PeerAuthentication`, and two `PodMonitor`s for Managed Prometheus (istiod + all Envoy proxies; created only
   when the Azure CRD exists).

## Run it

```bash
cd 02-istio-gateway
scripts/apply.sh                 # reads ../.platform/infra.env written by 01-infra
scripts/test.sh                  # pods, IP binding, HTTP→HTTPS, TLS SANs, Envoy header, PodMonitors
scripts/trust-cert.sh            # (optional) trust gateway.crt in macOS/Linux so the browser shows a padlock
```

On a cluster **not** built by 01-infra:

```bash
scripts/apply.sh --rg my-rg --cluster my-aks                          # creates a public IP in the node RG
scripts/apply.sh --rg my-rg --cluster my-aks --pip my-pip --pip-rg my-rg --allow 203.0.113.0/24
```

Requirements: `az`, `terraform ≥ 1.9`, `kubectl`, `curl`, `openssl`; the cluster must be running and reachable
from your machine; the cluster identity needs *Network Contributor* on the public IP's resource group (01-infra
grants it; the node resource group always works). Kubernetes ≥ 1.29 for native sidecars (set
`native_sidecars = false` otherwise). No Istio add-on on the cluster (the script warns).

Outputs worth knowing: `terraform output public_url`, `gateway` (`istio-ingress/public-gateway`),
`certificate_expires`, `certificate_pem`. `scripts/apply.sh` also writes `../.platform/gateway.env` for 03.

## Publishing a service (how 03 and your future services use this)

```yaml
apiVersion: networking.istio.io/v1
kind: VirtualService
metadata: { name: my-app, namespace: my-namespace }
spec:
  hosts: ["*"]                                  # or the FQDN from `terraform output public_fqdn`
  gateways: ["istio-ingress/public-gateway"]
  http:
    - match: [{ uri: { prefix: /my-app/ } }, { uri: { exact: /my-app } }]
      route: [{ destination: { host: my-app.my-namespace.svc.cluster.local, port: { number: 8080 } } }]
```

Path-based routing on one host keeps a single certificate and a single IP. For host-based routing add a DNS
record (`*.apps.example.com` → the IP), put the name in `extra_dns_names`, and use `hosts: [svc.apps.example.com]`.

## Settings

| Variable | Default | Why |
|----------|---------|-----|
| `istio_version` | `1.31.4` | pin and bump deliberately; upgrades are in-place `terraform apply` |
| `high_availability` | `false` | `true` → 2 istiod + 2 gateway replicas with PDBs. Off by default because a PDB on a single replica blocks node drains |
| `https_redirect` | `true` | plain HTTP gets a 301 |
| `mtls_mode` | `PERMISSIVE` | `STRICT` once every caller is a mesh pod (03's Helm hook Jobs run without a sidecar and talk to Keycloak over plaintext) |
| `access_logs` | `true` | JSON Envoy access logs on stdout → Container Insights (`ContainerLogV2`) |
| `native_sidecars` | `true` | sidecar as a Kubernetes native sidecar so Jobs can finish |
| `cert_validity_hours` | `8760` | re-issued automatically 30 days before expiry on the next `apply` |
| `extra_dns_names` | `[]` | extra SANs when you point a real DNS name at the IP |
| `allowed_source_cidrs` | `["0.0.0.0/0"]` | `loadBalancerSourceRanges`; keep equal to 01-infra's NSG list (apply.sh copies it) |

## Scripts

| Script | What it does |
|--------|--------------|
| `scripts/apply.sh [--rg --cluster] [--pip --pip-rg] [--allow] [--plan-only] [--yes]` | resolves inputs (flags → env → previous run → 01-infra hand-off), checks the cluster and IP, `init/validate/plan/apply`, smoke-tests `https://<fqdn>/`, writes `../.platform/gateway.env` |
| `scripts/test.sh` | ~20 checks: istiod/gateway readiness, Service IP == provisioned IP, `externalTrafficPolicy`, Gateway + secret, HTTP 301, HTTPS reachable, `server: istio-envoy`, certificate SANs/expiry, PodMonitors, access logs, `istioctl analyze` if installed |
| `scripts/trust-cert.sh [--export | --remove]` | exports the live cert and adds it to the macOS System keychain / Linux CA store |
| `scripts/destroy.sh` | warns about existing VirtualServices, `terraform destroy`, optional Istio CRD cleanup |

## Why these choices

* **Upstream Istio via Helm, not the AKS add-on** — the add-on's Gateway-API flavour has no `VirtualService` /
  `AuthorizationPolicy` CRDs and pins versions. Helm releases driven by Terraform give repeatable upgrades
  (`istio_version` bump → `apply`) and a state file that says exactly what is installed.
* **Static IP from 01-infra by name** — the Service does not "own" the IP, so deleting the gateway (or the
  cluster) never loses the address or the DNS name your users bookmarked.
* **Self-signed cert generated by Terraform** — no cert-manager, no ACME, works on a bare `cloudapp.azure.com`
  name. The trade-off is one browser warning (or `scripts/trust-cert.sh`). When you own a domain, replace the
  secret with cert-manager + Let's Encrypt and leave everything else as is — the Gateway only knows the secret name.
* **`externalTrafficPolicy: Local`** — real client IPs in access logs and in `X-Forwarded-For` (Keycloak brute-force
  detection and audit need them); Azure probes the health-check node port so only nodes with a gateway pod receive traffic.
* **HTTPS redirect at the gateway** — one place, no app has to care.
* **PERMISSIVE mTLS by default** — safe rollout; nothing breaks while you add sidecars. Flip to STRICT when ready.
* **Native sidecars** — Istio proxies start before the app and stop when it ends; Helm hook Jobs and CronJobs
  complete instead of hanging with a running `istio-proxy`.
* **Monitoring built in** — JSON access logs to Container Insights; PodMonitors feed Managed Prometheus with
  `istio_requests_total`, `istio_request_duration_milliseconds_bucket`, `pilot_*`. Grafana (01-infra option) has the
  Istio dashboards (IDs 7639, 7636, 7645).

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Service stuck `<pending>` | `kubectl -n istio-ingress describe svc istio-ingress` — usually the identity lacks Network Contributor on the IP's RG, or the IP is Basic SKU / in use |
| `curl: (7) Failed to connect` / timeout | your IP is not in `allowed_source_cidrs` (01-infra `scripts/allow-ip.sh --me`), or the Azure LB probe fails: gateway pod not Ready |
| Browser: `NET::ERR_CERT_AUTHORITY_INVALID` | expected for a self-signed cert — `scripts/trust-cert.sh` or click through |
| `404` from `istio-envoy` | correct: no VirtualService matches yet |
| `plan` fails with `no such host blob.istio.io` | your machine cannot reach the chart repo (proxy / firewall); set `istio_chart_repo` to a mirror |
| `another operation is in progress` | `helm -n istio-system history istiod` → `helm rollback`, then re-run |
| upgrade of `istio_version` fails on CRDs | the base chart upgrades CRDs; if it stalls: `kubectl apply --server-side -f` the CRDs from the new release, re-run |
| STRICT mTLS breaks Keycloak client registration Jobs | expected — Jobs have no sidecar; either keep PERMISSIVE or add a port-level PeerAuthentication for Keycloak 8080 |

Next: [03-apps-keycloak](../03-apps-keycloak/README.md).
