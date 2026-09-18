# Integrating EchoForge into RoatzBot

## Option A — Merge monorepo #17→#18→#19 (`-Droatz.v2engine`)

**Use when:** experimenting headless / JVM 17+ attach only.

**Merges:** `core/**`, `ReflectionStateSensor`, `ReflectionActionDispatcher`, TickRecorder/#18 deltas, fixtures, Gradle `core`/`coreTest` + bundle into `agentJar`.

**Runtime:** `-Droatz.v2engine=true` early-returns from legacy `onTick` into `CombatCoordinator`.

**Risk:** High — Java 17 bytecode in Java 11 client. Also conflicts with terminator `onTick`.

**Still needed:** PrayerNode, EquipNode, corpus promote, `--release 11` fix from live-loop.

## Option B — Dual-repo (recommended near-term)

**RoatzBot:** recorder + live combat. **EchoForge:** own Java 17 project. Bridge = NDJSON file.

**Land on RoatzBot:** live-loop / #18 recorder quality, `--release 11`, `launch.ps1 -Rec`. Do not re-add `core/` to agent JAR.

**Land on EchoForge:** #17/#19 `com.automation.core`, then Prayer/Equip/promote from local HANDOFF work.

**Risk:** Low for client. Live PK still uses monolith or terminator until IPC exists.

## Option C — Hybrid (recommended long-term)

Same as B now. Later: sidecar JVM 17 + wire schema; agent maps intents with Java 11 DTOs only.

## Recommended sequence

1. Trunk = `integration/roatz-de09`
2. Land recorder (#18 / live-loop) with `--release 11`
3. Merge terminator for live `TickDecision` (resolve `CombatScript` + `TickRecorder`)
4. Do not merge #19 JAR bundle into client path; retarget engine PRs to EchoForge if splitting
5. Publish EchoForge remote; add PrayerNode / EquipNode / promoteCorpus / replayReport
6. Fresh corpus only (pre-sticky-label-fix captures are worthless)
7. Design live IPC only after goldens are trustworthy

## Conflict surfaces

When merging terminator × live-loop / #18:

- `src/com/sun/java/fontmgr/CombatScript.java`
- `src/com/sun/java/fontmgr/TickRecorder.java`
- related agent tests (`ClientThreadGuardTest`, recorder tests)

## Flags

| Flag | Meaning |
|---|---|
| `-Droatz.rec=<path\|true>` | Enable NDJSON TickRecorder |
| `launch.ps1 -Rec <abs>` | Attach with recording + license bypass |
| `-Droatz.v2engine=true` | PR #19 in-process BT (do not use on J11 client) |
