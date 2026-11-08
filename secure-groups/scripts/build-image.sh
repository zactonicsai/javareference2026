#!/usr/bin/env bash
set -euo pipefail
# Usage: ./scripts/build-image.sh [image:tag]
# Builds frontend CSS, runs Maven tests on Java 25, then creates the runtime image.
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
image_tag="${1:-secure-groups:1.0.0}"
if ! command -v docker >/dev/null 2>&1; then
  echo 'Docker is required. Install/start Docker Desktop or Docker Engine.' >&2
  exit 1
fi
docker info >/dev/null
docker build --pull --tag "$image_tag" "$project_dir"
printf '\nBuilt image: %s\nSee README.md for local HTTP and HTTPS run commands.\n' "$image_tag"
