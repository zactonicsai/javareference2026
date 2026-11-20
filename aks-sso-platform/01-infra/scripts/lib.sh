# shellcheck shell=bash
# Shared helpers for 01-infra scripts. Source it; do not run it.
set -eo pipefail
# macOS ships bash 3.2, where `set -u` trips over empty arrays; only enable it on bash >= 4.4
if [ "${BASH_VERSINFO[0]}" -gt 4 ] || { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -ge 4 ]; }; then set -u; fi

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"          # 01-infra
PLATFORM_ROOT="$(cd "$DIR/.." && pwd)"                            # repo root
HANDOFF_DIR="$PLATFORM_ROOT/.platform"                            # git-ignored hand-off between directories
HANDOFF="$HANDOFF_DIR/infra.env"
LOG="$DIR/.terraform-last-run.log"
YES="${YES:-0}"

c_bold=$'\e[1m'; c_dim=$'\e[2m'; c_ok=$'\e[32m'; c_warn=$'\e[33m'; c_err=$'\e[31m'; c_off=$'\e[0m'
info() { echo "${c_bold}==>${c_off} $*"; }
ok()   { echo "${c_ok}  ✓${c_off} $*"; }
warn() { echo "${c_warn}  !${c_off} $*"; }
fail() { echo "${c_err}  ✗${c_off} $*"; }
die()  { echo "${c_err}error:${c_off} $*" >&2; exit 1; }
run()  { echo "${c_dim}  \$ $*${c_off}"; "$@"; }
need() { for b in "$@"; do command -v "$b" >/dev/null 2>&1 || die "'$b' is required but not on PATH"; done; }
confirm() {   # $1 prompt
  [ "$YES" = 1 ] && return 0
  [ -t 0 ] || die "non-interactive shell: re-run with --yes"
  read -r -p "    $1 [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || die "aborted"
}

# ---- Azure session ------------------------------------------------------------------------------------------
az_session() {
  need az
  az account show >/dev/null 2>&1 || die "not logged in to Azure: run  az login"
  ARM_SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
  ARM_TENANT_ID="$(az account show --query tenantId -o tsv)"
  export ARM_SUBSCRIPTION_ID ARM_TENANT_ID
  info "Azure subscription: ${c_bold}$(az account show --query name -o tsv)${c_off} ${c_dim}($ARM_SUBSCRIPTION_ID)${c_off}"
}

# Resource providers the stack needs. Registering is a one-time, per-subscription step that needs
# Contributor rights; a missing registration is the #1 reason a first `terraform apply` fails.
PROVIDERS="Microsoft.ContainerService Microsoft.Network Microsoft.Compute Microsoft.Storage Microsoft.OperationalInsights Microsoft.OperationsManagement Microsoft.Insights Microsoft.Monitor Microsoft.AlertsManagement Microsoft.ContainerRegistry Microsoft.ManagedIdentity Microsoft.Dashboard Microsoft.PolicyInsights Microsoft.KeyVault"
ensure_providers() {
  local missing=""
  for p in $PROVIDERS; do
    local st; st="$(az provider show -n "$p" --query registrationState -o tsv 2>/dev/null || echo Unknown)"
    [ "$st" = Registered ] || missing="$missing $p"
  done
  if [ -z "$missing" ]; then ok "all resource providers registered"; return 0; fi
  warn "resource providers not registered:$missing"
  confirm "register them now (takes 1-3 minutes)?"
  for p in $missing; do run az provider register -n "$p" >/dev/null; done
  for _ in $(seq 1 60); do
    local pending=""
    for p in $missing; do [ "$(az provider show -n "$p" --query registrationState -o tsv)" = Registered ] || pending="$pending $p"; done
    [ -z "$pending" ] && { ok "providers registered"; return 0; }
    sleep 5
  done
  die "providers still registering:$pending — wait a bit and re-run"
}

# ---- Terraform ------------------------------------------------------------------------------------------------
tf() { ( cd "$DIR" && terraform "$@" ); }
tf_init() {
  need terraform
  local v; v="$(terraform version -json 2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin)["terraform_version"])' 2>/dev/null || terraform version | head -1)"
  info "terraform $v in $DIR"
  if [ ! -d "$DIR/.terraform" ] || [ "${UPGRADE:-0}" = 1 ]; then
    run tf init -input=false -upgrade="$([ "${UPGRADE:-0}" = 1 ] && echo true || echo false)" >/dev/null
  fi
  if [ ! -f "$DIR/terraform.tfvars" ] && [ -z "${VAR_FILE:-}" ]; then
    warn "no terraform.tfvars — using defaults (copy terraform.tfvars.example to customise)"
  fi
}
tf_out() { tf output -raw "$1" 2>/dev/null || true; }   # "" when state has no such output yet

# Explain the usual suspects when terraform fails (reads the saved log).
explain_failure() {
  [ -f "$LOG" ] || return 0
  echo
  fail "terraform failed — hints based on the error text:"
  if grep -q "MissingSubscriptionRegistration\|not registered to use namespace" "$LOG"; then
    echo "    • a resource provider is not registered: scripts/apply.sh registers them, or  az provider register -n <namespace>"; fi
  if grep -qi "QuotaExceeded\|exceeding approved .* quota\|OperationNotAllowed.*quota" "$LOG"; then
    echo "    • vCPU quota: request more for the VM family in Portal → Quotas, or pick a smaller/other size (system_vm_size / user_vm_size)"; fi
  if grep -qi "SkuNotAvailable\|not available in location\|ZonalAllocationFailed\|AllocationFailed" "$LOG"; then
    echo "    • VM size/zone not available in this region: change *_vm_size or set availability_zones = []"; fi
  if grep -qi "AuthorizationFailed\|does not have authorization" "$LOG"; then
    echo "    • your account lacks a role: Contributor + User Access Administrator (for role assignments) on the subscription or RG"; fi
  if grep -qi "LinkedAuthorizationFailed\|SubnetRoleAssignment\|does not have permission.*subnet" "$LOG"; then
    echo "    • the cluster identity's Network Contributor grant had not propagated: simply re-run  scripts/apply.sh"; fi
  if grep -qi "DnsRecordInUse\|DomainNameLabel.*already\|already in use" "$LOG"; then
    echo "    • DNS label taken: set ingress_dns_label to something unique"; fi
  if grep -qi "EphemeralOSDisk\|ephemeral" "$LOG"; then
    echo "    • VM size cannot host an ephemeral OS disk of os_disk_size_gb: set os_disk_type = \"Managed\" or a smaller os_disk_size_gb"; fi
  if grep -qi "K8sVersionNotSupported\|not supported in this region\|AgentPoolK8sVersionNotSupported" "$LOG"; then
    echo "    • kubernetes_version not offered here:  az aks get-versions -l <location> -o table"; fi
  if grep -qi "connection reset\|TLS handshake timeout\|context deadline exceeded\|i/o timeout" "$LOG"; then
    echo "    • transient network error: re-run  scripts/apply.sh (Terraform resumes from state)"; fi
  echo "    full log: $LOG"
}

# ---- hand-off file (read by 02-istio-gateway and 03-apps-keycloak) ---------------------------------------------
write_handoff() {
  mkdir -p "$HANDOFF_DIR"
  cat > "$HANDOFF" <<EOF
# generated by 01-infra/scripts/apply.sh — $(date -u +%FT%TZ). Safe to delete; the other directories also accept flags/env.
SUBSCRIPTION_ID=$ARM_SUBSCRIPTION_ID
RESOURCE_GROUP=$(tf_out resource_group_name)
NODE_RESOURCE_GROUP=$(tf_out node_resource_group)
LOCATION=$(tf_out location)
CLUSTER_NAME=$(tf_out cluster_name)
PUBLIC_IP_NAME=$(tf_out ingress_public_ip_name)
PUBLIC_IP_RESOURCE_GROUP=$(tf_out ingress_public_ip_resource_group)
PUBLIC_IP=$(tf_out ingress_public_ip)
PUBLIC_FQDN=$(tf_out ingress_fqdn)
ALLOWED_SOURCE_CIDRS=$(tf output -json allowed_source_cidrs 2>/dev/null | python3 -c 'import sys,json;print(",".join(json.load(sys.stdin)))' 2>/dev/null || echo 0.0.0.0/0)
ACR_NAME=$(tf_out acr_name)
ACR_LOGIN_SERVER=$(tf_out acr_login_server)
LOG_ANALYTICS_WORKSPACE_ID=$(tf_out log_analytics_workspace_id)
MONITOR_WORKSPACE_ID=$(tf_out monitor_workspace_id)
GRAFANA_ENDPOINT=$(tf_out grafana_endpoint)
EOF
  chmod 600 "$HANDOFF"
  ok "hand-off written: $HANDOFF"
}
load_handoff() { [ -f "$HANDOFF" ] && { set +u 2>/dev/null; source "$HANDOFF"; }; return 0; }

# Resolve RG / cluster from (1) env, (2) terraform state, (3) hand-off file.
resolve_cluster() {
  RESOURCE_GROUP="${RESOURCE_GROUP:-$(tf_out resource_group_name)}"
  CLUSTER_NAME="${CLUSTER_NAME:-$(tf_out cluster_name)}"
  if [ -z "$RESOURCE_GROUP" ] || [ -z "$CLUSTER_NAME" ]; then load_handoff; fi
  [ -n "${RESOURCE_GROUP:-}" ] && [ -n "${CLUSTER_NAME:-}" ] || die "cannot work out the cluster: run scripts/apply.sh first, or set RESOURCE_GROUP and CLUSTER_NAME"
}

# kubectl context for this cluster (fetch credentials if missing)
ensure_kubecontext() {
  need kubectl
  if ! kubectl config get-contexts -o name 2>/dev/null | grep -qx "$CLUSTER_NAME"; then
    info "fetching kubeconfig for $CLUSTER_NAME"
    run az aks get-credentials -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --overwrite-existing >/dev/null
  fi
  kubectl config use-context "$CLUSTER_NAME" >/dev/null
}
