# shellcheck shell=bash
# Shared helpers for 03-apps-keycloak scripts. Source it; do not run it. bash 3.2 (macOS) compatible.
set -eo pipefail
if [ "${BASH_VERSINFO[0]}" -gt 4 ] || { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -ge 4 ]; }; then set -u; fi

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"          # 03-apps-keycloak
PLATFORM_ROOT="$(cd "$DIR/.." && pwd)"
HANDOFF_DIR="$PLATFORM_ROOT/.platform"
INFRA_ENV="$HANDOFF_DIR/infra.env"; GATEWAY_ENV="$HANDOFF_DIR/gateway.env"; IMAGE_ENV="$HANDOFF_DIR/launchpad-image.env"
CHARTS="$DIR/charts"; SERVICES_D="$DIR/services.d"
NAMESPACE="${NAMESPACE:-appsteam}"
KC_RELEASE="${KC_RELEASE:-keycloak}"; LP_RELEASE="${LP_RELEASE:-launchpad}"
GATEWAY="${GATEWAY:-istio-ingress/public-gateway}"
INGRESS_NS="${GATEWAY%/*}"; GATEWAY_NAME="${GATEWAY#*/}"
KC_PATH="${KC_PATH:-/auth}"; REALM="${REALM:-appsteam}"
YES="${YES:-0}"

c_bold=$'\e[1m'; c_dim=$'\e[2m'; c_ok=$'\e[32m'; c_warn=$'\e[33m'; c_err=$'\e[31m'; c_off=$'\e[0m'
info() { echo "${c_bold}==>${c_off} $*"; }
ok()   { echo "${c_ok}  ✓${c_off} $*"; }
warn() { echo "${c_warn}  !${c_off} $*"; }
fail() { echo "${c_err}  ✗${c_off} $*"; }
die()  { echo "${c_err}error:${c_off} $*" >&2; exit 1; }
run()  { echo "${c_dim}  \$ $*${c_off}"; "$@"; }
need() { for b in "$@"; do command -v "$b" >/dev/null 2>&1 || die "'$b' is required but not on PATH"; done; }
confirm() { [ "$YES" = 1 ] && return 0; [ -t 0 ] || die "non-interactive shell: re-run with --yes"; read -r -p "    $1 [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || die "aborted"; }
jsonq() { python3 -c 'import sys,json
d=json.load(sys.stdin)
for k in sys.argv[1].split("."):
    d=d.get(k) if isinstance(d,dict) else None
print("" if d is None else (json.dumps(d) if isinstance(d,(dict,list)) else d))' "$1"; }

# ---- cluster -----------------------------------------------------------------------------------------------------
current_context() { kubectl config current-context 2>/dev/null || die "no current kubectl context (01-infra: az aks get-credentials …)"; }
confirm_context() {
  need kubectl helm
  local ctx server; ctx="$(current_context)"
  server="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null || true)"
  info "kubectl context: ${c_bold}${ctx}${c_off}  ${c_dim}${server}${c_off}   namespace: ${c_bold}${NAMESPACE}${c_off}"
  kubectl get --raw=/readyz >/dev/null 2>&1 || die "cannot reach the cluster API (stopped? wrong context? authorized IP ranges?)"
  confirm "continue on this cluster?"
}
helm_ver_check() { local v; v="$(helm version --short 2>/dev/null | sed -E 's/^v?([0-9]+)\.([0-9]+).*/\1.\2/')"; [ -n "$v" ] || die "helm not working"; python3 -c "import sys; a,b=map(int,'$v'.split('.')); sys.exit(0 if (a,b)>=(3,10) else 1)" || die "helm >= 3.10 required (for --set-json); found $v"; }

# The gateway from 02-istio-gateway must exist; apps publish VirtualServices against it.
require_gateway() {
  kubectl get crd virtualservices.networking.istio.io >/dev/null 2>&1 || die "Istio is not installed on this cluster — run 02-istio-gateway/scripts/apply.sh first"
  kubectl -n "$INGRESS_NS" get gateway "$GATEWAY_NAME" >/dev/null 2>&1 || die "Gateway $GATEWAY not found — run 02-istio-gateway/scripts/apply.sh (or set GATEWAY=ns/name)"
  ok "gateway $GATEWAY present"
}

# Public base URL: flag/env → ../.platform/gateway.env → Gateway annotation → cert SAN → LB IP
detect_public_url() {
  [ -n "${PUBLIC_URL:-}" ] && { echo "$PUBLIC_URL"; return; }
  if [ -f "$GATEWAY_ENV" ]; then local u; u="$(sed -n 's/^PUBLIC_URL=//p' "$GATEWAY_ENV" | head -1)"; [ -n "$u" ] && { echo "$u"; return; }; fi
  local u; u="$(kubectl -n "$INGRESS_NS" get gateway "$GATEWAY_NAME" -o jsonpath='{.metadata.annotations.platform/public-url}' 2>/dev/null || true)"
  [ -n "$u" ] && [ "$u" != "https://CHANGE-ME" ] && { echo "$u"; return; }
  if command -v openssl >/dev/null 2>&1; then
    local san; san="$(kubectl -n "$INGRESS_NS" get secret gateway-tls -o jsonpath='{.data.tls\.crt}' 2>/dev/null | base64 -d 2>/dev/null | openssl x509 -noout -ext subjectAltName 2>/dev/null | tr ',' '\n' | sed -n 's/ *DNS:\([^*][^ ]*\).*/\1/p' | head -1 || true)"
    [ -n "$san" ] && { echo "https://$san"; return; }
  fi
  local ip; ip="$(kubectl -n "$INGRESS_NS" get svc -l istio=ingress -o jsonpath='{.items[0].status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"
  [ -n "$ip" ] && { echo "https://$ip"; return; }
  return 1
}

# ---- secrets: generated once per cluster+namespace, kept in a git-ignored file -----------------------------------
secrets_file() { echo "$HANDOFF_DIR/secrets/$(current_context)-$NAMESPACE.env"; }
load_secrets() { SECRETS="$(secrets_file)"; mkdir -p "$(dirname "$SECRETS")"; touch "$SECRETS"; chmod 600 "$SECRETS"; set +u 2>/dev/null; source "$SECRETS"; return 0; }
secret_get_or_create() {   # $1 var name  $2 generator command
  local cur; cur="$(sed -n "s/^$1=//p" "$SECRETS" | head -1)"
  if [ -z "$cur" ]; then cur="$(eval "$2")"; echo "$1=$cur" >> "$SECRETS"; fi
  printf -v "$1" '%s' "$cur"
}
gen_password()      { openssl rand -base64 18 | tr -d '/+=' | cut -c1-20; }
gen_client_secret() { openssl rand -hex 24; }
gen_cookie_secret() { openssl rand -base64 32 | tr -d '\n' | tr -- '+/' '-_'; }   # 32 bytes, URL-safe (oauth2-proxy)

# ---- helm helpers --------------------------------------------------------------------------------------------------
release_exists() { helm -n "$NAMESPACE" status "$1" >/dev/null 2>&1; }
release_value()  { helm -n "$NAMESPACE" get values "$1" -a -o json 2>/dev/null | jsonq "$2"; }
helm_install() {   # $1 release $2 chart dir $3.. args ; tolerates --wait connection drops
  local rel="$1" chart="$2"; shift 2
  run helm upgrade --install "$rel" "$chart" -n "$NAMESPACE" --create-namespace "$@" --wait --timeout 10m \
    || warn "helm --wait ended early (API connection drop or timeout) — continuing with explicit rollout checks"
}
wait_rollout() { run kubectl -n "$NAMESPACE" rollout status "$1" --timeout="${2:-300s}"; }
wait_job() {   # $1 job name
  if kubectl -n "$NAMESPACE" wait --for=condition=complete "job/$1" --timeout="${2:-300s}" >/dev/null 2>&1; then ok "job $1 complete"; return 0; fi
  warn "job $1 not complete — last log lines:"; kubectl -n "$NAMESPACE" logs "job/$1" --tail=15 2>/dev/null | sed 's/^/      /' || true; return 1
}

# ---- launchpad tile list: services.d/*.json → JSON array for --set-json app.extraServices -------------------------
extra_services_json() {
  python3 - "$SERVICES_D" <<'EOF'
import glob, json, os, sys
d = sys.argv[1]; out = []
for f in sorted(glob.glob(os.path.join(d, "*.json"))):
    with open(f) as fh:
        try: out.append(json.load(fh))
        except Exception as e: print(f"bad JSON in {f}: {e}", file=sys.stderr); sys.exit(1)
print(json.dumps(out))
EOF
}

# keycloak admin CLI inside the running pod (no port-forward needed)
kcadm() { kubectl -n "$NAMESPACE" exec deploy/keycloak -c keycloak -- bash -c 'K="/opt/keycloak/bin/kcadm.sh --config /tmp/kcadm.config"; $K config credentials --server http://localhost:8080'"$KC_PATH"' --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 && $K "$@"' kcadm "$@"; }
