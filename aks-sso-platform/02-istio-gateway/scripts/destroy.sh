#!/usr/bin/env bash
# Remove Istio and the gateway from the cluster (the public IP from 01-infra is kept; one created here is deleted).
#
#   scripts/destroy.sh [--yes]
#
# Run 03-apps-keycloak/scripts/uninstall.sh first if it is installed — its VirtualServices and sidecars depend on Istio.
source "$(dirname "$0")/lib.sh"
while [ $# -gt 0 ]; do case "$1" in --yes|-y) YES=1 ;; -h|--help) sed -n '2,7p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift; done
need az terraform kubectl
az_session
resolve_inputs
tf_init
ensure_kubecontext

if kubectl get virtualservices -A --no-headers 2>/dev/null | grep -v "^istio-" | grep -q .; then
  warn "VirtualServices still exist (apps are published through this gateway):"
  kubectl get virtualservices -A 2>/dev/null | sed 's/^/      /'
  confirm "remove Istio anyway? those apps go offline"
else
  confirm "remove Istio + gateway from $CLUSTER_NAME?"
fi

: > "$LOG"; set +e
tf destroy -input=false -auto-approve 2>&1 | tee -a "$LOG"; RC=${PIPESTATUS[0]}; set -e
if [ "$RC" != 0 ]; then
  explain_failure
  echo "    • if a namespace hangs in Terminating: kubectl get ns istio-system -o json | jq '.spec.finalizers=[]' | kubectl replace --raw /api/v1/namespaces/istio-system/finalize -f -"
  die "destroy failed"
fi
# Istio CRDs are left by the base chart on purpose (helm keeps CRDs). Remove them so a fresh install starts clean.
if kubectl get crd gateways.networking.istio.io >/dev/null 2>&1; then
  confirm "also delete the Istio CRDs (removes every VirtualService/Gateway object in the cluster)?" && \
    run kubectl get crd -o name | grep 'istio.io' | xargs -r kubectl delete >/dev/null && ok "Istio CRDs removed" || true
fi
rm -f "$GATEWAY_ENV" "$DIR/gateway.crt"
ok "02-istio-gateway destroyed"
