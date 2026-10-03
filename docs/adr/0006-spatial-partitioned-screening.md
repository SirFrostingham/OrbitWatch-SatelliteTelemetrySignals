# ADR-0006: Conjunction screening via spatial hash, with the cell as the Kafka key

**Status:** accepted · 2026-09-10

## Context
All-vs-all screening is O(N²): ~136 M pair checks per tick at 16.5k objects, and it cannot be
parallelised across Kafka partitions if every pair might be needed.

## Decision
Bucket positions into cubes with side ≥ threshold (50 km / 10 km). In-memory: compare within cell
and the 26 neighbours (exact). In Streams: publish each vector to its own cell plus any adjacent
cell within the threshold ("ghosts", 1–4 keys), window per tick per cell, screen each closed
bucket locally, report a pair only in the own cell of the lower id (exactly once). Pairs under
10 m are treated as one physical object (docked / co-catalogued).

## Consequences
Per-partition parallelism, tiny state per key, exactness proven by `ConjunctionScreenerTest`
(grid = brute force; ghosting never loses a pair). Screening only — a real assessment would refine
TCA and use covariance.
