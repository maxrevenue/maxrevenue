# Replay / dry-run

Decisions can be scored without a live client. Packets can be suppressed on a
live attach for observation.

## Dry run

```
-Droatz.dryrun=true
```

`CombatScript.onTick` still runs. `doAction`, spec-orb, interface-click, looter
clicks, and prayer packets are logged as `[DryRun]` instead of sent. Attach-
after-login is unchanged when the flag is unset.

## Record a fight

```
-Droatz.rec=true
```

or an explicit path:

```
-Droatz.rec=D:\pk\ags-gmaul.tsv
```

Off by default. The tick thread never does file I/O (bounded queue + daemon
writer). See **Capture recipe** in `docs/terminator-plan.md` for which fights
to record. There are no captures in git yet; synthetic goldens in
`ReplayHarnessTest` are the baseline.

## Replay

`ReplayHarness.run(List<CombatState>, Config)` seeds `Humanizer`, runs
`TickDecision` — the same function `CombatScript.onTick` calls — and returns:

- action sequence (`SPEC:kill-window`, `EAT:dh-axe`, `HOLD:hold`, …)
- metrics: windows entered / converted / missed / suppressed-by-survival,
  specs fired, spec waste outside a window, wrong-overhead ticks,
  one-shot exposures (latched per bracket), survive eats

`TickTsv.parse` reads a recorder file by header name so extra columns can land
without breaking old rows.

Parity: `TickDecisionParityTest` sets `-Droatz.dryrun=true`, drives the real
onTick arbiter with synthetic `CombatState`s, and asserts the `[DryRun] intent`
log equals the harness sequence.

```
./gradlew test --tests com.sun.java.fontmgr.ReplayHarnessTest
./gradlew test --tests com.sun.java.fontmgr.TickDecisionParityTest
```
