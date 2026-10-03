# ADR-0002: JSON payloads instead of Avro/Protobuf + schema registry

**Status:** accepted · 2026-09-10

## Context
A demo must be readable in Kafka UI and debuggable with `kcat`; a schema registry is one more
component to run and explain.

## Decision
JSON via a shared Jackson configuration (ISO-8601 instants, ignore unknown properties). The
contract module and its tests provide the compatibility discipline the registry would.

## Consequences
~450 B per state vector (~750 kB per tick pre-compression, lz4 on the wire). Revisit when volume
grows 10× or external consumers appear: Avro with the registry, same topic names, `.v2` suffix.
