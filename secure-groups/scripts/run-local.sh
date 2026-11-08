#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
# Prompt silently to keep passwords out of shell history. Exported values also work.
for key in ADMIN_PASSWORD USER_PASSWORD USER2_PASSWORD; do
  if [[ -z "${!key:-}" ]]; then
    read -r -s -p "$key (14+ characters, max 72 UTF-8 bytes): " value
    printf '\n'
    printf -v "$key" '%s' "$value"
    export "$key"
  fi
done
npm install --no-audit --no-fund
npm run build:css
mvn --batch-mode --no-transfer-progress clean verify
java -jar target/secure-groups-1.0.0.jar --spring.profiles.active=local
