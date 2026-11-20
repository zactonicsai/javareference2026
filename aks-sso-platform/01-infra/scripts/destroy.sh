#!/usr/bin/env bash
# Delete everything 01-infra created (cluster, network, monitoring, ACR, resource group).
#
#   scripts/destroy.sh [--yes] [--var-file FILE]
#
# Order matters: run 03-apps-keycloak/scripts/uninstall.sh and 02-istio-gateway/scripts/destroy.sh first if
# you want a clean Terraform state there — but it is not required, everything dies with the cluster anyway.
source "$(dirname "$0")/lib.sh"
VAR_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in --yes|-y) YES=1 ;; --var-file) VAR_FILE="$2"; shift ;; -h|--help) sed -n '2,8p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift
done
need az terraform
az_session
tf_init
RG="$(tf_out resource_group_name)"; CL="$(tf_out cluster_name)"
[ -n "$RG" ] || die "no state found in $DIR — nothing to destroy"

TFARGS=(-input=false)
[ -n "$VAR_FILE" ] && TFARGS+=(-var-file="$VAR_FILE")

warn "this deletes resource group ${c_bold}$RG${c_off} (cluster $CL) and EVERYTHING in it, including Keycloak's database volume"
if [ "$YES" != 1 ]; then
  [ -t 0 ] || die "non-interactive shell: re-run with --yes"
  read -r -p "    type the resource group name to confirm: " a; [ "$a" = "$RG" ] || die "aborted"
fi

# Kubernetes LoadBalancer Services create Azure resources Terraform does not know about (LB rules, the node-RG
# NSG rules). Deleting the gateway Service first keeps Azure from refusing to delete the subnet/public IP.
if command -v kubectl >/dev/null 2>&1 && kubectl config get-contexts -o name 2>/dev/null | grep -qx "$CL"; then
  info "removing LoadBalancer Services on $CL so Azure releases the public IP cleanly"
  kubectl --context "$CL" get svc -A --field-selector spec.type=LoadBalancer -o custom-columns=NS:.metadata.namespace,NAME:.metadata.name --no-headers 2>/dev/null \
    | while read -r ns name; do [ -n "$name" ] && run kubectl --context "$CL" -n "$ns" delete svc "$name" --wait=false || true; done
  sleep 20
fi

: > "$LOG"
set +e
tf destroy "${TFARGS[@]}" -auto-approve 2>&1 | tee -a "$LOG"
RC=${PIPESTATUS[0]}
set -e
if [ "$RC" != 0 ]; then
  explain_failure
  echo "    • if the public IP or subnet is 'InUse': wait a minute for the LB to release it and re-run"
  die "destroy failed"
fi

rm -f "$HANDOFF"
if command -v kubectl >/dev/null 2>&1; then
  kubectl config delete-context "$CL" >/dev/null 2>&1 && ok "kubectl context $CL removed" || true
  kubectl config delete-cluster "$CL" >/dev/null 2>&1 || true
  kubectl config delete-user "clusterUser_${RG}_${CL}" >/dev/null 2>&1 || true
fi
ok "01-infra destroyed"
