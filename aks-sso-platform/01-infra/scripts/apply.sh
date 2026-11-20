#!/usr/bin/env bash
# Create (or update) the AKS platform: resource group, VNet + NSG, public IP, monitoring, ACR, cluster.
#
#   scripts/apply.sh [--yes] [--plan-only] [--upgrade] [--var-file FILE] [--no-credentials]
#
#   --yes             no prompts (CI)
#   --plan-only       show the plan and stop
#   --upgrade         terraform init -upgrade (newer provider versions within the constraints)
#   --var-file FILE   extra .tfvars (terraform.tfvars is picked up automatically)
#   --no-credentials  do not run `az aks get-credentials` at the end
#
# Takes ~10-15 minutes on first run. Safe to re-run: Terraform only changes what differs.
source "$(dirname "$0")/lib.sh"
PLAN_ONLY=0; UPGRADE=0; VAR_FILE=""; GET_CREDS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) YES=1 ;; --plan-only) PLAN_ONLY=1 ;; --upgrade) UPGRADE=1 ;; --no-credentials) GET_CREDS=0 ;;
    --var-file) VAR_FILE="$2"; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) die "unknown flag $1 (see --help)" ;;
  esac; shift
done
export UPGRADE VAR_FILE

need az terraform python3
az_session
ensure_providers
tf_init

TFARGS=(-input=false)
[ -n "$VAR_FILE" ] && TFARGS+=(-var-file="$VAR_FILE")

info "terraform validate"
tf validate >/dev/null && ok "configuration valid"

info "terraform plan"
: > "$LOG"
set +e
tf plan "${TFARGS[@]}" -out=tfplan -detailed-exitcode 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e
case "$RC" in
  0) ok "no changes — infrastructure already matches the configuration" ;;
  2) ok "plan ready" ;;
  *) explain_failure; die "plan failed" ;;
esac

if [ "$PLAN_ONLY" = 1 ]; then info "plan only — stopping (plan file: $DIR/tfplan)"; exit 0; fi
if [ "$RC" = 2 ]; then
  echo
  warn "about to create/modify Azure resources in subscription $ARM_SUBSCRIPTION_ID (costs money: see README → Costs)"
  confirm "apply this plan?"
  info "terraform apply (10-15 min on first run — the cluster alone takes most of it)"
  set +e
  tf apply -input=false tfplan 2>&1 | tee -a "$LOG"
  RC=${PIPESTATUS[0]}
  set -e
  if [ "$RC" != 0 ]; then
    explain_failure
    die "apply failed — fix the cause and re-run scripts/apply.sh (Terraform continues where it stopped)"
  fi
  ok "apply complete"
fi
rm -f "$DIR/tfplan"

write_handoff

if [ "$GET_CREDS" = 1 ]; then
  RESOURCE_GROUP="$(tf_out resource_group_name)"; CLUSTER_NAME="$(tf_out cluster_name)"
  info "kubectl credentials"
  if command -v kubectl >/dev/null 2>&1; then
    run az aks get-credentials -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --overwrite-existing
    kubectl get nodes -o wide 2>/dev/null || warn "kubectl could not list nodes yet (Entra RBAC? run: kubelogin convert-kubeconfig -l azurecli)"
  else
    warn "kubectl not installed:  az aks install-cli   then   $(tf_out kube_config_command)"
  fi
fi

echo
info "01-infra done"
echo "    cluster:    $(tf_out cluster_name)  (rg $(tf_out resource_group_name), $(tf_out location), k8s $(tf_out kubernetes_version))"
echo "    public IP:  $(tf_out ingress_public_ip)   →  https://$(tf_out ingress_fqdn)  (nothing listens yet)"
[ -n "$(tf_out acr_login_server)" ] && echo "    registry:   $(tf_out acr_login_server)"
[ -n "$(tf_out grafana_endpoint)" ] && echo "    grafana:    $(tf_out grafana_endpoint)"
echo "    verify:     scripts/test.sh        details: scripts/cluster-info.sh"
echo "    next:       cd ../02-istio-gateway && scripts/apply.sh"
