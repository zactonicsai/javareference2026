#!/usr/bin/env bash
# Verify the infrastructure 01-infra created. Read-only. Exit code = number of failed checks.
#
#   scripts/test.sh [--yes]          (env RESOURCE_GROUP / CLUSTER_NAME override the Terraform state)
source "$(dirname "$0")/lib.sh"
while [ $# -gt 0 ]; do case "$1" in --yes|-y) YES=1 ;; -h|--help) sed -n '2,4p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift; done
need az
az_session
command -v terraform >/dev/null 2>&1 && [ -d "$DIR/.terraform" ] || true
resolve_cluster
PASS=0; FAIL=0
check() {  # $1 description, $2.. command (success = pass)
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$d"; PASS=$((PASS+1)); else fail "$d"; FAIL=$((FAIL+1)); fi
}
section() { echo; info "$*"; }

section "Azure resources (rg $RESOURCE_GROUP)"
check "resource group exists" az group show -n "$RESOURCE_GROUP"
STATE="$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query provisioningState -o tsv 2>/dev/null || true)"
POWER="$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query powerState.code -o tsv 2>/dev/null || true)"
check "cluster $CLUSTER_NAME provisioned (state: ${STATE:-missing})" [ "$STATE" = Succeeded ]
check "cluster running (power: ${POWER:-?})" [ "$POWER" = Running ]
NP="$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query "agentPoolProfiles[].{n:name,m:mode,s:provisioningState}" -o tsv 2>/dev/null || true)"
check "system + user node pools" bash -c "echo '$NP' | grep -q System && echo '$NP' | grep -q User"
check "all node pools Succeeded" bash -c "! echo '$NP' | grep -v Succeeded | grep -q ."
NET="$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query "networkProfile.{p:networkPlugin,m:networkPluginMode,d:networkDataplane,o:outboundType}" -o tsv 2>/dev/null || true)"
check "Azure CNI overlay + Cilium ($NET)" bash -c "echo '$NET' | grep -q cilium"
check "OIDC issuer + workload identity" bash -c "[ \"\$(az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME --query 'securityProfile.workloadIdentity.enabled' -o tsv)\" = true ]"
check "Container Insights add-on enabled" bash -c "[ \"\$(az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME --query 'addonProfiles.omsagent.enabled' -o tsv)\" = true ]"
check "Managed Prometheus enabled" bash -c "[ \"\$(az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME --query 'azureMonitorProfile.metrics.enabled' -o tsv)\" = true ]"
check "auto-upgrade channel = patch" bash -c "[ \"\$(az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME --query 'autoUpgradeProfile.upgradeChannel' -o tsv)\" = patch ]"

section "Network"
PIP_NAME="${PUBLIC_IP_NAME:-$(tf_out ingress_public_ip_name)}"; PIP_NAME="${PIP_NAME:-$CLUSTER_NAME-ingress-pip}"
PIP="$(az network public-ip show -g "$RESOURCE_GROUP" -n "$PIP_NAME" --query "{ip:ipAddress,fqdn:dnsSettings.fqdn,sku:sku.name}" -o tsv 2>/dev/null || true)"
check "public IP $PIP_NAME exists ($PIP)" [ -n "$PIP" ]
check "public IP is Standard SKU" bash -c "echo '$PIP' | grep -q Standard"
check "public IP has a DNS name" bash -c "echo '$PIP' | grep -q cloudapp"
NSG="$(az network nsg list -g "$RESOURCE_GROUP" --query "[?contains(name,'nodes-nsg')].name | [0]" -o tsv 2>/dev/null || true)"
check "node subnet NSG exists ($NSG)" [ -n "$NSG" ]
check "NSG allows 80 to the gateway IP" bash -c "az network nsg rule show -g $RESOURCE_GROUP --nsg-name '$NSG' -n AllowGatewayHTTP --query access -o tsv | grep -q Allow"
check "NSG allows 443 to the gateway IP" bash -c "az network nsg rule show -g $RESOURCE_GROUP --nsg-name '$NSG' -n AllowGatewayHTTPS --query access -o tsv | grep -q Allow"
check "NSG denies other Internet traffic" bash -c "az network nsg rule show -g $RESOURCE_GROUP --nsg-name '$NSG' -n DenyInternetInbound --query access -o tsv | grep -q Deny"
SRC="$(az network nsg rule show -g "$RESOURCE_GROUP" --nsg-name "$NSG" -n AllowGatewayHTTPS --query "sourceAddressPrefixes" -o tsv 2>/dev/null | tr '\n' ' ')"
[ -n "$SRC" ] && echo "    allowed sources: $SRC"
check "subnet is associated with the NSG" bash -c "az network vnet subnet show -g $RESOURCE_GROUP --vnet-name $CLUSTER_NAME-vnet -n snet-aks-nodes --query networkSecurityGroup.id -o tsv | grep -q ."

section "Identity & registry"
check "cluster identity has Network Contributor on the RG" bash -c "az role assignment list -g $RESOURCE_GROUP --query \"[?roleDefinitionName=='Network Contributor'] | length(@)\" -o tsv | grep -qv '^0\$'"
ACR="${ACR_NAME:-$(tf_out acr_name)}"
if [ -n "$ACR" ]; then
  check "ACR $ACR exists" az acr show -n "$ACR"
  KUBELET="$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query identityProfile.kubeletidentity.objectId -o tsv 2>/dev/null || true)"
  check "kubelet identity has AcrPull" bash -c "az role assignment list --assignee '$KUBELET' --scope \"\$(az acr show -n $ACR --query id -o tsv)\" --query \"[?roleDefinitionName=='AcrPull'] | length(@)\" -o tsv | grep -qv '^0\$'"
else
  warn "no ACR in this deployment (create_acr = false) — 03-apps-keycloak needs some registry"
fi

section "Monitoring"
check "Log Analytics workspace" bash -c "az monitor log-analytics workspace show -g $RESOURCE_GROUP -n $CLUSTER_NAME-law --query provisioningState -o tsv | grep -q Succeeded"
check "Azure Monitor (Prometheus) workspace" bash -c "az resource show -g $RESOURCE_GROUP -n $CLUSTER_NAME-amw --resource-type Microsoft.Monitor/accounts --query id -o tsv | grep -q ."
check "Prometheus data collection rule association" bash -c "az monitor data-collection rule association list --resource \"\$(az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME --query id -o tsv)\" --query \"[?contains(name,'MSProm')] | length(@)\" -o tsv | grep -qv '^0\$'"
check "control-plane diagnostic setting" bash -c "az monitor diagnostic-settings list --resource \"\$(az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME --query id -o tsv)\" --query \"[?name=='control-plane-logs'] | length(@)\" -o tsv | grep -qv '^0\$'"
NALERTS="$(az monitor metrics alert list -g "$RESOURCE_GROUP" --query "length(@)" -o tsv 2>/dev/null || echo 0)"
check "metric alerts defined ($NALERTS)" [ "${NALERTS:-0}" -ge 4 ]
RECV="$(az monitor action-group show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME-alerts" --query "length(emailReceivers)" -o tsv 2>/dev/null || echo 0)"
[ "${RECV:-0}" -gt 0 ] && ok "action group has $RECV e-mail receiver(s)" || warn "action group has no receiver — set alert_email in terraform.tfvars to get notified"

section "Kubernetes (via kubectl)"
if command -v kubectl >/dev/null 2>&1; then
  ensure_kubecontext
  if kubectl get nodes >/dev/null 2>&1; then
    NOTREADY="$(kubectl get nodes --no-headers 2>/dev/null | awk '$2!="Ready"' | wc -l | tr -d ' ')"
    check "all nodes Ready ($(kubectl get nodes --no-headers | wc -l | tr -d ' ') nodes)" [ "$NOTREADY" = 0 ]
    check "system pods healthy (kube-system)" bash -c "! kubectl -n kube-system get pods --no-headers | grep -Ev 'Running|Completed' | grep -q ."
    check "Container Insights agent running (ama-logs)" bash -c "kubectl -n kube-system get pods -l 'dsName=ama-logs' --no-headers 2>/dev/null | grep -q Running || kubectl -n kube-system get ds ama-logs"
    check "Prometheus agent running (ama-metrics)" bash -c "kubectl -n kube-system get deploy ama-metrics -o jsonpath='{.status.readyReplicas}' | grep -qv '^0\$'"
    check "Cilium agent running" bash -c "kubectl -n kube-system get pods -l k8s-app=cilium --no-headers | grep -q Running"
    check "CoreDNS resolves" bash -c "kubectl -n kube-system get deploy coredns -o jsonpath='{.status.readyReplicas}' | grep -qv '^0\$'"
    kubectl get ns istio-system >/dev/null 2>&1 && ok "Istio already present (02-istio-gateway applied)" || echo "    (Istio not installed yet — 02-istio-gateway does that)"
  else
    fail "kubectl cannot reach the API server (authorized IP ranges? Entra RBAC without kubelogin?)"; FAIL=$((FAIL+1))
  fi
else
  warn "kubectl not installed — skipping in-cluster checks (az aks install-cli)"
fi

echo
if [ "$FAIL" = 0 ]; then ok "${c_bold}$PASS checks passed${c_off} — infrastructure is ready for 02-istio-gateway"; else fail "${c_bold}$FAIL failed, $PASS passed${c_off}"; fi
exit "$FAIL"
