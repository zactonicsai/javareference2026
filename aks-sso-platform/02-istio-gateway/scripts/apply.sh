#!/usr/bin/env bash
# Install Istio (base, istiod, ingress gateway) with Terraform + Helm, bind the gateway to the public IP and
# terminate TLS with a self-signed certificate. Works on any AKS cluster.
#
#   scripts/apply.sh [--rg RG --cluster NAME] [--pip NAME [--pip-rg RG]] [--allow CIDR[,CIDR]] [--yes] [--plan-only] [--upgrade]
#
#   no flags            → uses ../.platform/infra.env from 01-infra (or an earlier generated.auto.tfvars)
#   --pip               → existing Standard public IP (default: 01-infra's); omit on a foreign cluster to create one
#   --allow             → client CIDRs for the load balancer (default: 01-infra's list, else 0.0.0.0/0)
#
# Takes ~5 minutes. Re-runnable: upgrades in place; the certificate is reissued only when near expiry or SANs change.
source "$(dirname "$0")/lib.sh"
PLAN_ONLY=0; UPGRADE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --rg) RESOURCE_GROUP="$2"; shift ;; --cluster) CLUSTER_NAME="$2"; shift ;;
    --pip) PUBLIC_IP_NAME="$2"; shift ;; --pip-rg) PUBLIC_IP_RESOURCE_GROUP="$2"; shift ;;
    --allow) ALLOWED_SOURCE_CIDRS="$2"; shift ;;
    --yes|-y) YES=1 ;; --plan-only) PLAN_ONLY=1 ;; --upgrade) UPGRADE=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) die "unknown flag $1 (see --help)" ;;
  esac; shift
done
export UPGRADE
need az terraform kubectl curl
az_session
resolve_inputs

info "target cluster ${c_bold}$CLUSTER_NAME${c_off} in $RESOURCE_GROUP"
CL_INFO="$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query "{k8s:currentKubernetesVersion,power:powerState.code,state:provisioningState}" -o tsv 2>/dev/null || true)"
read -r CL_K8S CL_POWER CL_STATE <<<"$CL_INFO"
[ -n "${CL_K8S:-}" ] || die "cluster not found (az aks show -g $RESOURCE_GROUP -n $CLUSTER_NAME failed)"
ok "kubernetes $CL_K8S, power $CL_POWER, state $CL_STATE"
[ "$CL_POWER" = Running ] || die "cluster is not running (az aks start -g $RESOURCE_GROUP -n $CLUSTER_NAME)"
ensure_kubecontext
if kubectl -n aks-istio-system get deploy istiod >/dev/null 2>&1; then
  warn "this cluster runs the Azure Istio add-on (aks-istio-system). Installing upstream Istio next to it is unsupported;"
  warn "disable the add-on (az aks mesh disable) or use its ingress instead. Continue only if you know what you are doing."
  confirm "continue anyway?"
fi
if [ -n "${PUBLIC_IP_NAME:-}" ]; then
  PIPRG="${PUBLIC_IP_RESOURCE_GROUP:-$RESOURCE_GROUP}"
  PIP_INFO="$(az network public-ip show -g "$PIPRG" -n "$PUBLIC_IP_NAME" --query "{ip:ipAddress,fqdn:dnsSettings.fqdn,sku:sku.name}" -o tsv 2>/dev/null || true)"
  read -r PIP_IP PIP_FQDN PIP_SKU <<<"$PIP_INFO"
  [ -n "${PIP_IP:-}" ] || die "public IP $PUBLIC_IP_NAME not found in $PIPRG"
  [ "$PIP_SKU" = Standard ] || die "public IP must be Standard SKU (found ${PIP_SKU:-?})"
  ok "public IP $PUBLIC_IP_NAME = $PIP_IP (${PIP_FQDN:-no DNS label})"
else
  warn "no public IP given — Terraform creates one in the node resource group"
fi
write_auto_tfvars
tf_init

info "terraform validate"; tf validate >/dev/null && ok "configuration valid"
info "terraform plan"
: > "$LOG"; set +e
tf plan -input=false -out=tfplan -detailed-exitcode 2>&1 | tee -a "$LOG"; RC=${PIPESTATUS[0]}; set -e
case "$RC" in 0) ok "no changes" ;; 2) ok "plan ready" ;; *) explain_failure; die "plan failed" ;; esac
[ "$PLAN_ONLY" = 1 ] && { info "plan only — stopping"; exit 0; }
if [ "$RC" = 2 ]; then
  confirm "apply this plan to $CLUSTER_NAME?"
  info "terraform apply (Istio charts download + rollout: ~5 min)"
  set +e; tf apply -input=false tfplan 2>&1 | tee -a "$LOG"; RC=${PIPESTATUS[0]}; set -e
  [ "$RC" = 0 ] || { explain_failure; die "apply failed — fix and re-run (Terraform resumes)"; }
  ok "apply complete"
fi
rm -f "$DIR/tfplan"
write_gateway_env

IP="$(tf_out public_ip)"; URL="$(tf_out public_url)"
info "smoke test"
for i in $(seq 1 12); do
  CODE="$(curl -k -s -m 8 -o /dev/null -w '%{http_code}' "$URL/" 2>/dev/null || true)"
  [ -n "$CODE" ] && [ "$CODE" != 000 ] && break; sleep 5
done
case "${CODE:-000}" in
  404) ok "gateway answers on $URL (HTTP 404 = Envoy is up, no routes yet — expected)" ;;
  000) warn "no answer from $URL yet — the Azure LB can take 1-2 minutes after the IP shows up; run scripts/test.sh in a moment" ;;
  *)   ok "gateway answers on $URL (HTTP $CODE)" ;;
esac
echo
info "02-istio-gateway done"
echo "    public URL:  ${c_bold}$URL${c_off}   (IP $IP)"
echo "    gateway:     $(tf_out gateway)       TLS secret: $(tf_out tls_secret)   cert expires: $(tf_out certificate_expires)"
echo "    trust cert:  scripts/trust-cert.sh   (so browsers stop warning)       test: scripts/test.sh"
echo "    next:        cd ../03-apps-keycloak && scripts/install.sh"
