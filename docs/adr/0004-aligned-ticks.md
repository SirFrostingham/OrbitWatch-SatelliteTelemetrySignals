# ADR-0004: Propagation ticks are aligned to wall-clock multiples

**Status:** accepted · 2026-09-10

## Context
Analytics windows need all objects at the same instant; multiple propagation instances (sharded or
active/standby) must not produce skewed instants; restarts must not create irregular ticks.

## Decision
`tick = floor(now / interval) * interval`. The tick is carried in every state vector and used as
the event timestamp in Streams. A duplicate tick (standby, replay) is a duplicate, not a new
instant.

## Consequences
Sharding by NORAD id across instances is trivial; windows are exactly one tick wide; the gateway
can key its stats ring by tick and replace rather than append.
