# RoatzBot / EchoForge — handoff

Everything an agent needs to pick this up cold. Written 2026-09-17 after the
telemetry bridge was made to work end to end.

---

## 1. What this is

Two repos that together form an OSRS PK bot whose combat brain can be tested
without a game client.

| Repo | Path | Role |
|---|---|---|
| **RoatzBot** | `C:\Users\Alec\Desktop\RoatzBot` | Java 11 instrumentation agent attached to the Roat PKz client. Fights, and **records** every tick as NDJSON. |
| **EchoForge** | `C:\Users\Alec\Desktop\EchoForge` | Java 17 standalone project. The decision **engine** + a golden-master replay harness. No client, no `game.jar`, no dependency on RoatzBot. |

The only bridge between them is the **NDJSON file**. RoatzBot writes it;
EchoForge replays it. Nothing else crosses, deliberately: the engine is Java 17
and the client is Java 11, so they cannot share bytecode (see §5).

```
agent onTick ──► TickRecorder ──► corpus/*.ndjson ──► NDJSONFixtureLoader
                                                           │
                                              BehaviorTreeDecisionEngine
                                                           │
                                        compared against the recorded action
```

---

## 2. Current state (verify these before trusting anything below)

| | RoatzBot | EchoForge |
|---|---|---|
| Branch | `cursor/echoforge-live-loop-3f6a` | `main` |
| HEAD | `e0130dc` | `b82a645` |
| Remote | `github.com/maxrevenue/maxrevenue` (pushed) | **none** — local only |
| Tests | **40 / 0** (`gradlew test`) | **78 / 0** (`gradlew test`) |
| Extra gate | `gradlew verify` → 262 / 12 / 38 / 72, `HARNESS: PASS` | — |

Agent jar: `build/fontmanager-windows.jar`. Product version is still `1.0.8`;
use the launcher's `Agent OK (… KB, <timestamp>)` line as the real build marker.

### ⚠️ The Roatz work exists on TWO branches that are NOT merged

| Branch | Contains | Missing |
|---|---|---|
| `origin/integration/terminator-de09` (`cae11ea`) | `TickDecision`, `ReplayHarness`, `DryRun`, `combo/Combo` — live kill math, kill window, combo dispatch | the EchoForge engine |
| `cursor/echoforge-live-loop-3f6a` (current) | EchoForge engine, recorder telemetry, the split, corpus tooling | `TickDecision` / `Combo` / kill math |

Both descend from `origin/roatz`. **Merging them is the single most important
structural task.** Decide which is the trunk and merge the other in.

---

## 3. Landmines — read before touching anything

**`--release 11` is mandatory.** `build.gradle.kts` sets
`options.release.set(11)`. Do **not** revert to
`sourceCompatibility`/`targetCompatibility`; that only picks the bytecode
version while still compiling against the build JDK. A JDK 21 build once emitted
`MappedByteBuffer.duplicate()Ljava/nio/MappedByteBuffer;`, which does not exist
on the client's Java 11 — `NoSuchMethodError` every tick, 6,524 of them, and the
recorder wrote nothing for days while the HUD looked healthy.
`Java11ApiBoundaryTest` guards the known divergent family.

**`verify` cannot catch Java-11 API bugs.** The harness runs on Java 21. That is
why the boundary test exists. If you add anything touching `java.nio`,
`java.util`, or records/sealed, check it against Java 11 explicitly.

**`origin` hosts other products.** `origin/main` is *Pivex*, unrelated. The
Roatz line is `origin/roatz`. Never merge `origin/main`.

**The client's working directory is not the repo.** A relative `-Rec` path
resolves against the client JVM's CWD. Always pass an absolute path.

**Recording requires `launch.ps1`.** It sets `-Dfontmgr.license.bypass=true` on
the game JVM. A client started any other way (official launcher, a second
client) has no bypass and the agent will abort at the license gate — attach
`loadAgent` still returns 0, so it looks successful. `launch.ps1 -Attach` now
reads `%TEMP%\.cache\fontconfig-attach.status` and reports an internal abort.

**Ticks only exist once you are logged in and in the world.** At the login
screen the client's counters do not advance. `launch.ps1` watches the recording
for ~30s after attach and says so explicitly.

**`corpus/` is never asserted; `fixtures/` always is.** Never drop a raw capture
into `fixtures/` — one disagreeing tick fails the build. `gradlew promoteCorpus`
is the only sanctioned path from one to the other, and it only copies ticks that
are assertable *and* already agree.

**The Ed25519 dev key is still in the source.** `LicenseToken.ED25519_PUBLIC_KEY_B64`
is the example key whose private half is committed in
`license-server/.dev.vars.example`. `dist` refuses to build with it unless
`-PallowDevLicenseKey=true`. **Rotate before any release** — otherwise anyone
with the repo can mint licenses.

---

## 4. What is done (chronological, condensed)

**Security**
- `75d16f9` HMAC license tokens → **Ed25519** (the shared secret was committed in
  a public repo).
- `a8818a2` `dist` fails while the compiled key is still the example; fingerprint
  written into `Roatz.cfg`.

**Agent correctness**
- `bf69c95` client mutations drained on `GameEngine.clientTick`, not the
  agent-tick daemon. `UiExecutor` became a deadline facade.
- `f6b3c5e` exactly one cycle alias hooked (was hooking every match).
- `040e687` **`TickEngine` uses `serverTick` exclusively while it exists.** It
  used to fall through to the `tick/30` cycle counter between server ticks,
  interleaving two clocks (fixed offset 23) → 14 backward and 12 duplicate tick
  numbers in 224 rows.
- `b6e4424` + `e0130dc` **recorder action accuracy**: `lastAction` is sticky, so
  one eat at tick 791 stamped 84 later rows `EAT:`; `AUTO_EAT_OFF` (a toggle)
  scored as an eat. Actions are now only reported for the tick they happened on,
  and `actionLabel` keeps `lastAction` verbatim for provenance.
- `c3a6dda` **`p:augury` / `p:rigour`** activate the prayer named, not the first
  candidate for its style (they were silently downgraded to Mystic Might /
  Eagle Eye).
- `914b257` `launch.ps1` `$found[-1]` on a PowerShell scalar returned the last
  *character* (`8`, not `1.0.8`) → false "STALE AGENT".
- `4957fc3` **the Java 11 `--release` outage** (see §3), plus `publishState()`
  moved ahead of the optional shared-memory publish.
- `1b56b78` an aborted agent writes the reason into the recording path instead of
  leaving 0 bytes.
- `cd4772c` every recording starts with `# tick engine ok (tick=…, serverTick=…)`.

**Decision engine (terminator line, item 1–4)**
- `df553b9` `ReplayHarness` + goldens scoring kill conversion headlessly.
- `40470c0` `TickDecision` is the sole spec/eat arbiter consulted by `onTick`.
- `be2f2fa` live kill math (str/prayer/stance/combo bonus), `38d6621` kill window
  = `expectedMaxHit`, `b486dd1` data-driven `Combo`.
- `d0a82f7` claws bonus inversion, Statius family fix, `ConversionTrade` sweep —
  measured **29 HP of kills left on the table** by the primary-only window.

**EchoForge**
- `55a9996`/`b1fdc80`/`241b962` engine, recorder, spec strategies.
- `33e39c5` `EquipNode` + `PrayerNode`; strict order
  `EMERGENCY_HEAL > PRAYER > SPECIAL_ATTACK > GEAR > ATTACK`.
- `91724c2` corpus triage: `gradlew replayReport` (metrics only, never fails) and
  `gradlew promoteCorpus` (agreeing ticks → asserted goldens).

---

## 5. What is open, ranked

1. **No usable corpus exists.** Two captures on disk, both worthless:
   - `eclipse-atlatl.ndjson` (1358 rows) — pre-clock-fix stamps, 0 tick-accurate actions.
   - `ags-fight.ndjson` (666 rows) — recorded before provenance was restored, 664 empty labels.
   Take a fresh capture with `e0130dc` or later. Then look at the `actionLabel`
   column first: real labels (`PLAYER_ATK@…`, `KO_SPEC@…`) mean the agent was
   working; all-empty means the bot did nothing during the fight and *that* is
   the bug to chase.
2. **Merge the two Roatz branches** (§2). Nothing else should start before this,
   or work will fork again.
3. **Capture the snapshot before acting.** `publishState()` runs at the end of
   the tick, so `player.prayers` already reflects `executedAction` — the engine
   sees "missiles already up" and asks for Rigour on a tick where the agent *put
   missiles up*. Cheap fix, improves every tick.
4. **The behavior tree is stateless; the agent is not.** On consecutive identical
   snapshots the engine re-eats where the agent had already moved on. It needs a
   decision session (cooldowns, "already ate"), like `TickDecision.Session`.
5. **Predictive / band eating.** The agent eats at 52–56% (`NH_PREDICTIVE_BREW`);
   the engine has one fixed 50% threshold. This is the `TripleEatNode` + threat
   model work. The engine also has **no max-hit/threat model at all** and
   `EatAction` has no food/brew/karambwan category.
6. **`ClawsStrategy`** is a placeholder (`CLAWS_AS_AGS`). Blocked on telemetry:
   the recorder does not record hitsplat count or sequence, so 4-hit timing is
   underivable from any recording. Extend the recorder first.
7. **`WalkUnderNode`** needs a movement intent type (none exists) and relative
   tile positions (only a scalar `distance` is recorded).
8. **EchoForge has no remote.** `git remote add origin …`.
9. **`corpus/*.ndjson` is not gitignored**, so every recording shows up as git
   noise (`git status` currently lists `ags-fight.ndjson` untracked and
   `eclipse-atlatl.ndjson` modified). Add `corpus/*.ndjson` to `.gitignore` with
   a `!corpus/synthetic-smoke.ndjson` exception.
10. **Rotate the Ed25519 key before release** (§3).

---

## 6. Commands

### Build and verify (RoatzBot)

```powershell
cd C:\Users\Alec\Desktop\RoatzBot
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test buildAll
.\gradlew.bat verify            # needs game.jar in the repo root (present locally, not in CI)
```

### Run the bot / record (needs the client)

```powershell
.\launch.ps1 -Attach -Rec C:\Users\Alec\Desktop\EchoForge\src\test\resources\corpus\fight-01.ndjson
# log in, get IN THE WORLD, fight, then QUIT the client to flush
```

The launcher stops old clients, rebuilds, launches vanilla, waits for Enter to
attach, then watches the recording for ~30s and reports the live tick count.

### Triage and promote (EchoForge)

```powershell
cd C:\Users\Alec\Desktop\EchoForge
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test              # 78 tests
.\gradlew.bat replayReport      # agreement ratio + mismatch worklist, never fails
.\gradlew.bat promoteCorpus -Pcorpus=fight-01.ndjson   # agreeing ticks -> fixtures/ (asserted)
```

### Inspect a recording

```powershell
# health line every recording starts with
Get-Content <file>.ndjson -TotalCount 1
# what the agent was doing, per tick
Select-String -Path <file>.ndjson -Pattern 'actionLabel' | Select-Object -First 5
```

---

## 7. File map

### RoatzBot (`src/com/sun/java/fontmgr/`)

| File | Notes |
|---|---|
| `FontManager.java` | premain/agentmain, bootstrap order, license gate, tick-engine wiring |
| `CombatScript.java` | 8.4k lines — the tick brain. `onTick`, `publishState`, `recordedAction` |
| `TickEngine.java` | tick poll. **Single clock source** — read the comment before editing |
| `TickRecorder.java` | NDJSON writer. `compactExecutedAction`, `formatNdjson`, abort note |
| `PrayerController.java` | prayers. `ensureOffensivePrayer` (auto) vs `ensureSpecificOffensivePrayer` (named) |
| `StateReader.java` | reflection reads, `distanceTo`, publishes to shared memory |
| `SharedMemory.java` | seqlock MMF snapshot. Where the Java 11 `--release` bug lived |
| `ClassFilePatcher.java` / `HardcodedCombatAgent.java` | ASM patches: telemetry scrubbing, mouse hooks, one tick hook |
| `LicenseToken.java` / `LicenseGate.java` | Ed25519 verify; `LicenseGate.bypassEnabled()` |
| `ClientThreadGuard.java` | client-thread dispatch. `pump()` is the sole marker |

### EchoForge (`src/`)

| Path | Notes |
|---|---|
| `main/.../model/` | `GameState`, `PlayerState`, `ActionIntent` (sealed), `Combo`, actions |
| `main/.../engine/` | `BehaviorTreeDecisionEngine`, `Selector`, `Sequence` |
| `main/.../engine/nodes/` | `EmergencyEatNode`, `SpecDecisionNode`, `EquipNode`, `PrayerNode`, `ReattackNode` |
| `main/.../engine/spec/` | `SpecStrategy` + AGS/Voidwaker/VLS/fallback |
| `test/.../harness/` | `NDJSONFixtureLoader`, `ActionExpectation`, `GoldenReplayTest`, `TriageReport` |
| `test/resources/fixtures/` | **asserted** goldens |
| `test/resources/corpus/` | **never asserted**; raw captures + `synthetic-smoke.ndjson` |

---

## 8. Test suites

| Suite | What it pins |
|---|---|
| `Java11ApiBoundaryTest` | no `MappedByteBuffer.duplicate/slice` covariant calls in agent bytecode |
| `ClientThreadGuardTest` | single clock source; listener-before-start; client-thread marker |
| `TickRecorderActionTest` | action vocabulary, non-sticky labels, raw-label provenance |
| `PrayerRequestTest` | named prayer wins; candidate list only as fallback |
| `ClassFilePatcherTickHookTest` | one cycle alias hooked, hook is the first instruction |
| `LicenseTokenTest` | Ed25519 sign/verify, expired, tampered, wrong HWID; v1 rejected |
| EchoForge `GoldenReplayTest` | engine top intent == recorded action, per fixture |
| EchoForge `TriageReportTest` | agreement counting, promotion filtering, corpus/fixture separation |

---

## 9. Two habits that saved this project

1. **Read the agent's own log.** `-Rec` implies `-Dagent.filelog=true`; the log is
   `%TEMP%\.cache\jvm-cache-log.dat`. Every real root cause so far (license
   denial, `NoSuchMethodError` every tick, sticky labels) was only visible there.
   A silent empty file tells you nothing; the log tells you everything.
2. **Measure before fixing.** Several "obvious" engine failures turned out to be
   artifacts of the recorder (sticky labels inflated 6 real disagreements into
   214). `replayReport` scores the *engine* against a recording; the recording's
   own quality is a separate question — check `actionLabel` density before
   drawing conclusions from the ratio.
