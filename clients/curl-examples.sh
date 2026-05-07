#!/usr/bin/env bash
# =====================================================================
# spring-demo · curl client examples
# Usage:
#   ./curl-examples.sh             # run all demos
#   ./curl-examples.sh role-admin  # run a single demo (function name)
# =====================================================================
set -u
HOST="${HOST:-http://localhost:8080}"

ADMIN='admin:admin123'
MANAGER='manager:manager123'
USER='user:user123'

hr() { printf '\n\033[1;33m── %s ─────────────────────────────────\033[0m\n' "$*"; }

# ---------- public ----------
public-health() {
  hr "GET /api/health/public  (no auth)"
  curl -sS -i "$HOST/api/health/public"
}

public-actuator-health() {
  hr "GET /actuator/health  (no auth - status only)"
  curl -sS -i "$HOST/actuator/health"
}

# ---------- role/me ----------
role-admin() {
  hr "GET /api/role/me  as admin  -> AdminDto"
  curl -sS -u "$ADMIN" "$HOST/api/role/me" | jq .
}
role-manager() {
  hr "GET /api/role/me  as manager  -> ManagerDto"
  curl -sS -u "$MANAGER" "$HOST/api/role/me" | jq .
}
role-user() {
  hr "GET /api/role/me  as user  -> UserDto"
  curl -sS -u "$USER" "$HOST/api/role/me" | jq .
}

# ---------- products CRUD ----------
list-products() {
  hr "GET /api/products  as user"
  curl -sS -u "$USER" "$HOST/api/products" | jq .
}
get-product() {
  hr "GET /api/products/1"
  curl -sS -u "$USER" "$HOST/api/products/1" | jq .
}
create-product() {
  hr "POST /api/products  as manager  (USER cannot create)"
  curl -sS -u "$MANAGER" -H 'Content-Type: application/json' \
    -d '{"name":"Standing Desk","description":"Adjustable, electric","price":499.00,"stock":15}' \
    "$HOST/api/products" | jq .
}
create-product-as-user-forbidden() {
  hr "POST /api/products  as user  (expected 403)"
  curl -sS -u "$USER" -H 'Content-Type: application/json' \
    -d '{"name":"foo","description":"x","price":1.00,"stock":1}' \
    "$HOST/api/products" -i | head -n 1
  curl -sS -u "$USER" -H 'Content-Type: application/json' \
    -d '{"name":"foo","description":"x","price":1.00,"stock":1}' \
    "$HOST/api/products" | jq .
}
update-product() {
  hr "PUT /api/products/1  as manager"
  curl -sS -u "$MANAGER" -H 'Content-Type: application/json' \
    -d '{"name":"Wireless Mouse","description":"Updated","price":34.99,"stock":99}' \
    "$HOST/api/products/1" | jq .
}
delete-product-forbidden() {
  hr "DELETE /api/products/1  as manager  (expected 403, only ADMIN can delete)"
  curl -sS -u "$MANAGER" -X DELETE -i "$HOST/api/products/1" | head -n 1
}
delete-product() {
  hr "DELETE /api/products/4  as admin"
  curl -sS -u "$ADMIN" -X DELETE -i "$HOST/api/products/4" | head -n 5
}
get-missing-product() {
  hr "GET /api/products/9999  (expected 404 with PRODUCT_NOT_FOUND code)"
  curl -sS -u "$USER" "$HOST/api/products/9999" | jq .
}
post-invalid-product() {
  hr "POST /api/products with invalid body (expected 400 VALIDATION_FAILED)"
  curl -sS -u "$MANAGER" -H 'Content-Type: application/json' \
    -d '{"name":"","price":-1,"stock":null}' \
    "$HOST/api/products" | jq .
}

# ---------- products via Temporal/Postgres ----------
list-temporal-products() {
  hr "GET /api/products-temporal  (reads from Postgres)"
  curl -sS -u "$USER" "$HOST/api/products-temporal" | jq .
}
create-temporal-product() {
  hr "POST /api/products-temporal  (CreateProductWorkflow → activity → Postgres)"
  curl -sS -u "$MANAGER" -H 'Content-Type: application/json' \
    -d '{"name":"Webcam HD","description":"1080p webcam","price":59.99,"stock":40}' \
    "$HOST/api/products-temporal" | jq .
}
update-temporal-product() {
  hr "PUT /api/products-temporal/1  (UpdateProductWorkflow)"
  curl -sS -u "$MANAGER" -H 'Content-Type: application/json' \
    -d '{"name":"Webcam HD Pro","description":"4K webcam","price":129.99,"stock":15}' \
    "$HOST/api/products-temporal/1" | jq .
}
delete-temporal-product() {
  hr "DELETE /api/products-temporal/1  (DeleteProductWorkflow, ADMIN only)"
  curl -sS -u "$ADMIN" -X DELETE -i "$HOST/api/products-temporal/1"
}
delete-missing-temporal-product() {
  hr "DELETE /api/products-temporal/9999  (workflow surfaces PRODUCT_NOT_FOUND)"
  curl -sS -u "$ADMIN" -X DELETE "$HOST/api/products-temporal/9999" | jq .
}

# ---------- file uploads (LocalStack S3) ----------
upload-file() {
  hr "POST /api/files/upload  (multipart, any auth user)"
  local f="${1:-/tmp/spring-demo-test.txt}"
  echo "spring-demo upload test $(date)" > "$f"
  curl -sS -u "$USER" -F "file=@$f" "$HOST/api/files/upload" | jq .
}
list-files() {
  hr "GET /api/files"
  curl -sS -u "$USER" "$HOST/api/files" | jq .
}
file-with-presigned-url() {
  hr "GET /api/files/1  (returns metadata + 15-min presigned download URL)"
  curl -sS -u "$USER" "$HOST/api/files/1" | jq .
}
download-file() {
  hr "GET /api/files/1/download  (streams content)"
  curl -sS -u "$USER" -OJ "$HOST/api/files/1/download"
  ls -la spring-demo-test.txt 2>/dev/null || true
}
delete-file-forbidden-as-user() {
  hr "DELETE /api/files/1  as user (expected 403)"
  curl -sS -u "$USER" -X DELETE -i "$HOST/api/files/1"
}

# ---------- health ----------
health-secure() {
  hr "GET /api/health/secure  as admin"
  curl -sS -u "$ADMIN" "$HOST/api/health/secure" | jq .
}

# ---------- actuator ----------
actuator-info() { hr "/actuator/info"; curl -sS "$HOST/actuator/info" | jq .; }
actuator-metrics-as-admin() { hr "/actuator/metrics  (admin)"; curl -sS -u "$ADMIN" "$HOST/actuator/metrics" | jq '.names | length'; }

# ---------- runner ----------
DEMOS=(
  public-health public-actuator-health
  role-admin role-manager role-user
  list-products get-product
  create-product create-product-as-user-forbidden
  update-product
  delete-product-forbidden
  get-missing-product post-invalid-product
  list-temporal-products create-temporal-product
  upload-file list-files file-with-presigned-url
  delete-file-forbidden-as-user
  health-secure actuator-info actuator-metrics-as-admin
)

if [[ $# -eq 0 ]]; then
  for d in "${DEMOS[@]}"; do "$d"; done
else
  "$@"
fi
