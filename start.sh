#!/usr/bin/env bash
# Start the whole OrbitWatch stack in Docker and wait until it is actually serving data.
#   ./start.sh                 build (if needed) and start
#   ./start.sh --observability also start Prometheus + Grafana
#   ./start.sh --rebuild       force a rebuild of the service images
#
# Host ports are checked before anything starts: anything already in use on this machine is
# replaced with the next free port, and the choice is written to .env so docker compose, the
# printed URLs and later runs all agree. Delete .env to go back to the defaults.
set -euo pipefail
cd "$(dirname "$0")"

PROFILES=(); BUILD=(--build)
for arg in "$@"; do
  case "$arg" in
    --observability) PROFILES+=(--profile observability) ;;
    --rebuild)       BUILD=(--build --no-cache) ;;
    -h|--help)       sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

docker info >/dev/null 2>&1 || { echo "Docker is not running — start Docker Desktop and try again."; exit 1; }

# ---- host ports -------------------------------------------------------------------------------
# var:default, in the order they are printed
PORT_SPEC="GATEWAY_PORT:8080 INGEST_PORT:8081 PROPAGATION_PORT:8082 ANALYTICS_PORT:8083 \
KAFKA_UI_PORT:8390 KAFKA_HOST_PORT:29092 PROMETHEUS_PORT:9090 GRAFANA_PORT:3000"
ENV_FILE=.env

# A port this stack is already publishing is not a conflict — it is released as the container is replaced.
ours() { docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null | grep '^ow-' | grep -q ":$1->"; }
busy() { ours "$1" && return 1; (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

env_file_value() { [[ -f $ENV_FILE ]] || return 0; sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }

REMAPPED=(); CLAIMED=" "
claimed() { [[ $CLAIMED == *" $1 "* ]]; }
for spec in $PORT_SPEC; do
  var=${spec%%:*}; default=${spec##*:}
  preset=${!var:-}                                   # exported in this shell -> honour it strictly
  if [[ -n $preset ]]; then
    busy "$preset" && { echo "$var=$preset is already in use by something else — pick another and retry."; exit 1; }
    CLAIMED="$CLAIMED$preset "
    continue
  fi
  want=$(env_file_value "$var" || true); want=${want:-$default}
  port=$want
  # free on the host, and not already claimed by another service in this same run
  for _ in $(seq 1 60); do
    if ! busy "$port" && ! claimed "$port"; then break; fi
    port=$((port + 1))
  done
  if busy "$port" || claimed "$port"; then echo "could not find a free port near $want for $var"; exit 1; fi
  CLAIMED="$CLAIMED$port "
  [[ $port != "$want" ]] && REMAPPED+=("$var $want -> $port")
  export "$var=$port"
done

# Record the choice so `docker compose` by hand, stop.sh and the next run all use the same ports.
{
  [[ -f $ENV_FILE ]] && grep -Ev "^($(echo $PORT_SPEC | sed 's/:[0-9]*//g; s/ /|/g'))=" "$ENV_FILE" || true
  echo "# ports chosen by start.sh on $(date '+%Y-%m-%d %H:%M') — delete this file to reset them"
  for spec in $PORT_SPEC; do var=${spec%%:*}; echo "$var=${!var}"; done
} > "$ENV_FILE.tmp" && mv "$ENV_FILE.tmp" "$ENV_FILE"

if [[ ${#REMAPPED[@]} -gt 0 ]]; then
  echo "==> these default ports are in use on this machine, moved:"
  printf '      %s\n' "${REMAPPED[@]}"
fi

# ---- start ------------------------------------------------------------------------------------
echo "==> building and starting (first run pulls images and builds, ~3-5 min)"
docker compose "${PROFILES[@]}" up -d "${BUILD[@]}"

echo "==> waiting for services to report healthy"
CONTAINERS=(ow-kafka ow-ingest ow-propagation ow-analytics ow-gateway)
deadline=$(( $(date +%s) + 300 ))
while :; do
  pending=()
  for c in "${CONTAINERS[@]}"; do
    state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$c" 2>/dev/null || echo missing)
    [[ "$state" == healthy ]] || pending+=("$c=$state")
  done
  [[ ${#pending[@]} -eq 0 ]] && break
  if (( $(date +%s) > deadline )); then
    echo "!! still not healthy after 5 minutes: ${pending[*]}"
    echo "   look at:  docker compose logs --tail=50"
    exit 1
  fi
  [[ -t 1 ]] && printf '\r    %-70s' "${pending[*]}"
  sleep 5
done
[[ -t 1 ]] && printf '\r%-74s\r' ' '

echo
docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
cat <<TXT

Dashboard      http://localhost:$GATEWAY_PORT
REST/Swagger   http://localhost:$GATEWAY_PORT/swagger-ui.html
GraphiQL       http://localhost:$GATEWAY_PORT/graphiql
Kafka UI       http://localhost:$KAFKA_UI_PORT
Ingest health  http://localhost:$INGEST_PORT/actuator/health/readiness

Data takes ~30 s after start to appear (ingest -> propagation -> analytics).
If CelesTrak is unreachable from this network, ingest seeds from the snapshots in data/
and reports "degraded" in its health details — the demo still runs on 16,506 real objects.

Follow the logs:  docker compose logs -f
Stop:             ./stop.sh
TXT
