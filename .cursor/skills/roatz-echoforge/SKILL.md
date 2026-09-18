---
name: roatz-echoforge
description: Guides RoatzBot / EchoForge dual-project work — Java 11 agent telemetry, Java 17 behavior-tree engine, NDJSON bridge, PR #17–#19 stack, and safe integration into RoatzBot. Use when working on Roatz, EchoForge, TickRecorder, BehaviorTreeDecisionEngine, golden replay, corpus promotion, SpecStrategy, or combat Sense-Think-Act.
---

# Roatz / EchoForge

## Hard rules

1. **Trunk** is `integration/roatz-de09`. Never merge `origin/main` (Pivex).
2. **Client JVM is Java 11.** Agent compile must use `options.release.set(11)` — not only `sourceCompatibility`/`targetCompatibility`.
3. **Do not bundle Java 17 `com.automation.core` into the agent JAR** for live attach. PR #19’s `-Droatz.v2engine` in-process path is contested; prefer NDJSON / sidecar.
4. **`corpus/` is never asserted; `fixtures/` always is.** Promote only via `promoteCorpus` when that task exists.
5. Absolute paths for `-Rec` recordings (client CWD ≠ repo).

## Architecture (target)

Strictly decoupled dual-project system:

| Component | Env | Responsibility |
|---|---|---|
| **RoatzBot** | Java 11 | Telemetry recorder & legacy client hook — `TickRecorder`, reflection sensors, `-Rec`. Emits tick state (`target.attackStyle`, `player.prayers`, Chebyshev distance). |
| **EchoForge** | Java 17 | Standalone headless behavior-tree engine — `BehaviorTreeDecisionEngine`, `SpecStrategy`, `EquipNode`, `PrayerNode`. Operates on immutable `GameState` snapshots. |
| **NDJSON bridge** | Cross-JVM | Schema-contract data bus. No shared class dependencies across version boundaries. |

Full priority ladder and triage pipeline: [architecture.md](architecture.md).

## Branch / PR map

| Piece | Location |
|---|---|
| Product trunk | `integration/roatz-de09` |
| Older product tip | `origin/roatz` (behind integration) |
| Engine bootstrap | PR [#17](https://github.com/maxrevenue/maxrevenue/pull/17) `cursor/combat-sense-think-act-engine-3662` |
| NDJSON + goldens | PR [#18](https://github.com/maxrevenue/maxrevenue/pull/18) `cursor/echoforge-tick-oracle-b578` |
| Spec + reflection bridge | PR [#19](https://github.com/maxrevenue/maxrevenue/pull/19) `cursor/phase3-spec-bridge-000a` |
| Recorder-only / split | `cursor/echoforge-live-loop-3f6a` (+ local EchoForge per HANDOFF) |
| Live kill math (parallel) | `integration/terminator-de09` (`TickDecision` / `Combo`) |

Stack ancestry: **#17 → #18 → #19**.

## Decision paths (do not confuse)

```text
CombatScript.onTick
 ├─ legacy monolith          ← integration trunk today
 ├─ TickDecision EAT|SPEC|HOLD ← terminator-de09
 └─ USE_V2_ENGINE → CombatCoordinator BT ← PR #19 only (J17-in-JAR risk)
```

Sample BT that actually runs on #19 tip: EmergencyEat → SpecDecision → Reattack.  
`ActionPriority` enum has Prayer(80)/Gear(40), but **`PrayerNode` / `EquipNode` are not on remote** (local EchoForge only per HANDOFF).

## How to add EchoForge to RoatzBot

### Recommended (near-term): dual-repo + recorder

1. Land **#18 recorder surface** (or live-loop refinements) on `integration/roatz-de09`:
   - `TickRecorder` NDJSON, `CombatScript.publishState`, `launch.ps1 -Rec`
   - Enforce `--release 11` + `Java11ApiBoundaryTest` if present
2. Keep **`core/` out of `agentJar`**
3. Put #17/#19 engine (+ Prayer/Equip/promote) in **EchoForge** (own repo / own JVM)
4. Optionally merge **terminator** for live combat; resolve conflicts in `CombatScript` + `TickRecorder`
5. Capture fresh fights → triage → promote agreeing ticks to fixtures

### Avoid for live client: merge #19 bundle as-is

Merging #17→#18→#19 with `from(sourceSets["core"].output)` into the agent JAR loads major-61 bytecode on Java 11 → `UnsupportedClassVersionError` risk. Do not enable `-Droatz.v2engine` on the client JVM until a sidecar/IPC design exists.

### Later: hybrid live

Sidecar JVM 17: agent sends tick snapshots; EchoForge returns intents; thin Java 11 dispatcher maps wire DTOs (no `com.automation.core` on client classpath).

## PR landing cheat sheet

| PR | Into RoatzBot | Into EchoForge |
|---|---|---|
| #17 core engine | No (not in agent JAR) | Yes |
| #18 TickRecorder + fixtures | Yes (recorder + optional fixture copies) | Yes (harness) |
| #19 SpecStrategy + reflection | Specs → EchoForge; bridge only via IPC later | Yes |

## Key paths

**RoatzBot:** `src/com/sun/java/fontmgr/` (`CombatScript`, `TickRecorder`, swap/, overlay/), `launch.ps1`, `build.gradle.kts`, `launcher/`, `license-server/`

**EchoForge (monorepo form on #17–#19):** `core/src/main/java/com/automation/core/` — `engine/`, `engine/spec/`, `model/`, `coordinator/`, `harness/` under `core/src/test/`

## Validation

- Agent: `./gradlew test` and `./gradlew verify` (needs `game.jar` locally)
- Engine: `./gradlew coreTest` (when `core/` source set present)
- Record: `.\launch.ps1 -Attach -Rec <absolute>.ndjson`

## Additional resources

- [architecture.md](architecture.md) — target architecture, BT order, corpus/fixtures pipeline
- [integration.md](integration.md) — merge options, conflict surfaces, recommended sequence
