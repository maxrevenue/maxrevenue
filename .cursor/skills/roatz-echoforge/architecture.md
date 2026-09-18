# Target architecture

The new architecture is a strictly decoupled dual-project system that isolates Java 11 client telemetry collection from Java 17 decision logic using an NDJSON file bridge.

## Component Breakdown

| Component | Environment | Primary Responsibility | Key Elements |
|---|---|---|---|
| RoatzBot | Java 11 | Telemetry recorder & legacy client hook | TickRecorder, reflection sensors, -Rec CLI flag. Emits tick state (target.attackStyle, player.prayers, Chebyshev distance). |
| EchoForge | Java 17 | Standalone, headless behavior-tree engine | BehaviorTreeDecisionEngine, SpecStrategy, EquipNode, PrayerNode. Operates on immutable GameState snapshots. |
| NDJSON Bridge | Cross-JVM | Schema-contract data bus | Serializes/deserializes tick snapshots and action intents across version boundaries without shared class dependencies. |

## Triage & Promotion Pipeline

* **corpus/ Directory**: Holds raw, unvalidated live combat captures. Analyzed via `.\gradlew.bat replayReport` to calculate agreement ratios and output mismatch worklists (traced by raw agent labels like `SWAP_MELEE@52`) without breaking builds.
* **fixtures/ Directory**: Holds curated golden NDJSON files. Enforced rigidly in CI via `GoldenReplayTest` using shared assertable kinds (`EAT`, `SPEC`, `ATTACK`, `EQUIP`, `PRAYER`).
* **Promote Workflow**: `.\gradlew.bat promoteCorpus -Pcorpus=<file>` automatically extracts agreeing ticks from `corpus/` and appends them to `fixtures/` as asserted regression tests.

## Behavior Tree Decision Order

The decision engine evaluates intents in strict priority sequence:

* **Emergency Heal (100)**: Consumes food or brews in lethal HP brackets.
* **Prayer Node (80)**: Toggles protection and matching offensive prayers based on `target.attackStyle`.
* **Spec Strategy (60)**: Evaluates weapon-specific spec logic (`VoidwakerStrategy`, `VlsStrategy`, `AgsStrategy`).
* **Equip Node (40)**: Executes weapon/gear swaps conditioned on Chebyshev `GameState.distanceToTarget()`.
* **Re-attack Node (20)**: Maintains primary combat engagement.

## Remote reality check

As of the PR #18/#19 tips on GitHub:

| Claimed | Remote status |
|---|---|
| Dual Gradle projects | Not on remote — monorepo `core/` source set (#17–#19) or local EchoForge (live-loop HANDOFF) |
| TickRecorder NDJSON + `-Rec` | On #18 / live-loop |
| SpecStrategy set | On #19 |
| EquipNode / PrayerNode | Not on remote (local EchoForge commits only) |
| corpus/ + replayReport + promoteCorpus | Documented in HANDOFF; not in remote Gradle |
| GoldenReplayTest assert kinds | EAT / SPEC / ATTACK / OTHER on #18–#19 (EQUIP/PRAYER not asserted yet) |
| Sample BT leaves | EmergencyEat + SpecDecision + Reattack only |
