# 01-infra — AKS cluster, network and monitoring (Terraform)

Creates everything below the Kubernetes line with one `terraform apply`:

```
Resource group  rg-appsteam-platform
├── VNet 10.10.0.0/16
│   └── snet-aks-nodes 10.10.0.0/22  ← NSG: allow 80/443 → gateway IP from allowed_source_cidrs, deny other Internet
├── Public IP  aks-appsteam-ingress-pip  (Standard, static, DNS label → <label>.<region>.cloudapp.azure.com)
├── User-assigned identity  (Network Contributor on the RG — needed for BYO subnet + the public IP)
├── AKS  aks-appsteam
│   ├── system pool (CriticalAddonsOnly) + user pool, both autoscaled, zonal, ephemeral OS, Azure Linux
│   ├── Azure CNI overlay + Cilium (network policy + eBPF data plane)
│   ├── OIDC issuer, workload identity, Azure Policy, Key Vault secrets provider, image cleaner
│   ├── auto-upgrade: patch channel + NodeImage, maintenance windows at the weekend
│   └── add-ons: Container Insights, Managed Prometheus
├── Log Analytics workspace (control-plane + NSG diagnostics), Azure Monitor workspace (Prometheus)
├── Action group + 4 metric alerts (node CPU, node memory, failed pods, unschedulable pods)
├── Azure Container Registry (Basic) with AcrPull for the kubelet identity        [optional, default on]
└── Azure Managed Grafana wired to Prometheus                                    [optional, default off]
```

**Not** created here on purpose: anything inside the cluster. The public IP and NSG are the hand-off to
[02-istio-gateway](../02-istio-gateway/README.md), which binds the Istio ingress gateway to that IP and
terminates TLS with a self-signed certificate issued for the IP's DNS name.

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Azure CLI | ≥ 2.60 | `brew install azure-cli` |
| Terraform | ≥ 1.9 | `brew install terraform` |
| kubectl | any recent | `az aks install-cli` |
| python3, curl | (macOS has them) | — |

Permissions on the subscription: **Contributor** plus **User Access Administrator** (or Owner) — the stack
creates role assignments (Network Contributor for the cluster identity, AcrPull, Grafana roles).
Resource providers must be registered once per subscription; `scripts/apply.sh` checks and registers them.

Quota: the defaults need 8 vCPUs of `Standard_D4ds_v5` (2 nodes) and can scale to 32. Check with
`az vm list-usage -l centralus -o table | grep -i ddsv5`.

## Run it

```bash
cd 01-infra
cp terraform.tfvars.example terraform.tfvars      # optional: edit names, region, alert e-mail, sizes
az login
scripts/apply.sh                                  # plan → confirm → apply (10-15 min) → kubeconfig → hand-off file
scripts/test.sh                                   # ~30 read-only checks
scripts/cluster-info.sh                           # what is running, network, monitoring, cost drivers
```

What `apply.sh` leaves behind:

* `terraform.tfstate` in this directory (move it to Azure Storage with `backend.tf.example` for team use)
* `../.platform/infra.env` — resource names for the next directories (git-ignored; they also accept flags)
* a kubectl context named after the cluster

### Common variations

| Want | Set in `terraform.tfvars` |
|------|---------------------------|
| Only my laptop can reach the site | `scripts/allow-ip.sh --me` (writes `allowed_source_cidrs`) |
| 99.95 % control-plane SLA | `sku_tier = "Standard"` |
| Fixed egress IP / avoid SNAT port exhaustion | `outbound_type = "managedNATGateway"` |
| E-mail on alerts | `alert_email = "team@example.com"` |
| Dashboards | `enable_grafana = true` (then open `terraform output grafana_endpoint`) |
| Entra ID admins | `admin_group_object_ids = ["<group object id>"]` |
| Lock the API server | `api_server_authorized_ip_ranges = ["203.0.113.7/32"]` |
| Region without zones | `availability_zones = []` |
| Cheaper nodes | `system_vm_size = "Standard_D2ds_v5"`, `os_disk_type = "Managed"` if the size has no temp disk |

Re-run `scripts/apply.sh` after any change; Terraform shows the diff before touching anything.

## Scripts

| Script | What it does |
|--------|--------------|
| `scripts/apply.sh [--yes] [--plan-only] [--upgrade] [--var-file F]` | Login check, provider registration, `init/validate/plan`, confirmation, `apply`, kubeconfig, hand-off file. Explains the usual failure causes (quota, SKU, RBAC propagation, DNS label taken). |
| `scripts/test.sh` | Verifies Azure resources, NSG rules, identities, monitoring wiring and in-cluster health. Exit code = failed checks. |
| `scripts/cluster-info.sh [--azure-only] [--k8s-only] [--pods]` | Cluster, node pools, network, public/egress IPs, NSG rules, monitoring endpoints, alerts, registry, cost drivers, nodes, workloads per namespace, LoadBalancer services, recent warning events. |
| `scripts/allow-ip.sh --me / CIDR… / --open / --show` | Edits `allowed_source_cidrs` and applies only the two NSG rules. |
| `scripts/destroy.sh` | Deletes LoadBalancer Services first (so Azure releases the IP), then `terraform destroy`, then cleans kubeconfig. |

All scripts are bash 3.2-compatible (macOS default), idempotent, and print every command they run.

## Design notes / best practices applied

**Network**
* Bring-your-own VNet/subnet so the address plan is explicit and peering is possible later. Overlay pods
  (`10.244.0.0/16`) keep the subnet small (a /22 for nodes only).
* The public IP is created *outside* the AKS node resource group. Resources in the node RG are deleted with
  the cluster; this IP (and its DNS name, and your certificate) survive a cluster rebuild.
* Two NSGs sit in the inbound path — this subnet NSG (yours) and the NIC NSG AKS manages (auto-opened per
  LoadBalancer Service). Tighten access here; never edit the AKS-managed one.
* NSG rules target the gateway IP as destination (Azure LB rules use floating IP, so the NSG sees the public
  IP). To expose a *second* LoadBalancer Service you must add rules for its IP — intentional.
* Egress defaults to the load balancer (cheapest). `managedNATGateway` gives a stable egress IP and 64k SNAT
  ports per IP. Full egress lockdown (Azure Firewall + FQDN rules) is out of scope but the subnet is ready for a UDR.

**Cluster**
* User-assigned control-plane identity created first, with Network Contributor on the RG, plus a 90 s wait
  for RBAC propagation — the classic "cluster creation fails on subnet permissions" problem.
* System pool tainted `CriticalAddonsOnly=true:NoSchedule` so your workloads land on the user pool; both pools
  autoscale and spread across zones; ephemeral OS disks (faster, free); `max_surge = 33 %` upgrades with a 30 min
  drain timeout; `temporary_name_for_rotation` lets Terraform resize the system pool without recreating the cluster.
* Cilium network policy (NetworkPolicy objects are enforced — 03-apps-keycloak uses them).
* Workload identity + OIDC issuer so pods can get Azure tokens without secrets; Key Vault secrets provider for
  mounting Key Vault secrets later; Azure Policy for deployment safeguards; image cleaner for stale images.
* Patch auto-upgrade + NodeImage channel inside weekend maintenance windows.
* **Istio add-on not enabled**: the Azure add-on ships a curated version and, in its Gateway-API form, no Istio
  CRDs (`VirtualService`, `AuthorizationPolicy`). 02-istio-gateway installs upstream Istio from the official Helm
  charts instead. If you prefer the managed add-on, enable `service_mesh_profile` in `aks.tf` and skip the Istio
  releases in 02 (the gateway-config chart still applies).

**Monitoring**
* Container Insights (logs, live container view) → Log Analytics with a 5 GB/day cap and 30-day retention.
* Managed Prometheus → Azure Monitor workspace through an explicit DCE/DCR/DCRA (what the portal's "enable"
  button does). Pod/ServiceMonitor CRDs (`azmonitoring.coreos.com/v1`) become available; 02 and 03 ship
  PodMonitors for Istio, Keycloak and the Spring Boot app.
* Control-plane diagnostics: `kube-apiserver`, `kube-audit-admin` (mutating calls only — plain `kube-audit`
  is huge), controller-manager, scheduler, autoscaler, guard. NSG events/counters too.
* Four alerts that catch the usual trouble before users do; add `alert_email`. Grafana is optional (billed per user)
  — the Azure portal's Container Insights blades are free.

**State & secrets**
* No secrets live in this directory. The hand-off file contains names only.
* Local state is fine for one person; for a team copy `backend.tf.example` → `backend.tf` and run
  `terraform init -migrate-state`.

## Costs (list prices, centralus, USD/month, approximate)

| Item | Default | Note |
|------|---------|------|
| 2 × Standard_D4ds_v5 (1 system + 1 user) | ≈ 280 | the autoscaler can add up to 6 more |
| Control plane | 0 (Free) | 73 with `sku_tier = "Standard"` |
| Standard LB + rules + 2 public IPs | ≈ 25 | |
| Log Analytics | 0 – 15 | 5 GB/day cap, first 5 GB/month free |
| Managed Prometheus | 0 – 10 | per 10 M samples |
| ACR Basic | 5 | |
| NAT Gateway (optional) | 33 + data | |
| Grafana (optional) | 9 / active user | |

`az aks stop -g <rg> -n <cluster>` stops the nodes (keeps paying for disks/IPs/LB, ~USD 30/month); `scripts/destroy.sh`
removes everything.

## Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `MissingSubscriptionRegistration` | `scripts/apply.sh` registers providers; or `az provider register -n Microsoft.ContainerService` |
| `QuotaExceeded` / `OperationNotAllowed … cores` | request vCPU quota for the family in Portal → Quotas, or use a smaller `*_vm_size` |
| `SkuNotAvailable`, `ZonalAllocationFailed` | pick another VM size or `availability_zones = []` |
| `LinkedAuthorizationFailed` on the subnet | RBAC not propagated yet — just re-run `scripts/apply.sh` |
| `DnsRecordInUse` | set `ingress_dns_label` to something unique |
| `terraform destroy` stuck on the public IP | a LoadBalancer Service still references it: `kubectl delete svc -n istio-ingress istio-ingress` then re-run |
| kubectl `Unauthorized` after enabling Entra RBAC | `az aks get-credentials …` again and `kubelogin convert-kubeconfig -l azurecli` |
| nodes `NotReady` right after creation | wait 2-3 minutes; Cilium and the monitoring agents start first |

Next: [02-istio-gateway](../02-istio-gateway/README.md).
