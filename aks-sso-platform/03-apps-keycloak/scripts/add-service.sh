#!/usr/bin/env bash
# Put another in-cluster service behind Keycloak SSO and add it as a tile on the launchpad.
#
#   scripts/add-service.sh --id project2-site --name "Project 2 site" --path /project2/ \
#                          --upstream http://project2-site.appsteam.svc.cluster.local:8080 --group project2
#
#   --id            short id (release name of the sso-proxy, tile id)         required
#   --name          tile title                                                 default: id
#   --path          public path prefix, with trailing slash (/project2/)       required unless --no-proxy
#   --upstream      in-cluster URL oauth2-proxy forwards to                    required unless --no-proxy
#   --group G       Keycloak group(s) allowed (repeatable); none = any signed-in user
#   --description   one line for the tile          --icon globe|key|chart|folder|shield|bolt|book|cog|lock
#   --health-url    what the launchpad probes (default: the upstream URL)     --order N (tile order, default 100)
#   --url           tile link (default: {publicUrl}<path>)  --external (open in new tab)
#   --no-proxy      only add the tile (the service does its own auth or is public) — then --url is required
#   --remove        uninstall the sso-proxy release and drop the tile
#   --yes           no prompts
#
# The tile is stored in services.d/<id>.json (commit it); the launchpad is upgraded with the merged list.
source "$(dirname "$0")/lib.sh"
ID=""; NAME=""; SVC_PATH=""; UPSTREAM=""; GROUPS_LIST=(); DESC=""; ICON="globe"; HEALTH=""; ORDER=100; URL=""; EXTERNAL=false; NOPROXY=0; REMOVE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --id) ID="$2"; shift ;; --name) NAME="$2"; shift ;; --path) SVC_PATH="$2"; shift ;; --upstream) UPSTREAM="$2"; shift ;;
    --group) GROUPS_LIST+=("$2"); shift ;; --description) DESC="$2"; shift ;; --icon) ICON="$2"; shift ;; --health-url) HEALTH="$2"; shift ;;
    --order) ORDER="$2"; shift ;; --url) URL="$2"; shift ;; --external) EXTERNAL=true ;; --no-proxy) NOPROXY=1 ;; --remove) REMOVE=1 ;;
    --namespace|-n) NAMESPACE="$2"; shift ;; --yes|-y) YES=1 ;; -h|--help) sed -n '2,19p' "$0"; exit 0 ;; *) die "unknown flag $1 (see --help)" ;;
  esac; shift
done
need kubectl helm openssl python3
[[ "$ID" =~ ^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$ ]] || die "--id must be a short DNS-style name (lowercase letters, digits, dashes)"
NAME="${NAME:-$ID}"
confirm_context
mkdir -p "$SERVICES_D"
TILE="$SERVICES_D/$ID.json"

upgrade_launchpad() {
  release_exists "$LP_RELEASE" || { warn "launchpad not installed yet — the tile will appear when scripts/install.sh runs"; return 0; }
  info "refreshing launchpad tiles"
  run helm -n "$NAMESPACE" upgrade "$LP_RELEASE" "$CHARTS/launchpad" --reuse-values --set-json "app.extraServices=$(extra_services_json)" --wait --timeout 5m \
    || warn "helm --wait ended early — continuing"
  wait_rollout "deploy/$LP_RELEASE" 300s || true
}

if [ "$REMOVE" = 1 ]; then
  if release_exists "$ID"; then confirm "uninstall sso-proxy release '$ID' (its Keycloak client is removed too)?"; run helm -n "$NAMESPACE" uninstall "$ID" --wait --timeout 5m; fi
  [ -f "$TILE" ] && { rm -f "$TILE"; ok "removed tile $TILE"; }
  upgrade_launchpad; ok "service $ID removed"; exit 0
fi

PUBLIC_URL="$(detect_public_url)" || die "cannot determine the public URL (is 02-istio-gateway applied?)"
PUBLIC_URL="${PUBLIC_URL%/}"
if [ "$NOPROXY" = 0 ]; then
  [[ "$SVC_PATH" == /*/ ]] || die "--path must start and end with / (e.g. /project2/)"
  [ -n "$UPSTREAM" ] || die "--upstream is required (e.g. http://my-svc.$NAMESPACE.svc.cluster.local:8080)"
  release_exists "$KC_RELEASE" || die "Keycloak release '$KC_RELEASE' not found in $NAMESPACE — run scripts/install.sh first"
  load_secrets
  VAR_CS="SVC_$(echo "$ID" | tr 'a-z-' 'A-Z_')_CLIENT_SECRET"; VAR_CK="SVC_$(echo "$ID" | tr 'a-z-' 'A-Z_')_COOKIE_SECRET"
  secret_get_or_create "$VAR_CS" gen_client_secret; secret_get_or_create "$VAR_CK" gen_cookie_secret
  CS="${!VAR_CS}"; CK="${!VAR_CK}"
  KC_IMAGE="$(release_value "$KC_RELEASE" image)"; KC_IMAGE="${KC_IMAGE:-quay.io/keycloak/keycloak:26.8.2}"
  GROUPS_JSON="$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' ${GROUPS_LIST[@]+"${GROUPS_LIST[@]}"})"
  info "sso-proxy release '$ID': $PUBLIC_URL$SVC_PATH → $UPSTREAM  (groups: ${GROUPS_LIST[*]:-any signed-in user})"
  helm_install "$ID" "$CHARTS/sso-proxy" \
    --set "publicUrl=$PUBLIC_URL" --set "path=$SVC_PATH" --set "upstream=$UPSTREAM" --set "displayName=$NAME" --set "gateway=$GATEWAY" \
    --set-json "allowedGroups=$GROUPS_JSON" \
    --set "keycloak.publicUrl=$PUBLIC_URL$KC_PATH" --set "keycloak.internalUrl=http://keycloak.$NAMESPACE.svc.cluster.local:8080$KC_PATH" \
    --set "keycloak.realm=$REALM" --set "keycloak.image=$KC_IMAGE" \
    --set "client.secret=$CS" --set "oauth2Proxy.cookieSecret=$CK"
  wait_rollout "deploy/$ID-sso" 300s || true
  wait_job "$ID-sso-client-register" 300s || warn "client registration pending: kubectl -n $NAMESPACE logs job/$ID-sso-client-register"
  if [ -z "$URL" ]; then URL="{publicUrl}$SVC_PATH"; fi
  HEALTH="${HEALTH:-$UPSTREAM}"
else
  [ -n "$URL" ] || die "--no-proxy needs --url"
fi

python3 - "$TILE" "$ID" "$NAME" "$DESC" "$URL" "$HEALTH" "$ICON" "$ORDER" "$EXTERNAL" ${GROUPS_LIST[@]+"${GROUPS_LIST[@]}"} <<'EOF'
import json, sys
path, id_, name, desc, url, health, icon, order, external, *groups = sys.argv[1:]
tile = {"id": id_, "name": name, "description": desc, "url": url, "healthUrl": health, "groups": groups,
        "icon": icon, "order": int(order), "external": external == "true"}
json.dump(tile, open(path, "w"), indent=2); open(path, "a").write("\n")
print("tile written: " + path)
EOF
upgrade_launchpad
echo
ok "service ${c_bold}$ID${c_off} ready"
[ "$NOPROXY" = 0 ] && echo "    open:     $PUBLIC_URL$SVC_PATH   (sign-in required${GROUPS_LIST[*]:+; groups: ${GROUPS_LIST[*]}})"
echo "    tile:     $TILE   (commit it; scripts/install.sh re-applies all tiles)"
echo "    remove:   scripts/add-service.sh --id $ID --remove"
