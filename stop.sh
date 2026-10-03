#!/usr/bin/env bash
# Stop the OrbitWatch stack.
#   ./stop.sh          stop and remove the containers, keep Kafka data and caches
#   ./stop.sh --wipe   also delete the volumes (Kafka log, ingest cache, analytics state)
set -euo pipefail
cd "$(dirname "$0")"

EXTRA=()
case "${1:-}" in
  --wipe) EXTRA=(-v); echo "==> stopping and wiping volumes" ;;
  "")     echo "==> stopping" ;;
  -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

docker compose --profile observability down "${EXTRA[@]}"
echo "stopped."
