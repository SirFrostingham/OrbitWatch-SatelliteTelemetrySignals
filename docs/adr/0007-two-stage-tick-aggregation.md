# ADR-0007: Two-stage aggregation for per-tick statistics (hot key)

**Status:** accepted · 2026-09-10

## Context
`groupBy(tick)` → windowed aggregate routes 16.5k records per tick to one partition and one thread
with a state-store read-modify-write each; observed 100k-record lag on a two-core host.

## Decision
Stage 1: a processor on every source partition folds vectors into an in-memory accumulator per
tick and flushes one partial per tick on wall-clock punctuation (tick + interval + grace). Stage 2:
a one-partition repartition topic and a merge processor emit once per tick and drop stragglers.

## Consequences
Per-record cost is a map update; the hot partition handles six records per tick instead of 16.5k.
Trade-off: in-memory partials are lost on crash (regenerated next tick) — acceptable for derived,
recomputable data. Documented as the canonical example of "measure, then change the topology".
