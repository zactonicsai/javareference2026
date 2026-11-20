#!/usr/bin/env bash
# Verify the Istio gateway end to end. Read-only. Exit code = number of failed checks.
#
#   scripts/test.sh [--rg RG --cluster NAME]
source "$(dirname "$0")/lib.sh"
while [ $# -gt 0 ]; do case "$1" in --rg) RESOURCE_GROUP="$2"; shift ;; --cluster) CLUSTER_NAME="$2"; shift ;; --yes|-y) YES=1 ;; -h|--help) sed -n '2,4p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift; done
need az kubectl curl openssl
az_session
resolve_inputs
ensure_kubecontext
[ -d "$DIR/.terraform" ] && command -v terraform >/dev/null 2>&1 || true
ISTIO_NS="$(tf_out istio_namespace)"; ISTIO_NS="${ISTIO_NS:-istio-system}"
INGRESS_NS="$(tf_out ingress_namespace)"; INGRESS_NS="${INGRESS_NS:-istio-ingress}"
GW="$(tf_out gateway)"; GW="${GW:-$INGRESS_NS/public-gateway}"; GW_NAME="${GW#*/}"
RELEASE="$(sed -nE 's/^gateway_release_name *= *"([^"]+)".*/\1/p' "$DIR/terraform.tfvars" 2>/dev/null | head -1)"; RELEASE="${RELEASE:-istio-ingress}"
EXPECTED_IP="$(tf_out public_ip)"; FQDN="$(tf_out public_fqdn)"
PASS=0; FAIL=0
check() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; PASS=$((PASS+1)); else fail "$d"; FAIL=$((FAIL+1)); fi; }
section() { echo; info "$*"; }

section "Control plane ($ISTIO_NS)"
check "istiod deployment ready" bash -c "[ \"\$(kubectl -n $ISTIO_NS get deploy istiod -o jsonpath='{.status.readyReplicas}')\" -ge 1 ]"
check "Istio CRDs installed (Gateway, VirtualService, PeerAuthentication)" bash -c "kubectl get crd gateways.networking.istio.io virtualservices.networking.istio.io peerauthentications.security.istio.io"
echo "    istiod image: $(kubectl -n "$ISTIO_NS" get deploy istiod -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
check "mesh PeerAuthentication 'default' present" kubectl -n "$ISTIO_NS" get peerauthentication default
echo "    mTLS mode: $(kubectl -n "$ISTIO_NS" get peerauthentication default -o jsonpath='{.spec.mtls.mode}' 2>/dev/null)"
check "sidecar injector webhook registered" bash -c "kubectl get mutatingwebhookconfiguration | grep -q istio-sidecar-injector"

section "Ingress gateway ($INGRESS_NS)"
check "gateway pods ready" bash -c "[ \"\$(kubectl -n $INGRESS_NS get deploy $RELEASE -o jsonpath='{.status.readyReplicas}')\" -ge 1 ]"
LB_IP="$(kubectl -n "$INGRESS_NS" get svc "$RELEASE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
check "Service has an external IP ($LB_IP)" [ -n "$LB_IP" ]
if [ -n "$EXPECTED_IP" ]; then check "external IP equals the provisioned public IP ($EXPECTED_IP)" [ "$LB_IP" = "$EXPECTED_IP" ]; else EXPECTED_IP="$LB_IP"; fi
check "externalTrafficPolicy Local (client IPs preserved)" bash -c "[ \"\$(kubectl -n $INGRESS_NS get svc $RELEASE -o jsonpath='{.spec.externalTrafficPolicy}')\" = Local ]"
echo "    allowed sources: $(kubectl -n "$INGRESS_NS" get svc "$RELEASE" -o jsonpath='{.spec.loadBalancerSourceRanges}' 2>/dev/null)"
check "Gateway $GW exists" kubectl -n "$INGRESS_NS" get gateway "$GW_NAME"
check "Gateway references TLS secret gateway-tls" bash -c "kubectl -n $INGRESS_NS get gateway $GW_NAME -o jsonpath='{.spec.servers[*].tls.credentialName}' | grep -q gateway-tls"
check "TLS secret exists" kubectl -n "$INGRESS_NS" get secret gateway-tls
check "HPA for the gateway" kubectl -n "$INGRESS_NS" get hpa "$RELEASE"

section "From here through the Internet (${FQDN:-$EXPECTED_IP})"
HOST="${FQDN:-$EXPECTED_IP}"
H_CODE="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://$HOST/" 2>/dev/null || echo 000)"
REDIR="$(kubectl -n "$INGRESS_NS" get gateway "$GW_NAME" -o jsonpath='{.spec.servers[0].tls.httpsRedirect}' 2>/dev/null)"
if [ "$REDIR" = true ]; then check "HTTP redirects to HTTPS (got $H_CODE)" [ "$H_CODE" = 301 ]; else check "HTTP answers (got $H_CODE)" [ "$H_CODE" != 000 ]; fi
S_CODE="$(curl -k -s -m 10 -o /dev/null -w '%{http_code}' "https://$HOST/" 2>/dev/null || echo 000)"
check "HTTPS answers (got $S_CODE; 404 is right until an app publishes a VirtualService)" bash -c "[ '$S_CODE' != 000 ]"
check "response comes from istio-envoy" bash -c "curl -k -s -m 10 -D - -o /dev/null https://$HOST/ | grep -qi 'server: istio-envoy'"
CERT="$(echo | openssl s_client -connect "$HOST:443" -servername "$HOST" 2>/dev/null | openssl x509 -noout -subject -ext subjectAltName -enddate 2>/dev/null || true)"
check "TLS handshake works" [ -n "$CERT" ]
echo "$CERT" | sed 's/^/      /'
check "certificate SAN covers $HOST" bash -c "echo '$CERT' | grep -q '$HOST'"
if [ -f "$DIR/gateway.crt" ]; then
  check "served certificate == ./gateway.crt" bash -c "diff <(echo | openssl s_client -connect $HOST:443 -servername $HOST 2>/dev/null | openssl x509 -fingerprint -sha256 -noout) <(openssl x509 -in '$DIR/gateway.crt' -fingerprint -sha256 -noout)"
  check "browser-trusted here (cert in OS trust store)? " bash -c "curl -s -m 10 -o /dev/null https://$HOST/ || curl -s -m 10 --cacert '$DIR/gateway.crt' -o /dev/null https://$HOST/"
fi
[ "$S_CODE" = 000 ] && echo "    hint: NSG/loadBalancerSourceRanges may not include your IP — 01-infra/scripts/allow-ip.sh --me"

section "Monitoring"
if kubectl get crd podmonitors.azmonitoring.coreos.com >/dev/null 2>&1; then
  check "PodMonitor istiod" kubectl -n "$ISTIO_NS" get podmonitor istiod
  check "PodMonitor envoy-stats" kubectl -n "$ISTIO_NS" get podmonitor envoy-stats
else
  warn "Managed Prometheus CRDs not present (cluster without the metrics add-on) — PodMonitors skipped"
fi
check "gateway access logs flowing (JSON on stdout)" bash -c "kubectl -n $INGRESS_NS logs deploy/$RELEASE --tail=50 | grep -q '\"authority\"\|\"method\"' || [ \"$S_CODE\" = 000 ]"
if command -v istioctl >/dev/null 2>&1; then
  echo; info "istioctl analyze"; istioctl analyze -A 2>&1 | tail -n 5
fi

echo
if [ "$FAIL" = 0 ]; then ok "${c_bold}$PASS checks passed${c_off} — the gateway is ready for 03-apps-keycloak"; else fail "${c_bold}$FAIL failed, $PASS passed${c_off}"; fi
exit "$FAIL"
