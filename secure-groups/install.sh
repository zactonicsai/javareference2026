#!/usr/bin/env bash
set -euo pipefail

# 1. Istio must be installed before the app chart, otherwise the VirtualService
#    fails with "no matches for kind VirtualService" (CRDs missing).
#    Also creates the shared Gateway "istio-gateway" in the devspaces namespace.
./istio/install-istio.sh

# 2. Build/load the app image into kind
docker save -o spring-demo.tar spring-demo:1.0.0
kind load image-archive spring-demo.tar --name devspaces
docker exec -it devspaces-control-plane crictl img

# 3. Deploy the app (Deployment, Service, Secret, VirtualService -> istio-gateway / myhost)
helm upgrade --install my-secure-groups ./helm --namespace devspaces --wait

# 4. Reach it through the Istio ingress gateway (kind has no LoadBalancer, so port-forward):
echo
echo "Run:  kubectl port-forward -n istio-system svc/istio-ingressgateway 8080:80"
echo "Then: curl -H 'Host: myhost' http://localhost:8080/"
