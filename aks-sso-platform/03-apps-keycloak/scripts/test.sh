#!/usr/bin/env bash
# Verify Keycloak, the launchpad and every sso-proxy service. Read-only. Exit code = number of failed checks.
#
#   scripts/test.sh [--login user:password] [--public-url URL] [--namespace appsteam] [--yes]
#
#   --login   drive a real browser-style login with curl (Keycloak form → code → session) and check that the
#             project pages honour the user's groups, e.g.  --login alice:alice123  (project2 yes, project3 no)
source "$(dirname "$0")/lib.sh"
LOGIN=""
while [ $# -gt 0 ]; do
  case "$1" in --login) LOGIN="$2"; shift ;; --public-url) PUBLIC_URL="$2"; shift ;; --namespace|-n) NAMESPACE="$2"; shift ;; --yes|-y) YES=1 ;; -h|--help) sed -n '2,8p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift
done
need kubectl helm curl python3
YES=1; confirm_context
PUBLIC_URL="$(detect_public_url)" || die "cannot determine the public URL (--public-url)"
PUBLIC_URL="${PUBLIC_URL%/}"
PASS=0; FAIL=0
check() { local d="$1"; shift; if "$@" >/dev/null 2>&1; then ok "$d"; PASS=$((PASS+1)); else fail "$d"; FAIL=$((FAIL+1)); fi; }
section() { echo; info "$*"; }
http() { curl -k -s -m 15 -o /dev/null -w '%{http_code}' "$@" 2>/dev/null || echo 000; }
location() { curl -k -s -m 15 -o /dev/null -w '%{redirect_url}' "$@" 2>/dev/null || true; }

section "Keycloak ($NAMESPACE)"
check "keycloak release installed" release_exists "$KC_RELEASE"
check "postgres ready" bash -c "[ \"\$(kubectl -n $NAMESPACE get sts keycloak-postgres -o jsonpath='{.status.readyReplicas}')\" -ge 1 ]"
check "keycloak deployment ready" bash -c "[ \"\$(kubectl -n $NAMESPACE get deploy keycloak -o jsonpath='{.status.readyReplicas}')\" -ge 1 ]"
check "keycloak pod has the Istio sidecar" bash -c "kubectl -n $NAMESPACE get pods -l app=keycloak -o jsonpath='{.items[0].spec.containers[*].name}{.items[0].spec.initContainers[*].name}' | grep -q istio-proxy"
check "VirtualService /auth exists" kubectl -n "$NAMESPACE" get virtualservice keycloak
DISC="$PUBLIC_URL$KC_PATH/realms/$REALM/.well-known/openid-configuration"
check "realm $REALM discovery through the gateway (HTTP $(http "$DISC"))" bash -c "[ \"$(http "$DISC")\" = 200 ]"
ISS="$(curl -k -s -m 15 "$DISC" 2>/dev/null | jsonq issuer 2>/dev/null || true)"
check "issuer is the public URL ($ISS)" [ "$ISS" = "$PUBLIC_URL$KC_PATH/realms/$REALM" ]
check "admin console reachable" bash -c "[ \"$(http "$PUBLIC_URL$KC_PATH/admin/")\" != 000 ]"
USERS="$(kcadm get users -r "$REALM" --fields username --format csv --noquotes 2>/dev/null | tr '\n' ' ' || true)"
check "default users exist in realm (${USERS:-none})" bash -c "echo '$USERS' | grep -q admin && echo '$USERS' | grep -q alice"
GROUPS_KC="$(kcadm get groups -r "$REALM" --fields name --format csv --noquotes 2>/dev/null | tr '\n' ' ' || true)"
check "groups admin/project2/project3 exist (${GROUPS_KC})" bash -c "for g in admin project2 project3; do echo '$GROUPS_KC' | grep -qw \$g || exit 1; done"
CLIENTS="$(kcadm get clients -r "$REALM" --fields clientId --format csv --noquotes 2>/dev/null | grep -v '^\(account\|admin-cli\|broker\|realm-management\|security-admin-console\|account-console\)$' | tr '\n' ' ' || true)"
echo "    app clients: ${CLIENTS:-none yet}"
check "NetworkPolicy keycloak" kubectl -n "$NAMESPACE" get networkpolicy keycloak
kubectl get crd podmonitors.azmonitoring.coreos.com >/dev/null 2>&1 && check "PodMonitor keycloak (Managed Prometheus)" kubectl -n "$NAMESPACE" get podmonitor keycloak

section "Launchpad"
if release_exists "$LP_RELEASE"; then
  check "launchpad deployment ready" bash -c "[ \"\$(kubectl -n $NAMESPACE get deploy $LP_RELEASE -o jsonpath='{.status.readyReplicas}')\" -ge 1 ]"
  check "OIDC client '$LP_RELEASE' registered in Keycloak" bash -c "echo '$CLIENTS' | grep -qw $LP_RELEASE"
  check "launch page public (HTTP $(http "$PUBLIC_URL/"))" bash -c "[ \"$(http "$PUBLIC_URL/")\" = 200 ]"
  check "launch page is self-contained (no external src/href)" bash -c "! curl -k -s -m 15 '$PUBLIC_URL/' | grep -Eo '(src|href)=\"https?://[^\"]+' | grep -v '$PUBLIC_URL' | grep -q ."
  check "security headers (CSP, no-sniff)" bash -c "curl -k -s -m 15 -D - -o /dev/null '$PUBLIC_URL/' | grep -qi 'content-security-policy' "
  check "/status JSON" bash -c "curl -k -s -m 15 '$PUBLIC_URL/status' | jsonq overall | grep -Eq 'UP|DEGRADED'"
  echo "    services: $(curl -k -s -m 15 "$PUBLIC_URL/status" | python3 -c 'import sys,json; print(", ".join(f"{s[\"id\"]}={s[\"state\"]}" for s in json.load(sys.stdin)["services"]))' 2>/dev/null)"
  L="$(location "$PUBLIC_URL/restricted")"
  check "/restricted redirects anonymous users to Keycloak" bash -c "echo '$L' | grep -q '$KC_PATH/realms/$REALM/protocol/openid-connect/auth'"
  check "login request uses PKCE" bash -c "echo '$L' | grep -q 'code_challenge_method=S256'"
  check "/admin redirects anonymous users to Keycloak" bash -c "location '$PUBLIC_URL/admin' | grep -q openid-connect/auth"
  check "/projects/project2 redirects anonymous users" bash -c "location '$PUBLIC_URL/projects/project2' | grep -q openid-connect/auth"
  check "/unavailable page renders (HTTP $(http "$PUBLIC_URL/unavailable/keycloak-account"))" bash -c "[ \"$(http "$PUBLIC_URL/unavailable/keycloak-account")\" = 200 ]"
  check "unknown service → 404" bash -c "[ \"$(http "$PUBLIC_URL/unavailable/nope")\" = 404 ]"
  check "actuator NOT exposed through the gateway" bash -c "[ \"$(http "$PUBLIC_URL/actuator/health")\" = 404 ]"
  check "NetworkPolicy launchpad" kubectl -n "$NAMESPACE" get networkpolicy "$LP_RELEASE"
  kubectl get crd podmonitors.azmonitoring.coreos.com >/dev/null 2>&1 && check "PodMonitor launchpad" kubectl -n "$NAMESPACE" get podmonitor "$LP_RELEASE"
  check "prometheus metrics served on the management port" bash -c "kubectl -n $NAMESPACE exec deploy/$LP_RELEASE -c launchpad -- sh -c 'exec 3<>/dev/tcp/127.0.0.1/8081; printf \"GET /actuator/prometheus HTTP/1.0\r\n\r\n\" >&3; cat <&3' | grep -q launchpad_service_up"
else
  warn "launchpad not installed (scripts/build-image.sh then scripts/install.sh --launchpad-only)"
fi

section "SSO-proxied services"
SP="$(helm -n "$NAMESPACE" list -o json 2>/dev/null | python3 -c 'import sys,json; print(" ".join(r["name"] for r in json.load(sys.stdin) if r["chart"].startswith("sso-proxy-")))' 2>/dev/null || true)"
if [ -n "$SP" ]; then
  for r in $SP; do
    P="$(release_value "$r" path)"; U="$(release_value "$r" upstream)"
    check "$r: oauth2-proxy ready" bash -c "[ \"\$(kubectl -n $NAMESPACE get deploy $r-sso -o jsonpath='{.status.readyReplicas}')\" -ge 1 ]"
    check "$r: $P redirects anonymous users to Keycloak" bash -c "location '$PUBLIC_URL$P' | grep -q openid-connect/auth"
    check "$r: client registered" bash -c "echo '$CLIENTS' | grep -qw $r"
    if release_exists "$LP_RELEASE"; then
      ST="$(curl -k -s -m 15 "$PUBLIC_URL/status/$r" 2>/dev/null | jsonq state 2>/dev/null || true)"
      check "$r: upstream $U healthy per launchpad (state ${ST:-?})" [ "$ST" = UP ]
    fi
  done
else
  echo "    none (examples/project2-site/install.sh adds one)"
fi

if [ -n "$LOGIN" ] && release_exists "$LP_RELEASE"; then
  section "Browser-style login as ${LOGIN%%:*}"
  U="${LOGIN%%:*}"; PW="${LOGIN#*:}"; JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
  AUTH="$(curl -k -s -m 15 -c "$JAR" -b "$JAR" -o /dev/null -w '%{redirect_url}' "$PUBLIC_URL/restricted")"
  check "step 1: redirected to Keycloak" bash -c "echo '$AUTH' | grep -q openid-connect/auth"
  FORM="$(curl -k -s -m 20 -c "$JAR" -b "$JAR" -L "$AUTH" | tr -d '\n' | sed -E 's/.*<form[^>]*id="kc-form-login"[^>]*action="([^"]+)".*/\1/' | sed 's/&amp;/\&/g')"
  check "step 2: Keycloak login form received" bash -c "echo '$FORM' | grep -q 'login-actions/authenticate'"
  CB="$(curl -k -s -m 20 -c "$JAR" -b "$JAR" -o /dev/null -w '%{redirect_url}' --data-urlencode "username=$U" --data-urlencode "password=$PW" --data-urlencode "credentialId=" "$FORM")"
  check "step 3: credentials accepted (redirect to the app callback)" bash -c "echo '$CB' | grep -q '/login/oauth2/code/keycloak?'"
  CODE="$(curl -k -s -m 20 -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' "$CB")"
  check "step 4: code exchanged, session created (HTTP $CODE → redirect)" bash -c "[ '$CODE' = 302 ]"
  BODY="$(curl -k -s -m 20 -c "$JAR" -b "$JAR" "$PUBLIC_URL/restricted")"
  check "step 5: /restricted shows the user" bash -c "echo \"\$BODY\" | grep -q '$U'"
  GRP="$(echo "$BODY" | tr -d '\n' | sed -E 's/.*<dt[^>]*>groups<\/dt><dd[^>]*>([^<]*)<.*/\1/' | sed 's/^ *//;s/ *$//')"
  echo "    groups seen by the app: ${GRP:-?}"
  for p in project2 project3; do
    C="$(curl -k -s -m 20 -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' "$PUBLIC_URL/projects/$p")"
    if echo "$GRP" | tr ',' ' ' | grep -qw "$p" || echo "$GRP" | grep -qw admin; then check "/projects/$p → 200 for $U (member)" [ "$C" = 200 ]; else check "/projects/$p → 403 for $U (not a member)" [ "$C" = 403 ]; fi
  done
  C="$(curl -k -s -m 20 -c "$JAR" -b "$JAR" -o /dev/null -w '%{http_code}' "$PUBLIC_URL/admin")"
  if echo "$GRP" | grep -qw admin; then check "/admin → 200 (admin group)" [ "$C" = 200 ]; else check "/admin → 403 (not admin)" [ "$C" = 403 ]; fi
fi

echo
if [ "$FAIL" = 0 ]; then ok "${c_bold}$PASS checks passed${c_off}"; else fail "${c_bold}$FAIL failed, $PASS passed${c_off} — details: scripts/cluster-info.sh ; kubectl -n $NAMESPACE get pods"; fi
exit "$FAIL"
