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

`bus` → `orchestrator` → `sidecar` → `telemetry` → tests.

## Review notes (logged)

1. Wire `ttlTicks` is inclusive through `evalTick + ttlTicks` — no local `+1` conversion (`docs/tickbus-wire-contract.md`).
2. Bus eviction uses strict `rank > lowestRank` on ties; test `fullBusRejectsIncomingOnEqualRank`.
3. Sidecar `precondMask == 0` increments `masklessIntents` in NDJSON for golden review.
4. Overlay: fill-then-set double buffer (debug HUD only; documented one-frame tear — **no logic may assume frame consistency**); `TickResolutionSnapshot` shared with NDJSON drops; lease/lag/strings on tick thread only; `tickIndex`/`seq` + `STALE` + sidecar `†` when `bornTick != tickIndex`.

## Flags

| Property | Effect |
|---|---|
| `-Droatz.tickbus=true` | Orchestrator owns dispatch; skips legacy combat tail |
| `-Droatz.tickbus.shadow=true` | Orchestrator logs only; legacy still runs |
| `-Droatz.tickbus.rec=<path>` | NDJSON telemetry output |
| `-Droatz.sidecar=<host>` | Enables sidecar advisor (socket reader TBD) |
| `-Droatz.sidecar.observe=true` | Sidecar frames observed only — never published to bus |
| `-Droatz.overlay=true` | Paint-thread HUD reads `OverlayPublisher` handoff (not the bus) |

## Shadow acceptance

Hook order and **definition of done** are in `docs/tickbus-wire-contract.md` (section **Shadow acceptance**).

**Frontier:** PR #21 instrumentation is ready; **no operator shadow NDJSON capture yet** — parity is unproven until capture + `goldenDiff`.

Capture flags: `-Droatz.tickbus=true -Droatz.tickbus.shadow=true -Droatz.tickbus.rec=<path> -Droatz.overlay=true`

Verdict: `./gradlew goldenDiff -Psession=<path>` → paste MATCH / RULE_DIFF / PRIORITY_DIFF / TIMING_DIFF / FEASIBILITY.

Gate: two stable sessions, `FEASIBILITY=0`, `NoAllocationTest` green, then `-Droatz.tickbus=true` without shadow. RULE_DIFF must match HUD `drop=` lines.

## Related

- [../roatz-echoforge/SKILL.md](../roatz-echoforge/SKILL.md) — dual-repo EchoForge / NDJSON context
