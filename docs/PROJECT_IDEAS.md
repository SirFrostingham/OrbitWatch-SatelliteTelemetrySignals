# Project ideas considered

The brief: a containerized, OCI-deployable demo on Java/Spring Boot/Kafka with servers and a client
that derives interesting data, explainable as a system design, relevant to Slingshot's world
(space domain awareness, government + commercial operators). Ideas evaluated, with why OrbitWatch
was chosen and how the others fold in as extensions.

1. **OrbitWatch — live catalogue telemetry and SDA analytics from CelesTrak GP data** *(built)*.
   Public data, no credentials, the customer's vocabulary (regimes, conjunctions, manoeuvres,
   decay), a natural Kafka shape (bulk table → keyed stream → windowed analytics), and enough
   numerics (SGP4) to be technically credible. Risk: none of it is real tracking data — say so.

2. **Conjunction assessment service.** Take screened pairs and compute time of closest approach,
   miss distance and a probability of collision using element-set age as covariance. A natural
   second Streams stage on top of OrbitWatch; the "integration with a vendor-provided capability"
   story if the Pc computation is treated as an external service with a contract.

3. **Multi-source fusion.** Add Space-Track (authenticated GP), CelesTrak supplemental GP (operator
   ephemerides for Starlink/OneWeb), and show reconciliation: which source wins per object, with
   provenance carried on every event. Demonstrates the "consistent, scalable approach to product
   integration" theme directly.

4. **Sensor tasking simulation.** Simulate a small ground-sensor network (visibility windows from
   propagated positions, site geometry) and publish "observation opportunities"; a client requests
   tasking via GraphQL mutations. Adds a write path and a scheduling problem.

5. **Launch and re-entry watch.** Correlate new NORAD ids with launch announcements and
   decay-watch objects with re-entry predictions; a notification integration (email/Slack webhook)
   as the client.

6. **Non-space variant with the same architecture.** ADS-B aircraft feed (OpenSky) or AIS ships:
   identical pipeline shape (position stream → spatial screening → alerts). Useful to show the
   pattern generalises, and as a second source in the same repo.

7. **Digital-twin style replay.** Record a day of state vectors and replay at 10× into the same
   topics for training/what-if — Slingshot's simulation and rehearsal products live here.

Items 2, 3 and 6 are the intended next steps (see REQUIREMENTS_AND_PLAN.md).
