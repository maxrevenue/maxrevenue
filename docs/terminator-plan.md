# Terminator work queue (items 1–11)

Trunk is `integration/roatz-de09`. This branch stacks on PR #11
(`cursor/replay-dryrun-de09` @ `df553b9`), which itself stacks on PR #9
(`cursor/swap-p0-client-thread-de09` @ `a8e7db7`). Neither is merged to
integration yet. Never merge `origin/main`.

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

`TickDecision` is extracted from **current** `CombatScript` behaviour (including
spec-on-big-hit waste). The arbiter wiring (item 3, this PR) makes onTick call
that function. Tightening the window so waste goes to 0 is the remainder of
item 3.

## 2. Live kill math

| File | Change |
|---|---|
| `MaxHitCalculator.java` | Equipped str bonus, live str/prayer, accuracy term; drop unconditional piety+hardcoded bonuses |
| `StateReader.java` | Expose str bonus / prayer drain already readable |
| `CombatScript.refreshPvpVitals` / `nhSpecFinishHp` / `estimateOurSpecDamage` | Consume the calculator |
| `test/.../MaxHitCalculatorTest.java` | Known OSRS max hits + drained-str case |

## 3. One kill window, one arbiter — arbiter shipped; window still today's

`onTick` builds a `TickDecision.Config` from live flags, captures the same
`CombatState` the HUD will read, calls `TickDecision.decide`, and acts:
EAT → existing survive-eat, SPEC → `executeSpec`, HOLD → nothing. Scattered
spec branches (outBigHit / oppSpec dump / ko / hardHit / `nhFireSpecFinish` /
`tryAutoSpecDump` / `tryEnqueueKillTickIfReady`) no longer decide to spec —
the dump and kill-tick methods are deleted. `LiveDecisionConfigRoundTripTest`
under `-Droatz.dryrun=true` checks `applyCfg` → `liveDecisionConfig()` and
that `applyTickDecision` emits the `[DryRun] intent` line. Shared-function
coverage with full `onTick` is the code sharing, not that test.

**Window that actually shipped** (not yet the tight KO formula):

- PK: `inKillRange && inActiveFight`, funded, not busy, not cooling.
- NH: Auto Gear **on**, `targetHp ≤ nhSpecFinishHp` where the HP cap is
  `max(nhKoHp, estimateSpecDamage(s.ourStr > 0 ? s.ourStr : cfg.ourStr))`
  clipped by `ourMaxHp` when readable; spec weapon carried; not on a mage staff.
  Auto Gear **off** (default) closes the NH window — `ed2f83c`. `nhSpecFinishReady`
  calls `TickDecision.killWindowOpen` / `nhSpecFinishHp` so the melee switch
  cannot disagree with the arbiter.
- Additional SPEC paths that still exist (known waste): fresh outgoing splat
  ≥ `damageTriggerMin` (`bighit`), fresh incoming splat ≥ that (`hardHit`).
- Survive still wins: DH axe / opponent spec eat before any SPEC.

| File | Change |
|---|---|
| `TickDecision.java` | Fidelity: fresh-splat, `inActiveFight`, hardHit, NH weapon/staff, `ourStr`, `nhAutoGear` |
| `CombatScript.onTick` | Sole spec/eat arbiter; `captureCombatState` shared with publish |
| `ReplayHarness.java` | `oneShotDeaths` latches per exposure; `windowsSuppressedBySurvival` |
| `test/.../LiveDecisionConfigRoundTripTest.java` | Flag round-trip + DryRun intent line (not full onTick) |
| Goldens | Bighit waste still fires on a *fresh* splat; stale splat HOLDs; drained `ostr` shrinks the NH window; Auto Gear off HOLDs in RANGE |

Remainder of item 3 (later): window iff HP ≤ expectedMaxHit(best finish) with
margin and overhead correct; spec waste outside a window → 0. That change
needs its own golden.

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
