# ADR-0005: No database — compacted topics are the system of record

**Status:** accepted (demo scope) · 2026-09-10

## Context
Element sets and catalogue metadata are "latest value per key" data; every service needs a copy;
history and acknowledgement are not demo requirements.

## Decision
Element sets and catalogue rows go to compacted topics keyed by NORAD id. Services rebuild state by
reading the topic from the beginning (unique consumer group, `earliest`). Alerts and stats are
time-retained streams; the gateway keeps bounded in-memory rings.

## Consequences
Restarts are cheap and republish nothing; no schema migrations; one fewer component. Missing for a
product: alert history beyond retention, acknowledge/resolve, ad-hoc queries — a Postgres sink fed
by a Streams processor is the planned addition.
