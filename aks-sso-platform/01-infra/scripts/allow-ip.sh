#!/usr/bin/env bash
# Change who may reach the website (NSG rules for 80/443). Updates allowed_source_cidrs in terraform.tfvars and
# applies just the two NSG rules (seconds, not minutes).
#
#   scripts/allow-ip.sh --me                # only my current public IP
#   scripts/allow-ip.sh --me --add          # add my IP to the existing list
#   scripts/allow-ip.sh 203.0.113.0/24 198.51.100.7/32
#   scripts/allow-ip.sh --open              # back to the whole Internet (0.0.0.0/0)
#   scripts/allow-ip.sh --show              # print the current list
source "$(dirname "$0")/lib.sh"
TFVARS="$DIR/terraform.tfvars"
ME=0; ADD=0; OPEN=0; SHOW=0; CIDRS=()
while [ $# -gt 0 ]; do
  case "$1" in --me) ME=1 ;; --add) ADD=1 ;; --open) OPEN=1 ;; --show) SHOW=1 ;; --yes|-y) YES=1 ;; -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    -*) die "unknown flag $1" ;; *) CIDRS+=("$1") ;; esac; shift
done
need az terraform python3 curl
az_session
tf_init

current() { tf output -json allowed_source_cidrs 2>/dev/null | python3 -c 'import sys,json;print(" ".join(json.load(sys.stdin)))' 2>/dev/null || echo "(unknown — not applied yet)"; }
if [ "$SHOW" = 1 ]; then info "currently allowed: $(current)"; exit 0; fi

if [ "$ME" = 1 ]; then
  IP="$(curl -4 -s -m 10 https://api.ipify.org || curl -4 -s -m 10 https://ifconfig.me || true)"
  [[ "$IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "could not detect your public IPv4 address; pass a CIDR instead"
  ok "your public IP: $IP"; CIDRS+=("$IP/32")
fi
if [ "$OPEN" = 1 ]; then CIDRS=("0.0.0.0/0"); ADD=0; fi
[ ${#CIDRS[@]} -gt 0 ] || die "nothing to do (see --help)"
for c in "${CIDRS[@]}"; do [[ "$c" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/[0-9]+$ ]] || die "not a CIDR: $c (use a.b.c.d/32 for a single address)"; done
if [ "$ADD" = 1 ]; then
  for c in $(current); do [[ "$c" == "(unknown"* ]] && continue; [ "$c" = "0.0.0.0/0" ] && continue; CIDRS+=("$c"); done
fi
LIST="$(printf '"%s", ' "${CIDRS[@]}")"; LIST="[${LIST%, }]"

[ -f "$TFVARS" ] || { cp "$DIR/terraform.tfvars.example" "$TFVARS"; warn "created terraform.tfvars from the example"; }
python3 - "$TFVARS" "$LIST" <<'EOF'
import re, sys
path, value = sys.argv[1], sys.argv[2]
text = open(path).read()
line = f"allowed_source_cidrs = {value}"
if re.search(r'^\s*allowed_source_cidrs\s*=', text, re.M):
    text = re.sub(r'^\s*allowed_source_cidrs\s*=.*$', line, text, flags=re.M)
else:
    text = text.rstrip("\n") + "\n" + line + "\n"
open(path, "w").write(text)
EOF
ok "terraform.tfvars: allowed_source_cidrs = $LIST"

info "applying the two NSG rules"
run tf apply -input=false -auto-approve -target=azurerm_network_security_rule.allow_http -target=azurerm_network_security_rule.allow_https
ok "done — now allowed: $(current)"
echo "    note: the gateway Service in 02-istio-gateway also carries loadBalancerSourceRanges; keep both in sync by"
echo "          re-running 02-istio-gateway/scripts/apply.sh (it reads the same list from this directory's state)."
