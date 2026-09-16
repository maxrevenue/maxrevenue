# Terminator work queue (items 1–11)

Trunk is `integration/roatz-de09`. This branch stacks on PR #14
(`cursor/live-kill-math-5863`), which stacks on PR #12
(`cursor/tickdecision-arbiter-de09`, including merged PR #13).
Never merge `origin/main`.

CombatState is a **read-model**: published once per tick, never fed by the HUD.
`CombatScript.onTick` now *calls* `TickDecision.decide` and acts on the Intent.
The harness goldens therefore run through the real path, not a parallel model.

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

`TickDecision` is the sole spec/eat arbiter. Item 3 closed big-hit / hardHit
waste: the window is `targetHp <= expectedFinishHp`.

## 2. Live kill math — shipped

| File | Change |
|---|---|
| `MaxHitCalculator.java` | Live str/prayer/stance + combo str bonus; `expectedHit` / `hitChance`; piety only on opponent threat |
| `StateReader.java` | `strengthPrayerMultiplier`, `wornWeaponStrBonus`, `getEquipmentIds`, `getAttack` |
| `CombatScript.refreshPvpVitals` / `estimateOurSpecDamage` | Consume `TickDecision.estimateSpecDamage` (no `agsMaxHit=77`) |
| `test/.../MaxHitCalculatorTest.java` | 99/piety/AGS = 40/55; no prayer = 46; ostr=1 = 5 |

## 3. One kill window — expectedMaxHit shipped

`onTick` builds a `TickDecision.Config` from live flags, captures the same
`CombatState` the HUD will read, calls `TickDecision.decide`, and acts:
EAT → existing survive-eat, SPEC → `executeSpec`, HOLD → nothing.

**Window:** `targetHp ≤ expectedFinishHp` (live spec max × accuracy, NH
floored by `nhKoHp` and capped by `ourMaxHp`), `inActiveFight`, overhead
correct, spec weapon carried. NH also needs Auto Gear **or**
`specWeaponEquipped` (no yank — review note 2b). Mage staff still blocks.
`bighit` / `hardHit` dumps are gone: spec waste outside a window is 0.
Counter-spec remains a named dump. `nhSpecFinishReady` still calls
`killWindowOpen`.

| File | Change |
|---|---|
| `TickDecision.killWindowOpen` | expectedMaxHit + overhead + Auto Gear/`specWeaponEquipped` |
| Goldens | AGS window uses finish HP not 77; bighit/hardHit HOLD; accuracy 0.5 tightens; Auto Gear off + worn spec SPECs |
| `OverlayUI` Auto Spec tooltip | Requires Auto Gear or spec already equipped |

## 4. Data-driven combos — shipped

| File | Change |
|---|---|
| `src/.../combo/Combo.java` | Descriptor: family, executor, energy %, gmaul follow, str bonus, hit formula |
| `CombatScript.SpecWeapon` / `executeSpec` | Dispatch through `Combo.of(selectedSpec).executor` |
| `TickDecision.estimateSpecDamage` | `Combo.specMaxHit` — one table with the window |
| Replay goldens | Each HUD combo SPECs at its own `expectedFinishHp` and HOLDs at +1. Claws still AGS-equivalent |

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
| `CombatScript.onTick` | Capture `CombatState` **once** per tick and pass the snapshot into `runNhTick` / `nhSpecFinishReady` / `nhFinishRange` (review note 3). Do not add more `captureCombatState()` calls before then. |

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
