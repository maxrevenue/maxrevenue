# Terminator work queue (items 1–11)

Trunk is `integration/roatz-de09`. This branch stacks on PR #9
(`cursor/swap-p0-client-thread-de09` @ `a8e7db7`) because #9 is not merged yet.
Never merge `origin/main`.

CombatState is a **read-model**: published once per tick, never fed by the HUD.
Item 1 scores decisions against that snapshot. Items 3 and 8 are what make
`CombatScript.onTick` *call* the same functions the harness already runs.

No fight captures are in the repo. Synthetic goldens are the baseline corpus.
Real captures need a wider TSV than the old 15 columns (see **Capture recipe**).

---

## 1. Replay + dry run — this PR

| File | Change |
|---|---|
| `src/.../DryRun.java` | `-Droatz.dryrun=true`: decisions run, packets logged not sent |
| `src/.../CombatScript.java` | Gate `doAction` / spec-orb / interface-click; publish `ourHp`/`ourStr` |
| `src/.../LooterApi.java` | Gate `doAction` |
| `src/.../PrayerController.java` | Gate prayer packets |
| `src/.../Humanizer.java` | `seed(long)` / `unseed()` for deterministic replay |
| `src/.../CombatState.java` | Additive: `ourHp`, `ourMaxHp`, `ourStr` |
| `src/.../TickRecorder.java` | Named TSV columns including the new fields |
| `src/.../TickDecision.java` | Current onTick / NH finish / survive rules over `CombatState` |
| `src/.../ReplayHarness.java` | Seeded run → action sequence + metric report |
| `src/.../TickTsv.java` | Parse recorder output by header |
| `test/.../ReplayHarnessTest.java` | Goldens + metrics + TSV round-trip |
| `test/.../HumanizerSeedTest.java` | Seeded gaps are deterministic |
| `docs/replay.md` | Ops: dry-run, record, what to capture |
| `README.md` | Dry-run / rec / capture pointer |

`TickDecision` is extracted from **current** `CombatScript` behaviour (including
spec-on-big-hit waste). Item 3 will tighten the window; the harness is how that
tightening is measured.

## 2. Live kill math

| File | Change |
|---|---|
| `MaxHitCalculator.java` | Equipped str bonus, live str/prayer, accuracy term; drop unconditional piety+hardcoded bonuses |
| `StateReader.java` | Expose str bonus / prayer drain already readable |
| `CombatScript.refreshPvpVitals` / `nhSpecFinishHp` / `estimateOurSpecDamage` | Consume the calculator |
| `test/.../MaxHitCalculatorTest.java` | Known OSRS max hits + drained-str case |

## 3. One kill window, one arbiter

| File | Change |
|---|---|
| `TickDecision.java` | Window iff HP ≤ expectedMaxHit(best finish) with margin, weapon carried, spec funded, in range, overhead correct |
| `CombatScript.onTick` | Spec only when `TickDecision` says SPEC; log suppression reason |
| Goldens | Spec waste outside a window → 0 |

## 4. Data-driven combos

| File | Change |
|---|---|
| `src/.../combo/Combo.java` (new) | Descriptor: spec weapon, energy %, follow-up, condition, tick offsets, max-hit contribution |
| `CombatScript.SpecWeapon` / `executeSpec` | Dispatch through descriptors |
| Replay goldens | AGS/Gmaul, claws, dmace, dbow, VLS, voidwaker identical unless a measured change |

## 5. Canonical overhead

| File | Change |
|---|---|
| `CombatScript.tryNhAutoWalkUnder` / prayer path | Read `lastTargetAnim` + `OpponentLoadout` only |
| Delete parallel `lastTargetAnimation` / `targetAnimationStartTick` / `lastTargetWeaponStyle` | After grep confirms no remaining readers |
| Replay metrics | `wrongOverheadTicks` before/after |

## 6. Survival bracket

| File | Change |
|---|---|
| `TickDecision` survive | Lethal DH/AGS/claws/voidwaker → EAT before SPEC |
| Golden `dh_oneshot_victim` | Must eat; spec suppressed |

## 7. NH reliability

| File | Change |
|---|---|
| `runNhV2System` / `runNhTick` | Freeze re-apply, re-attack after swap, walk-under from canonical state |
| Delete duplicate phase/freeze fields once V2 is the only reader | |

## 8. Decision pipeline + delete legacy NH

| File | Change |
|---|---|
| `CombatScript.onTick` | Ordered producers: survive > overhead > kill window > position > attack; one arbiter |
| Delete `SimpleNH.java`, `nhEnabled` | Only after V2 goldens match |

## 9. Per-tick reflection cache

| File | Change |
|---|---|
| `RtLookup.java` / `PrayerController.java` | Resolve `prayer()` / `isPrayerActive` / `liveProtectPrayerId` once; WARN if a handle disappears |

## 10. Fail-visible catches

| File | Change |
|---|---|
| Tick-path empty catches in `CombatScript` / `PrayerController` / `FontManager` / `StateReader` / `LooterApi` | Increment a counter |
| `FontManager` LOG/STATE | Expose the counter |

## 11. Human inv-gap distribution

| File | Change |
|---|---|
| `Humanizer.invGapMs` / `firstEquipDelayMs` | Distribution, still ≥ 72 ms AhkDetection floor |
| `README.md` | Stealth note: defense-in-depth, not a guarantee |

## Release blocker (own commit, not combat)

| File | Change |
|---|---|
| `LicenseToken.java` | `DEV_EXAMPLE_PUBLIC_KEY_B64`, fingerprint |
| `build.gradle.kts` `dist` / `jpackageImage` | Fail if compiled key == example unless `-PallowDevLicenseKey=true`; write fp into `Roatz.cfg` |

---

## Capture recipe (needed before a live corpus)

There are **no** recorded fights in git. Synthetic goldens cover the shapes
below. To replace them with real ticks:

1. Attach with `-Droatz.rec=true` (or `-Droatz.rec=D:\path\fight.tsv`).
2. Optional: `-Droatz.dryrun=true` for a decision log without packets (still
   needs a logged-in client to *observe*).
3. Record one fight of each type, 20–40 ticks, then `TickRecorder` off:

   | File name | What to do in-game |
   |---|---|
   | `dh-oneshot.tsv` | Opponent on DH greataxe, stacked, swinging at us |
   | `ags-gmaul.tsv` | Us AGS→Gmaul on a KO-range target |
   | `claws-gmaul.tsv` | Same with claws setup |
   | `dds.tsv` | Opponent DDS spec incoming |
   | `opp-spec.tsv` | Opponent AGS/claws/voidwaker spec |
   | `nh-freeze.tsv` | NH V2 freeze → range → melee finish |
   | `eats.tsv` | Marlin / brew / karambwan in a lethal bracket |

4. Drop TSVs under `test/com/sun/java/fontmgr/replay/corpus/` (not in this PR).
   Header must match `TickRecorder.COLUMNS`.
