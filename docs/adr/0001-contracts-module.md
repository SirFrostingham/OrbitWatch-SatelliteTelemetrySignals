# ADR-0001: Topic names, policies and record types live in one versioned `contracts` module

**Status:** accepted · 2026-09-10

## Context
Four services exchange events. Drift between producer and consumer DTOs, and between what a topic
is assumed to be (table vs stream) and how it is provisioned, is the most common way event-driven
integrations rot.

## Decision
A single Maven module holds topic constants (`Topics`), provisioning specs with compaction and
retention (`TopicSpecs`), the JSON record types, the shared `ObjectMapper` and the serde. The
producing service registers the `NewTopic` beans for the topics it owns. Versioning: additive
optional fields are free; a breaking change is a new `.v2` topic plus a bridge consumer.
Contract tests assert round-trip and unknown-field tolerance.

## Consequences
One artifact to bump; compatibility is a code review of one module; the topic table in
ARCHITECTURE.md is generated from the same names. Cost: a shared dependency across services
(acceptable — it is deliberately tiny and framework-free).
