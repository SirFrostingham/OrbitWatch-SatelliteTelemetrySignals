# ADR-0009: A demo that depends on someone else's network is not a demo

**Status:** accepted · 2026-09-10

## Context
The second real run on the operator's machine built and started cleanly, then:

```
ERROR c.orbitwatch.ingest.CelestrakClient : GET https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=json
        failed: Remote host terminated the handshake
INFO  c.o.propagation.PropagationTicker   : catalog empty; waiting for element sets     (every 10 s, forever)
```

The TLS message invites a TLS fix. The logs did not support one. Measured from that machine:
`curl` failed identically with and without `--http1.1`, with `--tlsv1.2 --tls-max 1.2`, with
`--no-alpn` and forced IPv4; `celestrak.com` failed the same way; **plain `http://celestrak.org`
also failed** (HTTP 000); `https://example.com` and `https://github.com` both returned 200. DNS
resolved normally. The same fetch from a cloud host returned 200 — the provider was up. So the host
was being blocked or blackholed on that network, and no client-side TLS setting could change it.

That leaves the real defect visible: with a cold cache, one unreachable third party stopped the
entire system. Every service was healthy, every log line was honest, and there was nothing to look
at. The provider is a *vendor dependency*, and a vendor dependency that can take the product to zero
is a design choice, not bad luck.

## Decision
1. **Seed from a committed snapshot.** `data/` holds a real CelesTrak GP + SATCAT capture, mounted
   read-only at `/data/snapshots`. When a fetch fails *and* there is no cache, ingest publishes from
   the snapshot instead of failing the run (`SnapshotSeed`). Files are found by convention
   (`celestrak-<group>-*.json`, `satcat-*.csv`, newest wins), so refreshing one is a file drop.
2. **Degraded is a state, not a secret.** A seeded run increments
   `orbitwatch_ingest_seed_fallbacks`, logs a WARN naming the file and the cause, and adds a
   `degraded` detail to the ingest health endpoint. Readiness stays UP because the system *is*
   producing (ADR-0008); the operator can see exactly why the data is not moving.
3. **Live stays the source of truth.** The seed is last in the order (cache → stale cache → seed),
   is never written to the cache, and the next scheduled run tries the network again. One successful
   fetch and the seed is out of the picture.
4. **Remove the cheap variables while here.** The HTTP client is pinned to HTTP/1.1 (the JDK default
   negotiates HTTP/2 over ALPN, which is what turns a middlebox problem into a "handshake" message),
   transport failures are retried three times with linear backoff, HTTP status errors are not
   retried, and a failure whose exception carries no message is logged by exception type instead of
   `failed: null`.

## Consequences
- The stack starts and demonstrates end to end on any machine, on a plane, behind any corporate
  proxy — verified in containers with the provider unreachable: 16,506 objects published, catalogue
  loaded, ticks complete, health UP with `degraded` naming the snapshot.
- Demo data can be silently old if nobody reads the alarm — hence the counter and health detail, and
  the runbook entry (this is the discipline from ADR-0008: every watchdog goes on the checklist in
  the same change).
- The snapshot in `data/` is ~7 MB of committed data. Worth it: the repository is self-contained.
- Honest limitation: seeded elements age, so propagation error grows. That is the correct trade-off
  for a demo, and the wrong one for an operational system, where the answer is a second provider
  (Space-Track) and an explicit data-freshness SLO rather than a file in the repository.
