# Interview walkthrough — OrbitWatch

A script for presenting the project in a technical deep dive for the Slingshot Software
Engineering Manager role (backend services, APIs, integrations; Space Force / government
stakeholders; Java, Kafka, GraphQL, TypeScript, Kotlin in the JD). Aim for ten minutes of
narrative, then let the questions drive.

## The 60-second version

"I wanted to spend my free time on the shape of problem your team has: take an external data
provider you don't control, turn it into reliable internal streams with clear contracts, and give
operators something they can act on. CelesTrak publishes the NORAD catalogue as one 7 MB document
every couple of hours. OrbitWatch turns that into a keyed change stream on Kafka, propagates all
16,500 objects every ten seconds with SGP4, runs streaming analytics — fleet stats, close-approach
screening, decay watch, manoeuvre candidates — and serves it over REST, GraphQL and SSE to a live
dashboard. Four Spring Boot services, one Dockerfile, compose locally, Terraform and Kubernetes
for OCI. It found TerraSAR-X and TanDEM-X flying 500 m apart and MEV-2 docked to Intelsat 10-02
in its first ten minutes, which told me the numerics were right."

## The demo path (with the stack running)

1. **Dashboard** (`:8080`). Point at the tick timestamp advancing, 16,506 objects, regime bars
   (LEO 15.7k / GEO 548 / MEO 184 / HEO 44). Toggle GEO only — the belt. Search "TANDEM", click
   it: element set, owner, ground track for the next 100 minutes, and its conjunction alert with
   TerraSAR-X.
2. **Kafka UI** (`:8390`). Show the topics: compacted catalogue tables vs. the state-vector
   stream; ~1,650 msgs/s on `state-vectors`; consumer groups and lag — analytics at zero.
3. **GraphiQL** (`/graphiql`). Run
   `{ satellites(regime: GEO, limit: 3) { objectName owner altKm recentAlerts { title } } }` and
   a subscription `subscription { alertStream(minSeverity: WARNING) { title severity } }`.
4. **Swagger** (`/swagger-ui.html`) for the REST contract, then `curl -N /api/stream/alerts`.
5. **Grafana** (`:3000`) — tick duration, vectors/s, ingest churn — and an ingest `POST /api/ingest/run`
   on `:8081` showing "published=0 unchanged=16506": the change-detection story.
6. **Tests**: `mvn verify` — 100+ tests, no broker: the SGP4 reference test (63 objects × 9
   epochs, 1 m tolerance), the topology test with `TopologyTestDriver`, the grid-vs-brute-force
   conjunction proof.

## Design decisions to lead with (and the alternative rejected)

- **Topics as the contract.** Names, keys, partitions, compaction and record types in one
  versioned module; the producing team provisions its own topics; additive changes are free,
  breaking changes are a new versioned topic. Rejected: per-service YAML and ad-hoc DTOs — the
  drift is how integrations rot. (ADR-0001/0002)
- **Compacted topics as tables, no database.** Every service rebuilds its state from Kafka on
  restart; the ingest restart republishes nothing. Rejected for the demo: a Postgres catalogue —
  it's the first thing to add for history and acknowledgement. (ADR-0005)
- **Aligned ticks.** Propagation snaps to wall-clock multiples so instances and restarts produce
  identical instants; windows key on the tick. This is what makes horizontal scaling and
  active/standby trivial. (ADR-0004)
- **Space-partitioned conjunction screening.** O(N²) → O(N·k) via a spatial hash, and the cell
  index is the Kafka key so the work shards across partitions; ghost copies make it exact; a pair
  is reported exactly once. Validated against brute force. (ADR-0006)
- **Two-stage aggregation for the hot key.** The naive `groupBy(tick)` fell 100k records behind
  on a two-core box; per-partition partials plus a one-partition merge fixed it. A concrete
  example of measuring first, then changing the topology — worth telling as a story.
- **SGP4 port, validated to the metre**, rather than Orekit: single function needed, ~700 lines,
  no data bundle, and a reference test that would catch any regression. Honest caveat: GP+SGP4 is
  km-class accuracy, so the analytics are a *screen*, not a determination.
- **JSON over Avro** for a demo that needs to be readable in Kafka UI; the compatibility
  discipline comes from the contract module and tests; the trade-off flips with external
  consumers or 10× volume.
- **Provider etiquette as code.** The cache enforces CelesTrak's 2-hour rule even across
  restarts, serves stale on outage, identifies itself — default-deny towards the internet is a
  posture, not a comment.

## A war story worth telling: the first real `docker compose up`

The first run on a real machine built fine, every container reported healthy, and the catalogue
stayed empty. Reproducing it in containers and working strictly from logs, it turned out to be the
first of four stacked failures, each hidden behind a green health check: a root-owned cache volume
turned a successful CelesTrak fetch into a failed ingest run (the one actually observed); the same
ownership problem would have crash-looped analytics; behind that, RocksDB's native library could not
load on the Alpine image, so Kafka Streams quietly shut down while the web server kept passing health
checks; and behind that, JVMs without container memory limits were being OOM-killed by the kernel.
(Plus a small one found on the way: the first tick after a restart published a partial catalogue.)

The second run, on a different day, looked like a TLS problem: `SSLHandshakeException: Remote host
terminated the handshake` against CelesTrak. The logs didn't support that story — plain HTTP to the
same host failed too, other hosts were fine, and the same fetch from a cloud box returned 200. The
host was blocked on that network. The interesting part is that this exposed a real design defect,
not a network annoyance: with a cold cache, one unreachable vendor took the whole system to zero.
So the fix isn't a TLS flag, it's a fallback ladder — live fetch, then cache, then stale cache, then
a snapshot committed with the repository — with "degraded" as an explicit, visible state (WARN,
counter, `degraded` in the health endpoint) rather than a silent one. That's the vendor-integration
lesson I'd want to bring to the team: decide in advance what the product does when the partner is
unavailable, and make the degraded mode legible (ADR-0009).

The fixes are the interesting part for a manager role, because none of them is just "patch the
line": readiness now means the service is *producing* (ingest DOWN after a failed run, analytics
DOWN unless Streams is running); a permanently dead component exits so the orchestrator restarts it
and the fault becomes a visible restart loop; an optimisation (the cache) can no longer fail the main
path; resource limits are explicit and soak-tested; and every new signal went onto the runbook's
alarm checklist in the same change. Each rule has a unit test, and each was proven in real
containers by running the broken image and watching the alarm fire (ADR-0008). That's the practice
I'd want a team to have: reproduce from logs, fix the class of failure, and make the next one loud.

## Bridges to the JD

| JD theme | Where it shows up |
|---|---|
| Backend services, APIs, integrations | Four services; REST + GraphQL + SSE; one external provider integration with cache/backoff/change detection; one internal sync call isolated in a client class |
| Clear API contracts, data exchange patterns | `contracts` module, topic table in ARCHITECTURE §3, OpenAPI, GraphQL schema, contract round-trip tests |
| Event-driven systems, Kafka | Compacted tables vs streams, keyed partitioning, Kafka Streams with stateful processors, dedupe store, repartitioning, windows/grace/suppress |
| Testing strategy | Reference-vector numerics, topology tests without a broker, property-style grid proof, CI smoke test that asserts data flows |
| Observability, runbooks, production support | Actuator probes, Prometheus, Grafana, Kafka UI, RUNBOOK.md, alerts for the pipeline's own health (`PROPAGATION_FAILURE`, ingest runs topic) |
| Cloud infra, CI/CD, release readiness | One Dockerfile, compose, Terraform for OCI, OKE kustomize with an OCI Streaming overlay, GitHub Actions with image builds |
| Vendor / partner coordination | The ingest layer is the "vendor boundary": documented interface, rate limits respected, failure isolated, explicit degraded mode when the vendor is unavailable, contract tests — the same shape as a vendor-provided capability |
| Kotlin, TypeScript, GraphQL | TypeScript dashboard; GraphQL with subscriptions; Kotlin is the stated next service (mixed-language reactor is straightforward with the kotlin-maven-plugin) |
| Space domain awareness | Regimes, conjunction screening, decay, manoeuvre detection — the vocabulary of the customer |

## Questions to expect, and short answers

**"How does this scale to the full catalogue with debris (~30k+) or a 1 s tick?"**
Propagation shards by NORAD id across instances (aligned ticks make outputs mergeable); Kafka
partitions scale the analytics; the conjunction screen is already per-cell; the gateway is a cache
and scales out freely. The first real bottleneck is the state-vector topic volume — the fix is a
tighter binary encoding (Avro/Protobuf) and per-consumer filtering, not architecture.

**"What happens when the provider changes its format?"**
The parser is header/field driven and unknown fields are ignored; a missing required field fails
the run, the last cache is kept, an ingest-run record with `error` lands on the ops topic, and the
fetch-failure metric alarms. The rest of the system keeps running on the last good elements.

**"Exactly-once?"**
At-least-once everywhere, with idempotent effects: compacted topics make republishing harmless,
the dedupe store bounds alert repeats, and tick stats are keyed by tick so a duplicate replaces
rather than adds. Exactly-once processing in Streams is a config flag but costs throughput and buys
nothing here.

**"Why not one service?"**
Different scaling profiles and failure domains: ingest is I/O-bound and rate-limited by a third
party; propagation is CPU-bound and horizontally shardable; analytics is stateful stream
processing; the gateway is a fan-out cache. Separate deployables let each be sized, restarted and
owned independently — the same reasoning I'd use to split a team's services.

**"What would you cut, and what did you cut?"**
Cut: a database, auth, a real conjunction assessment stage, Avro. Would cut next if pressed for
time: GraphQL (REST + SSE cover the dashboard); Grafana (Actuator + Kafka UI suffice for a demo).

**"How did you validate the orbital mechanics?"**
Against the reference implementation, not against intuition: 63 objects across every regime, nine
epochs, 1 m tolerance. Then against reality: known formations and docked vehicles showed up as
the closest pairs.

**"What's a manoeuvre candidate, and how good is the heuristic?"**
Consecutive element sets compared after removing the drag trend the set itself encodes; flags
unexplained changes in semi-major axis, inclination or eccentricity. It is deliberately simple and
explainable, will flag Starlink orbit raises and station-keeping, and would need covariance and a
proper OD residual to be more than a triage queue — which is what the real product does.

## Management framing (don't skip this)

This is an IC-shaped artefact presented for a manager role, so say why it exists: to understand
the domain and the integration problem well enough to run design reviews credibly, ask the right
questions of a vendor, and know what "done" looks like for the team. Then talk about how you'd run
it as a team: contracts owned by producers, ADRs for decisions, tests that encode the guarantees
(the grid-vs-brute-force test is the kind of test you ask an engineer for), runbooks and alarms
that somebody reads, and observability before features.
