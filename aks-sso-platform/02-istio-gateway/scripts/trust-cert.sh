#!/usr/bin/env bash
# Make your machine trust the gateway's self-signed certificate so browsers and curl stop warning.
#
#   scripts/trust-cert.sh            # export from the cluster (or ./gateway.crt) and install into the OS trust store
#   scripts/trust-cert.sh --export   # only write ./gateway.crt
#   scripts/trust-cert.sh --remove   # remove it from the trust store again
#
# macOS: System keychain (asks for sudo).  Linux: /usr/local/share/ca-certificates (Debian/Ubuntu) or
# /etc/pki/ca-trust (RHEL/Fedora).  Firefox keeps its own store: Settings → Privacy → Certificates → Import.
source "$(dirname "$0")/lib.sh"
MODE=install
while [ $# -gt 0 ]; do case "$1" in --export) MODE="export" ;; --remove) MODE="remove" ;; --rg) RESOURCE_GROUP="$2"; shift ;; --cluster) CLUSTER_NAME="$2"; shift ;; --yes|-y) YES=1 ;; -h|--help) sed -n '2,9p' "$0"; exit 0 ;; *) die "unknown flag $1" ;; esac; shift; done
need openssl
CERT="$DIR/gateway.crt"

if [ "$MODE" != remove ]; then
  if command -v kubectl >/dev/null 2>&1 && command -v az >/dev/null 2>&1; then
    { az_session; resolve_inputs; ensure_kubecontext; } >/dev/null 2>&1 || true
    NS="$(tf_out ingress_namespace)"; NS="${NS:-istio-ingress}"
    if kubectl -n "$NS" get secret gateway-tls >/dev/null 2>&1; then
      kubectl -n "$NS" get secret gateway-tls -o jsonpath='{.data.tls\.crt}' | base64 -d > "$CERT"
      ok "exported the live certificate from secret $NS/gateway-tls → $CERT"
    fi
  fi
  [ -s "$CERT" ] || die "no certificate: run scripts/apply.sh first (it writes $CERT)"
  info "certificate"
  openssl x509 -in "$CERT" -noout -subject -ext subjectAltName -enddate -fingerprint -sha256 | sed 's/^/    /'
  [ "$MODE" = export ] && exit 0
fi

OS="$(uname -s)"
case "$OS" in
  Darwin)
    if [ "$MODE" = remove ]; then
      FP="$(openssl x509 -in "$CERT" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g')"
      run sudo security delete-certificate -Z "$FP" /Library/Keychains/System.keychain && ok "removed from the System keychain"
    else
      info "adding to the System keychain as a trusted root (sudo prompt)"
      run sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain "$CERT"
      ok "trusted. Restart the browser; Firefox needs a manual import of $CERT"
    fi ;;
  Linux)
    if [ -d /usr/local/share/ca-certificates ]; then
      if [ "$MODE" = remove ]; then run sudo rm -f /usr/local/share/ca-certificates/aks-sso-gateway.crt; else run sudo cp "$CERT" /usr/local/share/ca-certificates/aks-sso-gateway.crt; fi
      run sudo update-ca-certificates >/dev/null && ok "system trust store updated (curl/python ok; Chrome/Firefox use their own NSS store: certutil -d sql:\$HOME/.pki/nssdb -A -t C -n aks-sso-gateway -i $CERT)"
    elif [ -d /etc/pki/ca-trust/source/anchors ]; then
      if [ "$MODE" = remove ]; then run sudo rm -f /etc/pki/ca-trust/source/anchors/aks-sso-gateway.crt; else run sudo cp "$CERT" /etc/pki/ca-trust/source/anchors/aks-sso-gateway.crt; fi
      run sudo update-ca-trust && ok "system trust store updated"
    else
      warn "unknown Linux trust store layout — import $CERT manually"
    fi ;;
  *) warn "unsupported OS $OS — import $CERT manually (Windows: certutil -addstore -f Root gateway.crt)" ;;
esac
