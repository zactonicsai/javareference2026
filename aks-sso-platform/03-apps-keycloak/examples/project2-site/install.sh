#!/usr/bin/env bash
# Deploy the example static site and publish it at /project2/ for members of the Keycloak group "project2".
#   examples/project2-site/install.sh [--yes]      remove: examples/project2-site/install.sh --remove
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../../scripts/lib.sh"
REMOVE=0
while [ $# -gt 0 ]; do case "$1" in --remove) REMOVE=1 ;; --yes|-y) YES=1 ;; --namespace|-n) NAMESPACE="$2"; shift ;; *) die "unknown flag $1" ;; esac; shift; done
need kubectl helm
confirm_context
if [ "$REMOVE" = 1 ]; then
  "$DIR/scripts/add-service.sh" --id project2-site --remove --yes
  run kubectl -n "$NAMESPACE" delete -f "$HERE/site.yaml" --ignore-not-found
  exit 0
fi
info "deploying project2-site (nginx) to $NAMESPACE"
run kubectl -n "$NAMESPACE" apply -f "$HERE/site.yaml"
wait_rollout deploy/project2-site 180s
YES=1 "$DIR/scripts/add-service.sh" --id project2-site --name "Project 2 site" --path /project2/ \
  --upstream "http://project2-site.$NAMESPACE.svc.cluster.local:8080" --health-url "http://project2-site.$NAMESPACE.svc.cluster.local:8080/healthz" \
  --group project2 --icon folder --order 50 --description "Static site for the project 2 team (nginx behind sso-proxy)"
