# TickBus / EchoForge Schema v2 — Invariant Audit

**Repository:** [maxrevenue/maxrevenue](https://github.com/maxrevenue/maxrevenue)  
**Branch / PR:** `cursor/tickbus-orchestrator-bus-bae7` → [#21](https://github.com/maxrevenue/maxrevenue/pull/21) (base `integration/roatz-de09`)  
**As of:** 2026-09-18  
**Scope:** Code review against frozen **TickBus / EchoForge Sidecar Wire Contract (schema v2)** — correctness, thread safety, zero-allocation on the tick thread.

**Related docs:** `docs/tickbus-wire-contract.md`, `docs/fingerprint-registry.md`, `docs/roatz-architecture-shareable.md`

---

## Executive summary

The **main shadow path** (vitals refresh → `evaluateEarly` → legacy monolith → `publishState` / `recordShadowLegacy`) matches the contract. **Suppression early-release**, **fingerprint bit layout**, **NDJSON orch/legacy shape**, and **`goldenDiff` / `ParityInput` isolation** are aligned with schema v2.

Gaps to treat as **capture noise or follow-up fixes**, not blockers for a first operator shadow run:

- Early `onTick` returns that **skip orchestrator** but still emit legacy lines → **`TIMING_DIFF`**
- **`wireRejects`** reset + reader/tick thread races → under-reported sidecar reject metrics
- **Allocations** on tick thread when overlay is on and from **`new TickResolutionSnapshot` every tick**
- **Sidecar socket reader** not fully wired in-repo; reject “no silent close” not end-to-end verified here
- **`specAvailableFromTick`** in replay projection is placeholder (`tick`), not legacy spec cooldown

---

## Invariant scorecard

| # | Invariant | Result |
|---|-----------|--------|
| 1 | Hook order & legacy pairing | **Pass** on main path; **partial** on early-return ticks |
| 2 | Overlay isolation & double-buffer | **Pass**; **fail** strict zero-alloc with overlay / per-tick snapshot alloc |
| 3 | Fingerprint & preconditions | **Pass**; bus does not re-check preconds |
| 4 | Suppression early release | **Pass** (incl. sub-threshold regen test) |
| 5 | Wire reject handling | **Partial** (codec/tests OK; logging, metrics threading, socket wiring) |
| 6 | NDJSON v2 & parity isolation | **Pass** for gate; **partial** for replay fidelity & periodic/shutdown |

---

## 1. Hook ordering & state mutation

**Contract:** `TickBusHooks.evaluateEarly` after `readLatestHitsplat`, `refreshPvpVitals`, `noteLocalHpDrop`, and **before** legacy dispatch; `recordShadowLegacy` at `publishState` with monolith `lastAction`.

**Main PvP path — pass**

In `CombatScript.onTick`, order is: vitals trio → `evaluateEarly` → legacy block (auto-prayer, NH, specs, …) → `finally` → `publishState()`.

- `publishState()` first calls `TickBusHooks.recordShadowLegacy(this, currentTick)` with `script.lastAction` after legacy sequencing (shadow only).
- Shadow: `evaluateEarly` returns `false`, so legacy still runs; pairing is end-of-tick `lastAction`.

**Caveats**

| Issue | Impact |
|--------|--------|
| Many **`return` paths before vitals / `evaluateEarly`** (combo phases, pending specs, pause, bot disabled) | **`finally` still `publishState()`** → legacy without orch → **`TIMING_DIFF`** |
| Work **before** vitals (pending veng/prayer/spec queues ~863+) | Orch sees state after partial queue drains; contract names only the three vitals calls — interpret “legacy dispatch” as post-1094 block |
| **Non-shadow:** `evaluateEarly` true → `publishState` at early exit **and** in `finally` | Double snapshot; shadow legacy hook no-ops when not shadow |

**Key paths:** `CombatScript.onTick`, `TickBusHooks.evaluateEarly` / `recordShadowLegacy`, `TickBusIntegration.recordLegacyTail`.

---

## 2. Overlay & double-buffering

**Contract:** Paint reads **only** `OverlayPublisher.current()`; never `TickBus` or resolution on paint. Fill buffer completely on tick thread, then `visible = buffer`; A/B reuse, no allocations.

**Isolation & handoff — pass**

- `TickBusHudOverlay` / `TickBusWinnerTileOverlay` use `OverlayPublisher.current()` only.
- Preallocated `OverlayState` A/B; alternate `writeIndex`; fill then assign `visible`.

**Zero-allocation — partial / fail when overlay on**

- With `-Droatz.overlay=true`, `OverlayPublisher.publish` builds **`StringBuilder` + new `String`s per channel** every tick on the **tick thread** (documented debug HUD path).
- `LiveTickOrchestrator.onTick` allocates **`new TickResolutionSnapshot(...)` every tick** even with overlay off.
- `NoAllocationTest` only smoke-runs 1000 ticks without throwing — **no alloc assertion**; recorder often null in test.

---

## 3. Fingerprint registry & preconditions

**Contract:** Bits per `docs/fingerprint-registry.md`; precond `(stateFingerprint & mask) == (hash & mask)`; maskless → `masklessIntents`.

**Pass**

- `FingerprintRegistry` + `CombatTickStateAdapter.getFingerprint()` / `fillReplayProjection()`: HP 0–7, food bit 8, protect bit 9, spec 16–23.
- Bit 9 source: `CombatScript.protectPrayerMaskForTelemetry()` (not internal `activeProtectPrayer()` accessor name in registry).
- Sidecar: same masked equality as `Intent.precondSatisfied`; `precondMask == 0` → `noteMasklessIntent()` (including observe-only before bus publish).

**Gap**

- `resolveChannel` does **not** call `precondSatisfied` on bus intents; sidecar filters at publish; transcribed advisors use mask 0.

---

## 4. Suppression leases & early release

**Contract:** EAT when `localHp > eatThreshold` (`comboEatHpThreshold`, default 32); no sub-threshold regen release; PRAYER when `protectPrayerMask == 0`.

**Pass**

- `SuppressionTable.onReleaseInputs`: strict `localHp > eatThreshold`; PRAYER expire when mask == 0.
- `SustainAdvisor`: eat when `hp <= comboEatHpThreshold` — band consistent with lease invalidation strictly above threshold.
- Test: `SuppressionLeaseTest.subThresholdHpRegenDoesNotReleaseEatLeaseEarly`.

---

## 5. Wire framing & rejection handling

**Contract:** WIRE_V2 40-byte BE frame; reject on error → **`wireRejects`**, `SidecarWireRejectLog`, **do not close socket**; rate-limited rejection bursts.

**Codec — pass**

- `SidecarFrameCodec`: version byte + big-endian 40 B body; `SidecarWireRejectTest` for version mismatch → `wireRejects`.

**Partial / gaps**

| Item | Status |
|------|--------|
| Production **socket reader** | Not wired in-repo; `ingestWireFrame` API only — “no silent close” not verified E2E |
| **Rate-limited bursts** | `SidecarWireRejectLog` logs **every** reject at WARNING — no dedup/throttle |
| **`wireRejects` on orch lines** | `beginTick()` **resets** counter; increments may run on **reader thread** while tick thread clears — **race + under-count** |
| **Sidecar payload** | `offerPayload` mutates ring slot then `AtomicReference.set` — possible torn reads under concurrent offers |

**NDJSON field name:** single counter **`wireRejects`** (no duplicate `sidecarWireFrameRejects`).

---

## 6. NDJSON schema v2 & parity gate isolation

**Contract:** Full `replayProjection` + `busIntents[]` on orch lines; legacy `uncomparableSubtype`; `goldenDiff` from **`ParityInput` only** — no outcome/periodic influence.

**Pass**

- `OffThreadNDJSONRecorder`: projection fields, bus intents, winners/drops/dispatch, sidecar observe fields including `wireRejects`.
- Legacy: `LegacyComparability.classify` → `NO_OPINION` / `OUT_OF_VOCAB` / comparable at record time.
- `GoldenDiffTool` + `GoldenParityClassifier`: orch winner summary + legacy action/subtype only; periodic lines not used in classification.

**Partial**

- **`specAvailableFromTick`:** adapter sets to current `tick`, not real spec cooldown — SPECIAL lease replay accuracy limited until wired from legacy.
- **`enqueuePeriodic`:** allocates `new long[2]` on tick thread every 50 ticks.
- **`flushPeriodicBestEffort`:** not wired on shutdown (contract “remaining”).
- Queue full → `recordsDropped` — possible orch/legacy pairing loss under overload.

**`damageTaken`:** per-tick on orch (`replayProjection`); session-cumulative on periodic — disambiguate by `recordKind`.

---

## Recommended follow-ups (priority)

1. **`wireRejects`:** cross-thread safe counter; do not blindly zero in `beginTick` if rejects must be visible on orch lines (per-tick latch or cumulative policy — pick one and document).
2. **`SidecarWireRejectLog`:** burst/rate limit per contract.
3. **Early-return ticks:** minimal no-op orch or document out-of-scope paths for parity.
4. **Zero-alloc:** reuse `TickResolutionSnapshot`; reduce overlay string churn if capturing with `-Droatz.overlay=true`.
5. **`specAvailableFromTick`:** bind to legacy spec cooldown when transcribed.

---

## Operator gate (unchanged)

Shadow capture:

```text
-Droatz.tickbus=true
-Droatz.tickbus.shadow=true
-Droatz.tickbus.rec=./logs/session_01.ndjson
-Droatz.overlay=true
```

Parity:

```text
./gradlew goldenDiff -Psession=./logs/session_01.ndjson
```

Paste: **MATCH, RULE_DIFF, PRIORITY_DIFF, TIMING_DIFF, FEASIBILITY, UNCOMPARABLE** (NO_OPINION / OUT_OF_VOCAB).

When interpreting counts, expect extra **TIMING_DIFF** from ticks that never reached `evaluateEarly`, and treat **`wireRejects`** on orch lines as **lower bound** until metrics threading is fixed.

---

*Audit performed against PR #21 rev 2 frozen schema. For implementation entry points: `LiveTickOrchestrator`, `CombatScript.onTick`, `OverlayPublisher`, `SidecarFrameCodec`, `OffThreadNDJSONRecorder`, `GoldenDiffTool`.*
