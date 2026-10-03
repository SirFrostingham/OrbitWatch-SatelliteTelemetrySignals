# ADR-0008: Health means "producing", and dead components exit

**Status:** accepted · 2026-09-10

## Context
The first `docker compose up` on a real machine stalled with an empty catalogue while every container
reported healthy. Reproducing it in containers surfaced three failures with the same shape — the
first observed on that machine, the other two waiting directly behind it:

1. Ingest fetched CelesTrak successfully, could not write its cache to a root-owned volume, and
   failed the whole run — nothing published, propagation waited on an empty catalogue forever.
2. Kafka Streams could not load RocksDB's native library on the Alpine runtime image and shut
   itself down; the Spring web server kept answering health checks.
3. Four JVMs without container memory limits each sized their heap against the whole Docker VM;
   the kernel OOM-killed them, and restarts looked like ordinary churn.

A health check that only proves the web server is listening is an alarm nobody can read.

## Decision
- **Readiness reflects real work.** Ingest readiness is DOWN when its last GP or SATCAT run failed.
  Analytics readiness is DOWN unless the Streams client is CREATED, REBALANCING or RUNNING. The
  Docker HEALTHCHECK polls readiness, so these show up in `docker compose ps`.
- **A permanently dead component exits.** Analytics exits with code 70 when Streams enters ERROR,
  and every JVM runs with `-XX:+ExitOnOutOfMemoryError`. The restart policy restarts them; a
  permanent fault becomes a climbing restart count instead of silence.
- **Optimisations never fail the main path.** The ingest cache degrades to memory (still enforcing
  the provider interval) instead of failing a successful fetch; the failure is a WARN, a metric and a
  health detail.
- **Resource limits are explicit.** Every container has a `mem_limit`; heaps are a percentage of it.
- Every new signal goes on the RUNBOOK alarm checklist in the same change.

## Consequences
Failures are visible at the level people actually look at. The cost is restart loops on permanent
faults — which is the point: they are loud. Each rule is covered by a unit test and was verified in
real containers (broken image → restart loop; fixed image → healthy, zero restarts, 8-minute soak
with no OOM kills).
