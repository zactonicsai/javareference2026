#!/usr/bin/env bash
# Rebuild the compiled Tailwind stylesheet after editing templates. Needs Node 18+ (npx). Output is committed,
# so the Gradle/Docker build never needs Node or the Internet for CSS.
set -euo pipefail
cd "$(dirname "$0")"
npx -y tailwindcss@3.4.17 -c tailwind.config.js -i input.css -o ../src/main/resources/static/css/app.css --minify
ls -la ../src/main/resources/static/css/app.css
