#!/usr/bin/env bash
# Remove the apps (launchpad, every sso-proxy service, Keycloak). Keycloak's database volume is kept unless --purge.
#
#   scripts/uninstall.sh [--purge] [--namespace appsteam] [--yes]
#
#   --purge   also delete the Postgres volume (all users/clients/sessions), the namespace and the generated secrets file
source "$(dirname "$0")/lib.sh"
PURGE=0
while [ $# -gt 0 ]; do case "$1" in --purge) PURGE=1 ;; --namespace|-n) NAMESPACE="$2"; shift ;; --yes|-y) YES=1 ;; -h|--help) sed -n '2,6p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift; done
need kubectl helm python3
confirm_context
kubectl get ns "$NAMESPACE" >/dev/null 2>&1 || { ok "namespace $NAMESPACE does not exist — nothing to do"; exit 0; }
RELEASES="$(helm -n "$NAMESPACE" list -o json 2>/dev/null | python3 -c 'import sys,json; print(" ".join(r["name"]+":"+r["chart"] for r in json.load(sys.stdin)))' 2>/dev/null || true)"
info "releases in $NAMESPACE: ${RELEASES:-none}"
[ "$PURGE" = 1 ] && warn "--purge deletes the Keycloak database volume, the namespace and $(secrets_file)"
confirm "uninstall the apps from $NAMESPACE?"

# order matters: apps first (their pre-delete hooks remove their Keycloak clients while Keycloak is still up)
for entry in $RELEASES; do
  name="${entry%%:*}"; chart="${entry#*:}"
  case "$chart" in sso-proxy-*|launchpad-*) run helm -n "$NAMESPACE" uninstall "$name" --wait --timeout 5m || warn "uninstall of $name reported an error (continuing)" ;; esac
done
if release_exists "$KC_RELEASE"; then run helm -n "$NAMESPACE" uninstall "$KC_RELEASE" --wait --timeout 5m || warn "uninstall of $KC_RELEASE reported an error (continuing)"; fi
# leftovers of interrupted hooks
kubectl -n "$NAMESPACE" delete job -l app.kubernetes.io/part-of=aks-sso-platform --ignore-not-found >/dev/null 2>&1 || true

if [ "$PURGE" = 1 ]; then
  run kubectl -n "$NAMESPACE" delete pvc --all --ignore-not-found
  run kubectl delete ns "$NAMESPACE" --ignore-not-found --wait=true
  rm -f "$(secrets_file)"; ok "secrets file removed"
else
  PVC="$(kubectl -n "$NAMESPACE" get pvc -o name 2>/dev/null | tr '\n' ' ')"
  [ -n "$PVC" ] && warn "kept volume(s): $PVC — Keycloak data survives a re-install; scripts/uninstall.sh --purge deletes them"
fi
ok "03-apps-keycloak removed"
