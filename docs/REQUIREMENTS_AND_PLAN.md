# Requirements and plan

Living document. Requirements are recorded as stated; status is kept current.

## Requirements (as stated, 2026-09-10)

| # | Requirement | Status |
|---|---|---|
| R1 | Demo application relating to the space / SDA industry, built in free time, to bring to a Slingshot Aerospace Engineering Manager technical deep dive | Done — OrbitWatch |
| R2 | Containerized | Done — one multi-stage `Dockerfile` for all services, `docker-compose.yml` |
| R3 | Deployable to a cloud such as OCI | Done — Terraform single-VM (Ampere A1) + OKE kustomize + OCI Streaming overlay + OCIR push script |
| R4 | Java + Spring Boot + Kafka, plus related JD technologies | Done — Spring Boot 3.5 / Java 21, Kafka + Kafka Streams, GraphQL, TypeScript/React, REST/OpenAPI, SSE. Kotlin: planned (next service) |
| R5 | Server(s) and a way for a client to connect and derive interesting data | Done — four services; browser dashboard, REST, GraphQL (query + subscription), SSE; `curl`-able |
| R6 | Explainable in system-design terms | Done — `docs/ARCHITECTURE.md`, `docs/adr/*`, `docs/INTERVIEW_TALKING_POINTS.md`, diagrams |
| R7 | Not limited to NORAD data; CelesTrak GP JSON as a starting point | Done for GP + SATCAT; ingest is source-pluggable (`groups`, replay files); other sources listed in `docs/PROJECT_IDEAS.md` |
| R8 | Source lives at `E:\source\Research\SatelliteTelemetrySignals` | Done |
| R9 | Take the implementation as far as possible | Done to a running, tested, documented end-to-end system (see "What is verified") |

## What is verified

- `mvn verify`: 138 tests green across contracts (5), astro-core (82, incl. 65 reference-vector
  cases), ingest (28), propagation (6), analytics (11, incl. TopologyTestDriver), gateway (6, incl.
  GraphQL schema wiring).
- **In Docker containers** (runtime images built from the Dockerfile's runtime stage, compose stack,
  live fetch path served locally): all five containers healthy with zero restarts; 16,506 objects
  every 10 s; every tick in the stats history complete (no partial first tick, including after a
  propagation restart); conjunction and decay alerts at the gateway; dashboard, Swagger and GraphQL
  answering; an 8-minute soak with every service flat at ≤ 60 % of its memory limit and zero OOM
  kills.
- **Provider-outage path proven in containers** (2026-09-19): with the CelesTrak host unreachable and
  the cache volume empty, ingest published 16,506 element sets and 16,506 SATCAT rows from the
  committed snapshot, propagation ticked 16,498/16,506 objects, and ingest health stayed UP with the
  `degraded` detail naming the snapshot file and the cause.
- Alarms proven in containers: a broken analytics image restart-loops with exit 70 instead of
  idling healthy; a failed ingest run turns the ingest container `(unhealthy)` with the error in its
  health details.
- On the user's machine (2026-09-10): the full `docker compose up --build` image build succeeds
  (dashboard npm build + Maven build inside Docker).
- Restart behaviour: ingest warm restart republishes 0; propagation reloads the catalogue from the
  compacted topic; gateway rebuilds views; volumes left empty by the first failed run are fixed by a
  plain rebuild (Docker copies the image's ownership into an empty volume).
- Terraform HCL parses; Kubernetes YAML parses. Not yet run: the OCI apply.

## Findings from the first real run (2026-09-10) — all fixed and verified

| Symptom on `docker compose up` | Root cause (from logs) | Fix |
|---|---|---|
| `kafka-ui`: `Bind for 127.0.0.1:8090 failed: port is already allocated` | Port 8090 already used on the host | Every published host port has an env override (`KAFKA_UI_PORT`, `GATEWAY_PORT`, …), and kafka-ui's default moved to **8390** so no `.env` is needed |
| Propagation: `catalog empty; waiting for element sets`, forever | Ingest fetched fine, then `AccessDeniedException: /data/cache/gp-active.json.tmp` failed the run: named volume mount point owned by root, service runs as `app` | Image creates `/data/cache` and `/data/streams` owned by `app`; cache write failures fall back to memory instead of failing the run; ingest readiness DOWN on a failed run |
| (next in line) analytics crash loop | `state directory [/data/streams/orbitwatch-analytics] doesn't exist and couldn't be created` — same ownership cause | Same image fix |
| (next in line) analytics "healthy" but no stats, no alerts | RocksDB native library: `Error loading shared library libstdc++.so.6` on Alpine; Streams client shut down, web server kept running | `apk add libstdc++`; Streams readiness indicator; JVM exits on Streams ERROR |
| (next in line) random JVM deaths, no log line | Kernel OOM killer: no container memory limits, `MaxRAMPercentage=70` + ZGC per JVM against the whole VM | `mem_limit` per container, G1, `MaxRAMPercentage=65`, `ExitOnOutOfMemoryError`; soak-tested |
| First tick after start/restart had 11,457 of 16,506 objects | Ticker fired while the compacted topic was still loading | Tick deferred while element sets are still arriving (≤ 3 ticks) |

## Findings from the second real run (2026-09-10) — all fixed and verified

| Symptom on `docker compose up` | Root cause (from logs) | Fix |
|---|---|---|
| Ingest: `SSLHandshakeException: Remote host terminated the handshake` for both CelesTrak URLs, then `catalog empty; waiting for element sets` forever | Not TLS: celestrak.org is unreachable from that network entirely — plain `http://celestrak.org` also returned nothing (000) while example.com and github.com returned 200, and the same fetch from a cloud host returned 200. With a cold cache one unreachable vendor took the whole system to zero | Ingest seeds from the committed snapshot in `data/` when a fetch fails with no cache: run succeeds, flagged `degraded` in health + `seed_fallbacks` metric + WARN; next scheduled run retries live (ADR-0009) |
| `kafka-ui` port clash again (no `.env` created) | Default was still 8090 | Default moved to 8390; the override still exists |
| `fetch failed: null` in the ingest log | The underlying IOException carries no message | Failures are logged by exception type; HTTP/1.1 pinned; transport failures retried 3× with backoff, status errors not retried |

## Next (in order)

1. **Rerun on your machine**: `docker compose up --build` — no `.env` needed. Expect all five
   containers `(healthy)` within ~2 minutes and the dashboard at http://localhost:8080, populated
   with 16,506 objects even though CelesTrak is unreachable from your network (ingest will log
   `seeding from committed snapshot` and report `degraded` in its health details). Make sure Docker
   has ~6 GB of memory available. To get live data, run it from a network that can reach
   celestrak.org, or refresh the two files in `data/` from a machine that can.
2. **Push to GitHub** (`sirfrostingham/SatelliteTelemetrySignals`) so the CI workflow and the
   Terraform `repo_url` default become real. Before the first push: `data/` holds two ~7 MB
   snapshots (fine, well under GitHub's 100 MB cap); no secrets are tracked; the `.gitignore`
   excludes the built dashboard, `target/`, caches and Terraform state.
3. **OCI**: copy `deploy/oci/terraform/orbitwatch.tfvars.example` → `orbitwatch.tfvars`, fill in,
   `terraform apply`. Ampere A1 4 OCPU / 24 GB is Always-Free eligible where capacity allows.
4. **Kotlin service** to cover the JD: a small `cli-client` (Kotlin + GraphQL WebSocket
   subscription printing alerts) or port `analytics-service` — ~1 day.
5. **Durable alerts**: Postgres sink for alerts with acknowledge/resolve, and a history endpoint.
6. **Refined conjunction assessment**: for screened pairs, propagate both at 1 s over ±10 min to
   find TCA and miss distance; fold element-set age into a confidence.
7. **Second source**: Space-Track GP (authenticated, shows credential handling and rate limits)
   or an ADS-B feed to demonstrate that the ingest pattern generalises.

## Decisions log

See `docs/adr/`. Notable calibrations made during verification: GEO band tightened to ±0.5 % of a
sidereal day (graveyard orbits were classified GEO); decay-watch B* rule raised to 5×10⁻³ with
perigee < 300 km (Starlink insertion orbits produced ~530 false positives); pairs under 10 m
treated as one physical object (ISS + docked vehicles); tick statistics moved to two-stage
aggregation (hot key); window grace 5 s. First-run container findings and fixes are in the table
above and in ADR-0008 (health means producing; dead components exit).
