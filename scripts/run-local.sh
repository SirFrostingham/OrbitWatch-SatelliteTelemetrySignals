#!/usr/bin/env bash
# Run the four services as local JVMs against the compose Kafka (localhost:29092).
# Usage: scripts/run-local.sh [--offline]   (offline = replay the data/ snapshots, no CelesTrak calls)
set -euo pipefail
cd "$(dirname "$0")/.."
LOG=${LOG_DIR:-/tmp/orbitwatch}; mkdir -p "$LOG"
export KAFKA_BOOTSTRAP_SERVERS=${KAFKA_BOOTSTRAP_SERVERS:-localhost:${KAFKA_HOST_PORT:-29092}}
export INGEST_CACHE_DIR=${INGEST_CACHE_DIR:-$LOG/cache}
export KAFKA_STREAMS_STATE_DIR=${KAFKA_STREAMS_STATE_DIR:-$LOG/streams}
if [[ "${1:-}" == "--offline" ]]; then
  export INGEST_GP_REPLAY_FILE="$PWD/data/celestrak-active-2026-09-09.json"
  export INGEST_SATCAT_REPLAY_FILE="$PWD/data/satcat-2026-09-09.csv"
fi
for s in ingest-service propagation-service analytics-service gateway-service; do
  jar=$(ls "$s"/target/"$s"-*.jar | grep -v original | head -1)
  nohup java -jar "$jar" > "$LOG/$s.log" 2>&1 &
  echo "started $s (pid $!) -> $LOG/$s.log"
done
echo "dashboard: http://localhost:8080   kafka-ui: http://localhost:8390 (docker compose up -d kafka-ui)"
