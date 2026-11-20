#!/usr/bin/env bash
# Install Keycloak (+ Postgres, realm appsteam) and the Spring Boot launchpad behind the Istio gateway.
#
#   scripts/install.sh [--public-url https://host] [--image REPO:TAG] [--namespace appsteam]
#                      [--keycloak-only] [--launchpad-only] [--yes] [--set chart.key=value]…
#
#   --public-url      browser-facing base URL; default: what 02-istio-gateway recorded (or the gateway cert's name)
#   --image           launchpad image; default: <ACR from 01-infra>/launchpad:<tag from scripts/build-image.sh>
#   --keycloak-only   stop after Keycloak (install the launchpad later with --launchpad-only)
#   --set k=v         extra Helm values; prefix with the chart: keycloak.realm.users[0].password=… / launchpad.replicas=2
#   --values-keycloak FILE / --values-launchpad FILE   extra values files
#
# Idempotent: re-run after changing values. Generated passwords/secrets live in ../.platform/secrets/ (git-ignored)
# and are reused on every run, so Keycloak's database and the OIDC clients stay consistent.
source "$(dirname "$0")/lib.sh"
IMAGE="${IMAGE:-}"; DO_KC=1; DO_LP=1; KC_VALUES=(); LP_VALUES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --public-url) PUBLIC_URL="$2"; shift ;; --image) IMAGE="$2"; shift ;; --namespace|-n) NAMESPACE="$2"; shift ;;
    --keycloak-only) DO_LP=0 ;; --launchpad-only) DO_KC=0 ;; --yes|-y) YES=1 ;;
    --set) case "$2" in keycloak.*) KC_VALUES+=(--set "${2#keycloak.}") ;; launchpad.*) LP_VALUES+=(--set "${2#launchpad.}") ;; *) die "--set needs a chart prefix: keycloak.… or launchpad.…" ;; esac; shift ;;
    --values-keycloak) KC_VALUES+=(-f "$2"); shift ;; --values-launchpad) LP_VALUES+=(-f "$2"); shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) die "unknown flag $1 (see --help)" ;;
  esac; shift
done
need kubectl helm openssl curl python3
helm_ver_check
confirm_context
require_gateway
PUBLIC_URL="$(detect_public_url)" || die "cannot determine the public URL — pass --public-url https://<fqdn> (02-istio-gateway: terraform output public_url)"
PUBLIC_URL="${PUBLIC_URL%/}"
ok "public URL: ${c_bold}$PUBLIC_URL${c_off}"
load_secrets
secret_get_or_create KC_ADMIN_PASSWORD gen_password
secret_get_or_create PG_PASSWORD gen_password
secret_get_or_create LAUNCHPAD_CLIENT_SECRET gen_client_secret
ok "secrets: $SECRETS"

info "namespace $NAMESPACE (sidecar injection on)"
kubectl get ns "$NAMESPACE" >/dev/null 2>&1 || run kubectl create ns "$NAMESPACE" >/dev/null
run kubectl label ns "$NAMESPACE" istio-injection=enabled app.kubernetes.io/part-of=aks-sso-platform --overwrite >/dev/null

# ---- Keycloak ----------------------------------------------------------------------------------------------------
if [ "$DO_KC" = 1 ]; then
  info "Keycloak + PostgreSQL (charts/keycloak) — first start takes 2-4 minutes"
  helm_install "$KC_RELEASE" "$CHARTS/keycloak" \
    --set "publicUrl=$PUBLIC_URL" --set "relativePath=$KC_PATH" --set "gateway=$GATEWAY" --set "realm.name=$REALM" \
    --set "admin.password=$KC_ADMIN_PASSWORD" --set "postgres.password=$PG_PASSWORD" ${KC_VALUES[@]+"${KC_VALUES[@]}"}
  wait_rollout statefulset/keycloak-postgres 300s
  wait_rollout deploy/keycloak 600s
  info "realm discovery through the gateway"
  DISC="$PUBLIC_URL$KC_PATH/realms/$REALM/.well-known/openid-configuration"
  for i in $(seq 1 24); do
    CODE="$(curl -k -s -m 10 -o /dev/null -w '%{http_code}' "$DISC" || true)"; [ "$CODE" = 200 ] && break; sleep 5
  done
  if [ "$CODE" = 200 ]; then ok "$DISC"; else warn "realm discovery returned HTTP ${CODE:-000} from here (NSG/allowed IPs? gateway?) — in-cluster access may still work"; fi
else
  release_exists "$KC_RELEASE" || die "Keycloak release '$KC_RELEASE' not found in $NAMESPACE — run without --launchpad-only first"
  ok "Keycloak release present"
fi

# ---- Launchpad ---------------------------------------------------------------------------------------------------
if [ "$DO_LP" = 1 ]; then
  if [ -z "$IMAGE" ] && [ -f "$IMAGE_ENV" ]; then IMAGE="$(sed -n 's/^IMAGE=//p' "$IMAGE_ENV" | head -1)"; fi
  if [ -z "$IMAGE" ] && [ -f "$INFRA_ENV" ]; then
    ACR="$(sed -n 's/^ACR_LOGIN_SERVER=//p' "$INFRA_ENV" | head -1)"; [ -n "$ACR" ] && IMAGE="$ACR/launchpad:1.0.0"
  fi
  if [ -z "$IMAGE" ]; then
    warn "no launchpad image known — build it first:  scripts/build-image.sh   (then re-run with --launchpad-only)"
    DO_LP=0
  fi
fi
if [ "$DO_LP" = 1 ]; then
  REPO="${IMAGE%:*}"; TAG="${IMAGE##*:}"; [ "$REPO" = "$IMAGE" ] && TAG=latest
  if command -v az >/dev/null 2>&1 && [[ "$REPO" == *.azurecr.io/* ]]; then
    ACR_NAME="${REPO%%.azurecr.io/*}"; REPO_PATH="${REPO#*.azurecr.io/}"
    if az acr repository show-tags -n "$ACR_NAME" --repository "$REPO_PATH" -o tsv 2>/dev/null | grep -qx "$TAG"; then ok "image $IMAGE exists in ACR"
    else warn "tag $TAG not found in $ACR_NAME/$REPO_PATH — build it with scripts/build-image.sh (continuing; pods will ImagePullBackOff until it exists)"; fi
  fi
  KC_IMAGE="$(release_value "$KC_RELEASE" image)"; KC_IMAGE="${KC_IMAGE:-quay.io/keycloak/keycloak:26.8.2}"
  EXTRA="$(extra_services_json)"
  info "Launchpad (charts/launchpad) with image $IMAGE"
  helm_install "$LP_RELEASE" "$CHARTS/launchpad" \
    --set "publicUrl=$PUBLIC_URL" --set "gateway=$GATEWAY" \
    --set "image.repository=$REPO" --set "image.tag=$TAG" \
    --set "keycloak.publicUrl=$PUBLIC_URL$KC_PATH" --set "keycloak.internalUrl=http://keycloak.$NAMESPACE.svc.cluster.local:8080$KC_PATH" \
    --set "keycloak.realm=$REALM" --set "keycloak.image=$KC_IMAGE" \
    --set "client.secret=$LAUNCHPAD_CLIENT_SECRET" \
    --set-json "app.extraServices=$EXTRA" ${LP_VALUES[@]+"${LP_VALUES[@]}"}
  wait_rollout "deploy/$LP_RELEASE" 420s || true
  wait_job "$LP_RELEASE-client-register" 300s || warn "OIDC client registration pending — check: kubectl -n $NAMESPACE logs job/$LP_RELEASE-client-register"
  CODE="$(curl -k -s -m 10 -o /dev/null -w '%{http_code}' "$PUBLIC_URL/" || true)"
  [ "$CODE" = 200 ] && ok "launch page answers: $PUBLIC_URL/" || warn "launch page HTTP ${CODE:-000} from here (give the pod a minute; scripts/test.sh re-checks)"
fi

echo
info "03-apps-keycloak installed in $NAMESPACE"
echo "    Keycloak admin:   ${c_bold}$PUBLIC_URL$KC_PATH/admin/${c_off}   user admin / $KC_ADMIN_PASSWORD   (master realm; stored in $SECRETS)"
echo "    realm $REALM users (defaults, see charts/keycloak/values.yaml):"
echo "        admin / admin123 (group admin)   alice / alice123 (project2)   bob / bob123 (project3)   carol / carol123 (project2+project3)   viewer / viewer123 (no group)"
[ "$DO_LP" = 1 ] && echo "    launchpad:        ${c_bold}$PUBLIC_URL/${c_off}   restricted: $PUBLIC_URL/restricted   projects: $PUBLIC_URL/projects/project2   admin: $PUBLIC_URL/admin"
echo "    browser warning:  self-signed certificate — 02-istio-gateway/scripts/trust-cert.sh removes it"
echo "    verify:           scripts/test.sh --login alice:alice123        details: scripts/cluster-info.sh"
echo "    add a service:    scripts/add-service.sh --help      example: examples/project2-site/install.sh"
