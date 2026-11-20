#!/usr/bin/env bash
# Print what is running: Azure view (cluster, pools, network, monitoring, cost drivers) and Kubernetes view
# (nodes, namespaces, workloads, LoadBalancer services). Read-only.
#
#   scripts/cluster-info.sh [--azure-only] [--k8s-only] [--pods]
source "$(dirname "$0")/lib.sh"
AZURE=1; K8S=1; PODS=0
while [ $# -gt 0 ]; do
  case "$1" in --azure-only) K8S=0 ;; --k8s-only) AZURE=0 ;; --pods) PODS=1 ;; -h|--help) sed -n '2,5p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift
done
YES=1
need az
az_session
resolve_cluster

if [ "$AZURE" = 1 ]; then
  info "Cluster  ${c_bold}$CLUSTER_NAME${c_off}  (rg $RESOURCE_GROUP)"
  az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" -o table \
    --query "{name:name, location:location, k8s:currentKubernetesVersion, tier:sku.tier, state:provisioningState, power:powerState.code, upgrade:autoUpgradeProfile.upgradeChannel, nodeOS:autoUpgradeProfile.nodeOsUpgradeChannel, fqdn:fqdn}" 2>/dev/null || die "cluster not found"
  echo
  info "Node pools"
  az aks nodepool list -g "$RESOURCE_GROUP" --cluster-name "$CLUSTER_NAME" -o table \
    --query "[].{name:name, mode:mode, vm:vmSize, os:osSku, disk:osDiskType, count:count, min:minCount, max:maxCount, zones:join(',',availabilityZones||\`[]\`), k8s:currentOrchestratorVersion, state:provisioningState}"
  echo
  info "Network"
  az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" -o table \
    --query "networkProfile.{plugin:networkPlugin, mode:networkPluginMode, dataplane:networkDataplane, policy:networkPolicy, outbound:outboundType, podCidr:podCidr, serviceCidr:serviceCidr}"
  echo "  public IPs in $RESOURCE_GROUP:"
  az network public-ip list -g "$RESOURCE_GROUP" -o table --query "[].{name:name, ip:ipAddress, fqdn:dnsSettings.fqdn, sku:sku.name, attachedTo:ipConfiguration.id}" 2>/dev/null | sed 's/^/    /'
  NSG="$(az network nsg list -g "$RESOURCE_GROUP" --query "[?contains(name,'nodes-nsg')].name | [0]" -o tsv 2>/dev/null || true)"
  if [ -n "$NSG" ]; then
    echo "  NSG $NSG (custom inbound rules):"
    az network nsg rule list -g "$RESOURCE_GROUP" --nsg-name "$NSG" -o table \
      --query "[?direction=='Inbound'].{prio:priority, name:name, access:access, port:destinationPortRange, from:join(',',sourceAddressPrefixes||[sourceAddressPrefix]), to:destinationAddressPrefix}" | sed 's/^/    /'
  fi
  echo
  info "Egress / outbound IPs (what the Internet sees when pods call out)"
  az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query "networkProfile.loadBalancerProfile.effectiveOutboundIPs[].id" -o tsv 2>/dev/null | while read -r id; do
    [ -n "$id" ] && az network public-ip show --ids "$id" --query "ipAddress" -o tsv | sed 's/^/    LB outbound: /'; done
  az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query "networkProfile.natGatewayProfile.effectiveOutboundIPs[].id" -o tsv 2>/dev/null | while read -r id; do
    [ -n "$id" ] && az network public-ip show --ids "$id" --query "ipAddress" -o tsv | sed 's/^/    NAT GW outbound: /'; done
  echo
  info "Monitoring"
  echo "    Log Analytics:  $(az monitor log-analytics workspace show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME-law" --query "{n:name,r:retentionInDays}" -o tsv 2>/dev/null | tr '\t' ' ' || echo '-') (retention days)"
  echo "    Prometheus:     $(az resource show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME-amw" --resource-type Microsoft.Monitor/accounts --query "properties.metrics.prometheusQueryEndpoint" -o tsv 2>/dev/null || echo '-')"
  GR="$(az grafana list -g "$RESOURCE_GROUP" --query "[0].properties.endpoint" -o tsv 2>/dev/null || true)"
  echo "    Grafana:        ${GR:-not enabled (enable_grafana = true)}"
  echo "    Alerts:"
  az monitor metrics alert list -g "$RESOURCE_GROUP" -o table --query "[].{name:name, severity:severity, enabled:enabled, description:description}" 2>/dev/null | sed 's/^/      /'
  echo "    Portal: Container Insights → https://portal.azure.com/#@/resource$(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query id -o tsv)/containerInsights"
  echo
  info "Registry"
  az acr list -g "$RESOURCE_GROUP" -o table --query "[].{name:name, loginServer:loginServer, sku:sku.name}" 2>/dev/null | sed 's/^/    /' || true
  echo
  info "Cost drivers (list prices, approx.)"
  az aks nodepool list -g "$RESOURCE_GROUP" --cluster-name "$CLUSTER_NAME" --query "[].{n:name,vm:vmSize,c:count}" -o tsv | while IFS=$'\t' read -r n vm c; do
    echo "    nodes ($n): $c x $vm  — the main cost; autoscaler min/max bounds it"; done
  echo "    control plane: $(az aks show -g "$RESOURCE_GROUP" -n "$CLUSTER_NAME" --query sku.tier -o tsv) tier  (Free = \$0, Standard ≈ \$73/mo)"
  echo "    load balancer + public IPs ≈ \$20-25/mo, Log Analytics ≈ \$2.76/GB ingested (first 5 GB/day capped), Prometheus by samples"
fi

if [ "$K8S" = 1 ]; then
  command -v kubectl >/dev/null 2>&1 || { warn "kubectl not installed — skipping Kubernetes view"; exit 0; }
  ensure_kubecontext
  kubectl get nodes >/dev/null 2>&1 || { warn "kubectl cannot reach the API server"; exit 0; }
  echo
  info "Nodes"
  kubectl get nodes -o custom-columns='NAME:.metadata.name,STATUS:.status.conditions[?(@.type=="Ready")].status,POOL:.metadata.labels.agentpool,ZONE:.metadata.labels.topology\.kubernetes\.io/zone,VM:.metadata.labels.node\.kubernetes\.io/instance-type,OS:.status.nodeInfo.osImage,VERSION:.status.nodeInfo.kubeletVersion'
  echo
  info "Resource usage (metrics-server)"
  kubectl top nodes 2>/dev/null || warn "metrics not available yet"
  echo
  info "Namespaces and workloads"
  for ns in $(kubectl get ns -o jsonpath='{.items[*].metadata.name}'); do
    n="$(kubectl -n "$ns" get deploy,sts,ds --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    [ "$n" = 0 ] && continue
    notready="$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | grep -Ev 'Running|Completed' | wc -l | tr -d ' ')"
    printf '    %-24s %3s workloads  %3s pods  %s\n' "$ns" "$n" "$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')" "$([ "$notready" = 0 ] && echo "${c_ok}healthy${c_off}" || echo "${c_warn}$notready pod(s) not running${c_off}")"
  done
  echo
  info "Services exposed through Azure load balancers"
  kubectl get svc -A --field-selector spec.type=LoadBalancer -o custom-columns='NAMESPACE:.metadata.namespace,NAME:.metadata.name,EXTERNAL-IP:.status.loadBalancer.ingress[0].ip,PORTS:.spec.ports[*].port' 2>/dev/null
  echo
  info "Istio / platform components"
  for ns in istio-system istio-ingress appsteam; do
    kubectl get ns "$ns" >/dev/null 2>&1 && { echo "    $ns:"; kubectl -n "$ns" get deploy,sts --no-headers 2>/dev/null | sed 's/^/      /'; } || echo "    $ns: not installed"
  done
  if [ "$PODS" = 1 ]; then echo; info "All pods"; kubectl get pods -A -o wide; fi
  echo
  info "Recent warnings (last 30 events)"
  kubectl get events -A --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -n 30
fi
