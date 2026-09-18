---
name: roatz-tickbus-orchestrator
description: Implements the zero-allocation LiveTickOrchestrator, TickBus, Advisor pipeline, async EchoForge sidecar wire protocol, and off-thread NDJSON telemetry for RoatzBot Java 11. Use when building tick bus arbitration, sidecar frames, suppression leases, GoldenReplayTest round-trip, or refactoring CombatScript to a single onTick owner.
---

# Zero-Allocation TickBus Orchestrator

Read and follow the full implementation spec verbatim:

- [implementation-prompt.md](implementation-prompt.md)

## Quick invariants

1. Tick time = `GameState.tickIndex` only in `com.bot.core.bus` and `Advisor.evaluate`.
2. Zero allocation on tick thread — intent pools, no varargs channels, no `bus.snapshot()` on tick thread.
3. `LiveTickOrchestrator` is the only reflection tick hook consumer.
4. Fingerprints: `(state & mask) == (hash & mask)`, not equality.
5. Sidecar wire: 40-byte frames with `ackTick` (WIRE_V1/V2 if needed).
6. No Java 17 EchoForge classes in the agent JAR.

## Implementation order

`bus` → `orchestrator` → `sidecar` → `telemetry` → tests. Pause after `bus` for review unless the user asks to continue.

## Related

- [../roatz-echoforge/SKILL.md](../roatz-echoforge/SKILL.md) — dual-repo EchoForge / NDJSON context
