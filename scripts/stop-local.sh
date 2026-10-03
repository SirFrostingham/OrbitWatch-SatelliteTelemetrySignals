#!/usr/bin/env bash
# Stop the local JVMs started by run-local.sh
for s in ingest-service propagation-service analytics-service gateway-service; do
  pids=$(pgrep -f "$s-[0-9].*\.jar" || true)
  [[ -n "$pids" ]] && kill $pids && echo "stopped $s ($pids)"
done
true
