#!/usr/bin/env bash
# Installs Istio (CRDs, control plane, ingress gateway) into the cluster using the
# official Istio Helm charts, then installs the shared "istio-gateway" Gateway.
# Idempotent: safe to re-run.
set -euo pipefail

ISTIO_VERSION="${ISTIO_VERSION:-}"            # empty = latest chart version
ISTIO_NS="${ISTIO_NS:-istio-system}"
APP_NS="${APP_NS:-devspaces}"                 # namespace the app + Gateway live in
GATEWAY_RELEASE="${GATEWAY_RELEASE:-istio-gateway}"

VERSION_FLAG=()
[[ -n "$ISTIO_VERSION" ]] && VERSION_FLAG=(--version "$ISTIO_VERSION")

echo ">>> Adding Istio Helm repo"
helm repo add istio https://istio-release.storage.googleapis.com/charts >/dev/null
helm repo update istio >/dev/null

echo ">>> Installing istio-base (CRDs) into $ISTIO_NS"
helm upgrade --install istio-base istio/base \
  --namespace "$ISTIO_NS" --create-namespace \
  --set defaultRevision=default \
  "${VERSION_FLAG[@]}" --wait

echo ">>> Waiting for Istio CRDs to be established"
kubectl wait --for=condition=Established crd/virtualservices.networking.istio.io --timeout=120s
kubectl wait --for=condition=Established crd/gateways.networking.istio.io --timeout=120s

echo ">>> Installing istiod (control plane)"
helm upgrade --install istiod istio/istiod \
  --namespace "$ISTIO_NS" \
  "${VERSION_FLAG[@]}" --wait

echo ">>> Installing istio-ingressgateway"
# NodePort because kind has no LoadBalancer by default.
helm upgrade --install istio-ingressgateway istio/gateway \
  --namespace "$ISTIO_NS" \
  --set service.type=NodePort \
  "${VERSION_FLAG[@]}" --wait

echo ">>> Creating namespace $APP_NS (if missing) and enabling sidecar injection"
kubectl create namespace "$APP_NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl label namespace "$APP_NS" istio-injection=enabled --overwrite

echo ">>> Installing shared Gateway '$GATEWAY_RELEASE' into $APP_NS"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
helm upgrade --install "$GATEWAY_RELEASE" "$SCRIPT_DIR/helm-gateway" \
  --namespace "$APP_NS" --wait

echo
echo "Istio is ready. Gateway '$GATEWAY_RELEASE' exists in namespace '$APP_NS'."
kubectl get gateway -n "$APP_NS"
kubectl get pods -n "$ISTIO_NS"
