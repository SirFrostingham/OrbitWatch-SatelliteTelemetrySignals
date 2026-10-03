#!/usr/bin/env bash
# Build all four images and push them to OCI Registry.
# Usage: REGION=phx TENANCY_NS=<tenancy-namespace> TAG=0.1.0 deploy/ocir-push.sh
#   (docker login <region>.ocir.io first: username "<tenancy-namespace>/<user>", password = auth token)
set -euo pipefail
cd "$(dirname "$0")/.."
: "${REGION:=phx}" "${TENANCY_NS:?set TENANCY_NS}" "${TAG:=0.1.0}"
for s in ingest-service propagation-service analytics-service gateway-service; do
  img="$REGION.ocir.io/$TENANCY_NS/orbitwatch/$s:$TAG"
  docker build --build-arg SERVICE=$s -t "$img" .
  docker push "$img"
done
