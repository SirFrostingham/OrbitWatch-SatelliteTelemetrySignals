# Runbook

## Health at a glance
- `docker compose ps` → every service `(healthy)` with no climbing restart count. Health is not just
  "the JVM is up": ingest readiness is DOWN when its last run failed, analytics readiness is DOWN when
  the Kafka Streams client is not running, and analytics exits (code 70) if Streams enters ERROR.
- Dashboard header: "live" pill and the tick advancing every 10 s.
- `GET :8082/api/propagation/status` → `catalogSize` ≈ 16.5k, `lastTick` recent, `lastTickObjects` ≈ catalogSize.
- `GET :8083/actuator/health/readiness` → `kafkaStreams.state: RUNNING`.
- `GET :8081/actuator/health/readiness` → `ingest` UP, `cache: disk:/data/cache`, `cacheWriteFailures: 0`,
  and **no `degraded` key** (a `degraded` value means the data is a committed snapshot, not live).
- Kafka UI (`:8390`) → consumer group `orbitwatch-analytics` lag near zero on `state-vectors`.

## Alarms that already exist — check every one of them on every review
Every watchdog added to the system goes on this list in the same change. A review that only looks
at the dashboard misses most of these.

| Signal | Where | Meaning |
|---|---|---|
| Any container `(unhealthy)` or restart count > 0 | `docker compose ps`, `docker inspect -f '{{.RestartCount}}'` | Something below is firing — read that service's log first |
| `Restarting (70)` / `kafka streams client entered ERROR` | `docker compose ps`, analytics log | Streams died; the exception just above that line is the cause (e.g. RocksDB native library, state dir permissions) |
| ingest readiness DOWN / ingest run with `error` | `:8081/actuator/health/readiness`, `ow.ops.ingest-runs.v1`, dashboard "Ingest runs" | Fetch with no cache to fall back on, parse or publish failure |
| `orbitwatch_ingest_cache_write_failures` > 0 / `could not write cache` WARN | Prometheus, ingest log, health `cache: memory-only` | Cache volume not writable or full; ingest still works but the provider interval is not persisted across restarts |
| `orbitwatch_ingest_gp_fetch_failures_total` rising / `fetch failed` WARN | Prometheus, ingest log | Provider unreachable; running on cache |
| `orbitwatch_ingest_seed_fallbacks` > 0 / `seeding from committed snapshot` WARN / health `degraded` | Prometheus, ingest log, `:8081/actuator/health/readiness` | Provider unreachable **and** cache cold: the catalogue is the snapshot in `data/`, frozen at its capture date. Everything works, nothing is live (ADR-0009) |
| `OutOfMemoryError` in any log, or a kernel `Out of memory: Killed process` | service log (JVM exits on OOM), `dmesg` / Docker Desktop logs | Heap or container limit too small for the workload — see "Memory" |
| `element sets still arriving after 3 deferrals` WARN | propagation log | Catalogue updates never settle; ticks published with a moving catalogue |
| `PROPAGATION_FAILURE` alerts | alerts topic, dashboard | Objects that decayed / bad elements; a sudden burst means a bad feed |
| `Skipping record for expired window` WARN | analytics log | Analytics falling behind (lag) — see "Analytics lag" |
| `previous tick still running; skipping` WARN | propagation log | Tick slower than interval — CPU starved or Kafka slow |
| `orbitwatch_propagation_last_tick_objects` ≪ `catalog_size` | Prometheus | Many propagation failures |

One-liner that sweeps the log-based alarms across the stack:

```bash
for c in ow-ingest ow-propagation ow-analytics ow-gateway; do
  echo "== $c restarts=$(docker inspect -f '{{.RestartCount}}' $c)"
  docker logs $c 2>&1 | grep -E " ERROR |entered ERROR|OutOfMemory|could not write cache|fetch failed|seeding from committed|still arriving after|expired window|previous tick still running" | tail -5
done
```

## Common situations
**Provider unreachable from this network (`Remote host terminated the handshake`, `ConnectException`,
`UnknownHostException` for celestrak.org).** Ingest seeds from the snapshot in `data/` and keeps
going; the dashboard is fully populated but the elements are frozen at the snapshot date, and the
ingest health endpoint says so under `degraded`. Nothing to fix in the app. To confirm it is the
network and not the provider, from any other host:
`curl -sSo /dev/null -w '%{http_code}\n' https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=json`
— a 200 elsewhere and a hang or reset here means the host is blocked or blackholed locally (corporate
proxy, DNS/RPZ filtering, ISP). Workarounds: run the stack from a network that can reach it,
refresh `data/` by hand from a machine that can, or point `ORBITWATCH_INGEST_GP_BASEURL` at a mirror.
Check `http://celestrak.org` too: if plain HTTP fails as well, it is not a TLS problem.

**Port already allocated on start (e.g. `Bind for 127.0.0.1:8080 failed`).** `start.sh` /
`start.cmd` check every published port before starting, pick the next free one, and record the
choice in `.env` — so this should not happen through the scripts, and the URLs they print are the
ones in use. Running `docker compose up` by hand skips that check; set the overrides yourself in
`.env`: `GATEWAY_PORT`, `INGEST_PORT`, `PROPAGATION_PORT`, `ANALYTICS_PORT`, `KAFKA_UI_PORT`,
`KAFKA_HOST_PORT`, `PROMETHEUS_PORT`, `GRAFANA_PORT`. `KAFKA_HOST_PORT` moves the published port
*and* the advertised address together, so host clients (and `scripts/run-local.sh`) follow it.
To go back to the defaults, delete `.env` — the ports are re-picked on the next start.

**`catalog empty; waiting for element sets` that never goes away.** Normal for the first seconds.
If it persists: ingest has not published. Check ingest readiness (`:8081/actuator/health/readiness`)
— the failed run's error is in the details — and the ingest log.

**Permission denied on `/data/cache` or `state directory ... couldn't be created`.** The image
creates both mount points owned by the `app` user, and Docker copies that ownership into a named
volume when the volume is empty. A volume that was already non-empty and root-owned keeps root: run
`docker compose down -v` (wipes Kafka data too) and start again. Ingest keeps working regardless
(in-memory cache fallback); analytics cannot.

**Memory.** Every container has a `mem_limit`, and JVM heaps are 65 % of it (`-XX:MaxRAMPercentage=65`,
G1). Observed steady state on the full catalogue: ingest ~300 MiB / 768 MiB, propagation ~330 MiB /
1 GiB, gateway ~330 MiB / 1 GiB, analytics ~600 MiB / 1.5 GiB, Kafka ~600–800 MiB / 1.5 GiB (page
cache counts, and is reclaimable). The whole stack needs about 6 GB available to Docker (Docker
Desktop → Settings → Resources, or `.wslconfig` on Windows). Never remove the limits: without them
every JVM sizes its heap against the whole VM and the kernel kills them.

**No data on the dashboard after start.** Order of readiness is ingest → propagation → analytics
(stats appear ~20 s after the first tick) → gateway. Check the ingest log for the first run line;
check `propagation/status` for `catalogSize`.

**Analytics lag / expired windows.** After a restart with a backlog this is expected while
catching up (threads run at different speeds). If it persists in steady state: increase
`num.stream.threads`, increase `window-grace`, or add a second analytics instance (partitions
rebalance automatically).

**Provider blocked / rate limited.** Never reduce `min-interval` below PT2H. Use replay mode
(`INGEST_GP_REPLAY_FILE`) for demos and load tests.

**Reset analytics state** (e.g. after changing the topology):
`kafka-streams-application-reset.sh --application-id orbitwatch-analytics --input-topics ow.telemetry.state-vectors.v1,ow.catalog.gp-elements.v1 --to-latest`
with the service stopped, then delete its state dir.

**Kafka disk.** State vectors retain 1 h (~600 MB/h compressed at 10 s ticks). Adjust in `TopicSpecs`.

## Local operations
- Whole stack in Docker: `docker compose up --build` (add `-d` to detach); `docker compose down` stops; `down -v` also wipes volumes.
- Kafka in Docker, services as local JVMs: `docker compose up -d kafka kafka-ui && scripts/run-local.sh [--offline]`; logs in `/tmp/orbitwatch`; `scripts/stop-local.sh`.
- Force an ingest run: `curl -X POST :8081/api/ingest/run`.
