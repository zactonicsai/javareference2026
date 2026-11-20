#!/usr/bin/env bash
# What is running in the apps namespace: Keycloak realm (users, groups, clients), launchpad tiles + live status,
# SSO-proxied services, routes on the gateway, pods and recent warnings. Read-only.
#
#   scripts/cluster-info.sh [--namespace appsteam] [--pods]
source "$(dirname "$0")/lib.sh"
PODS=0
while [ $# -gt 0 ]; do case "$1" in --namespace|-n) NAMESPACE="$2"; shift ;; --pods) PODS=1 ;; -h|--help) sed -n '2,5p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift; done
need kubectl helm python3 curl
YES=1; confirm_context
PUBLIC_URL="$(detect_public_url 2>/dev/null || echo '?')"; PUBLIC_URL="${PUBLIC_URL%/}"
kubectl get ns "$NAMESPACE" >/dev/null 2>&1 || die "namespace $NAMESPACE does not exist (scripts/install.sh)"

info "Public entry point: ${c_bold}$PUBLIC_URL${c_off}   gateway $GATEWAY"
echo "    Keycloak:   $PUBLIC_URL$KC_PATH/admin/      launchpad: $PUBLIC_URL/      status: $PUBLIC_URL/status"
echo
info "Helm releases in $NAMESPACE"
helm -n "$NAMESPACE" list 2>/dev/null | sed 's/^/    /'
echo
info "Routes on the gateway (VirtualServices, all namespaces)"
kubectl get virtualservices -A -o json 2>/dev/null | python3 -c '
import sys, json
for vs in json.load(sys.stdin)["items"]:
    ns, name = vs["metadata"]["namespace"], vs["metadata"]["name"]
    for h in vs["spec"].get("http", []):
        paths = []
        for m in h.get("match", []):
            u = m.get("uri", {})
            for k, v in u.items(): paths.append(("%s " % k) + v)
        dest = ", ".join(r["destination"]["host"].split(".")[0] + ":" + str(r["destination"].get("port", {}).get("number", "")) for r in h.get("route", [])) or ("redirect " + h.get("redirect", {}).get("uri", ""))
        print("    %-12s %-22s %-40s → %s" % (ns, name, "; ".join(paths)[:40], dest))'
echo
info "Keycloak realm $REALM"
if kubectl -n "$NAMESPACE" get deploy keycloak >/dev/null 2>&1; then
  echo "    users:   $(kcadm get users -r "$REALM" --fields username --format csv --noquotes 2>/dev/null | tr '\n' ' ')"
  echo "    groups:  $(kcadm get groups -r "$REALM" --fields name --format csv --noquotes 2>/dev/null | tr '\n' ' ')"
  echo "    roles:   $(kcadm get roles -r "$REALM" --fields name --format csv --noquotes 2>/dev/null | grep -v '^\(default-roles-\|offline_access\|uma_authorization\)' | tr '\n' ' ')"
  echo "    clients: $(kcadm get clients -r "$REALM" --fields clientId --format csv --noquotes 2>/dev/null | grep -v '^\(account\|admin-cli\|broker\|realm-management\|security-admin-console\|account-console\)$' | tr '\n' ' ')"
  echo "    group membership:"
  for g in $(kcadm get groups -r "$REALM" --fields id,name --format csv --noquotes 2>/dev/null); do
    gid="${g%%,*}"; gname="${g#*,}"
    echo "      $gname: $(kcadm get "groups/$gid/members" -r "$REALM" --fields username --format csv --noquotes 2>/dev/null | tr '\n' ' ')"
  done
  echo "    sessions: $(kcadm get "sessions/realm" -r "$REALM" 2>/dev/null | python3 -c 'import sys,json; print(len(json.load(sys.stdin)))' 2>/dev/null || echo '?') active"
else
  echo "    keycloak not installed"
fi
echo
info "Launchpad tiles and live status"
if curl -k -s -m 10 "$PUBLIC_URL/status" 2>/dev/null | python3 -c '
import sys, json
d = json.load(sys.stdin); print("    overall: %s (%s down), checked %s" % (d["overall"], d["down"], d["time"]))
for s in d["services"]: print("    %-24s %-8s http=%-4s %5s ms  %s" % (s["id"], s["state"], s["httpStatus"], s["latencyMs"], s["message"] or ""))'; then :; else echo "    launchpad not reachable at $PUBLIC_URL/status"; fi
[ -d "$SERVICES_D" ] && echo "    extra tiles (services.d): $(find "$SERVICES_D" -name '*.json' -exec basename {} \; 2>/dev/null | tr '\n' ' ')"
echo
info "Workloads in $NAMESPACE"
kubectl -n "$NAMESPACE" get deploy,sts -o custom-columns='KIND:.kind,NAME:.metadata.name,READY:.status.readyReplicas,DESIRED:.spec.replicas,IMAGE:.spec.template.spec.containers[0].image' 2>/dev/null | sed 's/^/    /'
echo
info "Pods (sidecar = 2/2)"
kubectl -n "$NAMESPACE" get pods -o wide 2>/dev/null | sed 's/^/    /'
kubectl top pods -n "$NAMESPACE" 2>/dev/null | sed 's/^/    /' || true
echo
info "Storage"
kubectl -n "$NAMESPACE" get pvc 2>/dev/null | sed 's/^/    /'
echo
info "Secrets file: $(secrets_file)"
echo
info "Recent warnings"
kubectl -n "$NAMESPACE" get events --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -n 15 | sed 's/^/    /'
[ "$PODS" = 1 ] && { echo; info "Pod details"; kubectl -n "$NAMESPACE" describe pods | grep -E '^(Name|Status|Containers|  [a-z-]+:$|    Image:|    State:|    Ready:|    Restart Count:)' | sed 's/^/    /'; }
exit 0
