# TickBus / EchoForge sidecar wire contract

## Tick hook order (shadow baseline)

In `CombatScript.onTick`, ordering is fixed:

1. Vitals refresh (`readLatestHitsplat`, `refreshPvpVitals`, `noteLocalHpDrop`)
2. **`TickBusHooks.evaluateEarly`** — orchestrator evaluates on **pre-legacy** state
3. Legacy monolith (auto-prayer, NH, spec dumps, …) when `-Droatz.tickbus.shadow=true`
4. **`publishState`** → `TickBusHooks.recordShadowLegacy` pairs legacy `lastAction` with the orch NDJSON line

If the orchestrator ran after legacy dispatch, golden diffs would show ordering artifacts (HP/prayer already mutated), not logic bugs.

## Render overlay (paint thread)

The tickbus HUD is **not** a decision brain. `OverlayPublisher` hands off a double-buffered {@code OverlayState} once per tick after resolution; the client paint hook reads {@code OverlayPublisher.current()} only. Never read {@code TickBus} from paint.

Double-buffer rules (tick thread): **fill completely, then** assign {@code visible = buffer}; never publish then fill. Alternate A/B each tick — do not refill the buffer just published until the next tick. Under a lag spike, paint can straddle a flip and show a **one-frame tear** (mixed channel lines). Two buffers are acceptable **only for a debug HUD** with that tear documented — **do not build tile animations, automation, or any logic that assumes a consistent overlay frame** (including winner tile coloring as a verifier, not a timing signal).

## Shadow acceptance (definition of done)

**Project state (PR #21):** instrumentation landed (orchestrator, NDJSON recorder, overlay HUD/tiles, {@code goldenDiff}). **No shadow baseline capture has been run yet** — advisor vs legacy parity is the open question until an operator completes capture below.

**Capture (one full fight / session minimum for first read; two sessions before trusting counts):**

```text
-Droatz.tickbus=true
-Droatz.tickbus.shadow=true
-Droatz.tickbus.rec=<path/to/session.ndjson>
-Droatz.overlay=true
```

Attach agent as today ({@code launch.ps1} unchanged). Legacy monolith still dispatches; orchestrator records {@code orch} lines and pairs legacy via hook order above.

**Parity gate:** {@code goldenDiff} compares orchestrator output (transcribed advisors = **candidate**) against **legacy monolith actual dispatch** ({@code legacyAction} from {@code recordShadowLegacy} at {@code publishState}). Legacy is the oracle; nothing in the diff re-runs advisors in isolation.

**Strategic risk:** the architecture is not the open question — the harness must distinguish *“advisors match the monolith”* from *“the harness cannot see clearly enough to know.”* NDJSON schema v2 fields (state projection, bus intent inputs, overflow counters, {@code UNCOMPARABLE}) exist to buy that discrimination. **First shadow capture freezes the telemetry schema** — land schema changes before capture or recapture.

**Verdict:**

```text
./gradlew goldenDiff -Psession=<path/to/session.ndjson>
```

Paste category counts: **MATCH**, **RULE_DIFF**, **PRIORITY_DIFF**, **TIMING_DIFF**, **FEASIBILITY**, **UNCOMPARABLE** (legacy empty / no opinion / outside captured vocab — excluded from match rates, raw count reported).

**Gate to flip production tickbus (drop shadow):**

1. Two full shadow sessions with stable category distributions (no drift run-to-run on the same scenario).
2. {@code FEASIBILITY=0} on reviewed sessions.
3. {@code NoAllocationTest} green in CI / local.
4. Enable {@code -Droatz.tickbus=true} **without** {@code -Droatz.tickbus.shadow=true}; keep recorder on until a second validation pass.

**Overlay cross-check:** when {@code goldenDiff} reports {@code RULE_DIFF}, the HUD channel line must show the same {@code drop=<EliminationReason>} as NDJSON — if not, fix recorder/overlay resolution drift before trusting either source.

{@code TickResolutionSnapshot} is the same resolution output as NDJSON {@code channelDrops} / elimination reasons — one pass in {@code LiveTickOrchestrator}, not a second path on paint. Lease countdowns, sidecar lag, and HUD label strings are computed on the tick thread at publish time; paint only calls {@code drawString} on precomputed lines. {@code tickIndex} and {@code publishSequence} on the strip answer “live vs frozen”; {@code STALE} appears when {@code publishSequence} is unchanged for many frames. Sidecar grace replays show a {@code †} suffix when {@code bornTick != tickIndex}.

Enable with {@code -Droatz.overlay=true} (same agent JAR / {@code launch.ps1} attach; no script changes required). Meaningful when {@code -Droatz.tickbus=true} is also on.

Cross-JVM contract between the Java 11 RoatzBot agent and the Java 17 EchoForge sidecar.

## Intent freshness (TTL)

Wire field `ttlTicks` is an **inclusive grace offset** from `evalTick`:

**Publishable iff** `state.tickIndex >= evalTick && state.tickIndex <= evalTick + ttlTicks`.

Examples:

| evalTick | ttlTicks (wire) | Valid tick indices |
|---|---|---|
| N | 0 | N only |
| N | 1 | N, N+1 |

The bus implements this identically in `Intent.isValidAt(long tick)` — **no local `+1` conversion**. Sidecar codecs pass `ttlTicks` through untouched.

## Fingerprint preconditions

Per-intent `precondHash` and `precondMask` (32-bit). Satisfied when:

`(stateFingerprint & precondMask) == (precondHash & precondMask)`

`precondMask == 0` is unconditional (always satisfied). Unconditional sidecar intents increment `masklessIntents` in telemetry for golden-review scrutiny.

### Fingerprint field registry

| Field | Bit range | Source | Consulted by |
|---|---|---|---|
| Local HP | 0–7 (`FingerprintLayout.HP_MASK`) | {@code CombatTickState.localHp()} / adapter | SustainAdvisor, sidecar eat preconds |
| Spec energy | 16–23 (`FingerprintLayout.SPEC_MASK`) | {@code specEnergyPercent()} | CombatAdvisor spec gate, sidecar |

**Rule:** any new state field consulted by a sidecar intent must be added to this table (or explicitly waived). {@code FingerprintRegistryTest} guards registered fields used in tests.

## TickBus overflow

When the 32-slot bus is full, publish evicts the **lowest rank** only if the incoming intent **strictly outranks** it; otherwise the incoming intent is dropped. Per tick NDJSON records {@code droppedPublishes} and {@code maxRankDropped}. High-priority bursts that still overflow indicate advisor logic bugs (counter spike), not a sort pass.

## Suppression leases

Default durations are applied on dispatch in {@code LiveTickOrchestrator}. Leases expire by tick deadline **or** early release on invalidating vitals:

| Kind | Duration (ticks) | Early release |
|---|---|---|
| EAT / SIP | 3 | Local HP **increases** vs previous observed tick ({@code SuppressionTable.onVitals}) |
| EQUIP | 1 | — |
| PRAYER | 1 | Prayer inactive (fingerprint bit TBD — {@code onPrayerInactive}) |
| SPECIAL | until {@code specAvailableFromTick} | — |

Lease-holding despite invalidated state is a bug class — extend this table when adding kinds.

## Dispatch order (orchestrator)

After {@code ChannelRules}: **DEFENSIVE → SUSTAIN → OFFENSIVE** (prayer/gear before food before attack). Source: {@code LiveTickOrchestrator.onTick}.

## NDJSON recorder schema v2 ({@code schemaVersion: 2}) — **frozen pre-shadow**

Orchestrator lines ({@code recordKind: "orch"}) include:

- {@code replayProjection}: **fingerprint registry ∪ vitals ∪ suppression-release inputs only** — {@code fingerprint}, {@code localHp}, {@code specEnergy}, {@code specAvailableFromTick}. Single-source with {@code FingerprintLayout} + sidecar preconds. **Replay reads; never recomputes client randomness.**
- {@code busIntents[]}: per-intent {@code kind}, {@code priority}, {@code advisor}, {@code rank}, ids/slots, {@code bornTick}, {@code ttlTicks}
- {@code droppedPublishes}, {@code maxRankDropped}
- {@code sidecarWireFrameRejects} (increment on wire decode/version reject — log + count, never silent close)

Legacy tail lines: {@code recordKind: "legacy"}, {@code legacyAction}, optional {@code uncomparableSubtype} ({@code NO_OPINION} | {@code OUT_OF_VOCAB}) **classified at record time** ({@code LegacyComparability}).

{@code goldenDiff} reports {@code UNCOMPARABLE} with subtype counts; excluded from match rates.

### Tier 1 (frozen — ship before capture)

- {@code UNCOMPARABLE} subtypes {@code NO_OPINION} / {@code OUT_OF_VOCAB} on legacy NDJSON
- Fingerprint registry seed (CombatAdvisor + SustainAdvisor fields)
- Recorder schema v2 ({@code replayProjection}, bus intents, overflow counters)
- Parity gate sentence (legacy oracle, transcription candidate)
- Sidecar **wire reject logging + counter** ({@code SidecarWireRejectLog}) — independent of watchdog hysteresis deferral

### Tier 2 (hardening; may trail first capture)

- Lease early-release spec expansion + tests
- Pool outstanding-count assertion
- Dispatch order as data table
- Fingerprint **coverage** enforcement test (registry vs builder)

### Operator gate (only remaining risk)

- [ ] Shadow capture + paste MATCH / RULE_DIFF / PRIORITY_DIFF / TIMING_DIFF / FEASIBILITY / UNCOMPARABLE (subtype counts)

## Sidecar frame (WIRE_V1 / WIRE_V2)

Negotiation uses a leading **version byte** ({@code WIRE_V1} 32B / {@code WIRE_V2} 40B). On mismatch or truncated frame: **log, increment {@code sidecarWireFrameRejects}, discard frame** — do not silently close the session. Healthy flag resets on fresh valid payload (observe-only sidecar today).

40-byte big-endian intent body — see `SidecarFrameCodec` ({@code WIRE_V2}).

### TODO (post-shadow / when sidecar socket lands)

- Watchdog **two-threshold** degrade (hysteresis) — sidecar is observe-only during shadow; policy does not affect parity gate yet.
- Generate {@code ChannelRules} exclusivity doc from a data table (polish).
