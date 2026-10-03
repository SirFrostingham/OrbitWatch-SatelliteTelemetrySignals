# ADR-0003: In-house SGP4/SDP4 port validated against the reference implementation

**Status:** accepted · 2026-09-10

## Context
Options: Orekit (industry grade, ~30 MB data bundle, large API), a small third-party SGP4 jar (none
maintained on Maven Central), or a port of Vallado's reference code.

## Decision
Port the reference (Vallado's public-domain C++, via the MIT-licensed Python `sgp4` package's transcription) to
~700 lines of dependency-free Java, including the deep-space branch. Validate with generated
reference vectors: 63 live objects across LEO/MEO/GEO/HEO/graveyard, nine epochs, 1 m / 1 mm·s⁻¹
tolerance. Keep the generator script in `tools/`.

## Consequences
No heavyweight dependency, allocation-free hot path, a regression test that would catch any
numerical drift. Known limits are those of SGP4 itself (km-class accuracy, degrading with
element-set age); TEME→ECEF ignores polar motion (< 1 km, inside the SGP4 error budget).
