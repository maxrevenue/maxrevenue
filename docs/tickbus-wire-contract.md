# TickBus / EchoForge sidecar wire contract

## Tick hook order (shadow baseline)

In `CombatScript.onTick`, ordering is fixed:

1. Vitals refresh (`readLatestHitsplat`, `refreshPvpVitals`, `noteLocalHpDrop`)
2. **`TickBusHooks.evaluateEarly`** — orchestrator evaluates on **pre-legacy** state
3. Legacy monolith (auto-prayer, NH, spec dumps, …) when `-Droatz.tickbus.shadow=true`
4. **`publishState`** → **`TickBusHooks.recordShadowLegacy`** pairs legacy `lastAction` with the orch NDJSON line

If the orchestrator ran after legacy dispatch, golden diffs would show ordering artifacts (HP/prayer already mutated), not logic bugs.

## Render overlay (paint thread)

The tickbus HUD is **not** a decision brain. `OverlayPublisher` hands off a double-buffered {@code OverlayState} once per tick after resolution; the client paint hook reads {@code OverlayPublisher.current()} only. Never read {@code TickBus} from paint.

Double-buffer rules (tick thread): **fill completely, then** assign {@code visible = buffer}; never publish then fill. Alternate A/B each tick — do not refill the buffer just published until the next tick. Under a lag spike, paint can straddle a flip and show a **one-frame tear** (mixed channel lines). Two buffers are acceptable **only for a debug HUD** with that tear documented — **do not build tile animations, automation, or any logic that assumes a consistent overlay frame** (including winner tile coloring as a verifier, not a timing signal).

## Shadow acceptance (definition of done)

**Project state (PR #21 rev 2):** instrumentation and schema v2 **landed**. **No shadow baseline capture has been run yet** — advisor vs legacy parity is the open question until an operator completes capture below.

**Capture (one full fight / session minimum for first read; two sessions before trusting counts):**

```text
-Droatz.tickbus=true
-Droatz.tickbus.shadow=true
-Droatz.tickbus.rec=<path/to/session.ndjson>
-Droatz.overlay=true
```

Attach agent as today ({@code launch.ps1} unchanged). Legacy monolith still dispatches; orchestrator records {@code orch} lines and pairs legacy via hook order above.

**Parity gate definition:** {@code goldenDiff} classifications are computed solely from resolved channel winners vs the recorded {@code legacyAction} per tick. {@code UNCOMPARABLE} ticks ({@code NO_OPINION}, {@code OUT_OF_VOCAB}) are excluded from all rates and reported as raw counts only. Outcome counters and sidecar latency metrics exist in the telemetry schema for post-parity analysis and may not appear in, influence, or gate any classification category. The oracle is the legacy monolith's actual dispatch; transcribed advisors are the candidate under test, never a reference.

**Oracle clarification:** The parity oracle is the legacy monolith's actual dispatch ({@code recordShadowLegacy} at {@code publishState}); transcribed advisors are the candidate under test and are never used as a reference. Structural enforcement: {@code com.bot.core.golden.ParityInput} — the classifier entry point accepts nothing else.

**Strategic risk:** the architecture is not the open question — the harness must distinguish *“advisors match the monolith”* from *“the harness cannot see clearly enough to know.”* NDJSON schema v2 fields (state projection, bus intent inputs, overflow counters, {@code UNCOMPARABLE}) exist to buy that discrimination. **First shadow capture freezes the telemetry schema** — land schema changes before capture or recapture.

**Verdict:**

```text
./gradlew goldenDiff -Psession=<path/to/session.ndjson>
```

Paste category counts: **MATCH**, **RULE_DIFF**, **PRIORITY_DIFF**, **TIMING_DIFF**, **FEASIBILITY**, **UNCOMPARABLE** with subtypes **NO_OPINION** / **OUT_OF_VOCAB** (excluded from match rates).

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

Authoritative table: `docs/fingerprint-registry.md` and `FingerprintRegistry`.

| Field | Bit | Source | Consulted by |
|---|---|---|---|
| Local HP | 0–7 | {@code localHp()} | SustainAdvisor, sidecar eat preconds |
| Food present | 8 | {@code foodSlotIndex >= 0} | SustainAdvisor ({@code findHpReducerSlotPublic}) |
| Protect prayer active | 9 | {@code protectPrayerMaskForTelemetry()} | Suppression PRAYER early-release |
| Spec energy | 16–23 | {@code specEnergyPercent()} | CombatAdvisor spec gate, sidecar |

**Rule:** any new state field consulted by a sidecar intent must be added to the registry (or explicitly waived). {@code FingerprintCoverageTest} + {@code FingerprintLayoutTest} guard layout.

## TickBus overflow

When the 32-slot bus is full, publish evicts the **lowest rank** only if the incoming intent **strictly outranks** it; otherwise the incoming intent is dropped. Per tick NDJSON records {@code droppedPublishes} and {@code maxRankDropped} ({@code -1} if none). High-priority bursts that still overflow indicate advisor logic bugs (counter spike), not a sort pass.

## Suppression leases

Default durations are applied on dispatch in {@code LiveTickOrchestrator}. Leases expire by tick deadline **or** early release from {@code replayProjection} (candidate-side; legacy has no equivalent lease table — expect some {@code RULE_DIFF} clusters):

| Kind | Duration (ticks) | Early release |
|---|---|---|
| EAT / SIP | 3 | {@code localHp > eatThreshold} where {@code eatThreshold = CombatScript.comboEatHpThreshold}. **Transcription:** {@code SustainAdvisor} eats when {@code hp >= 0 && hp <= comboEatHpThreshold}; lease purpose invalidates when HP leaves the eat band (strictly above threshold). Legacy reference field: {@code CombatScript.comboEatHpThreshold} (default 32). **Not** “any HP increase tick-over-tick” — sub-threshold regen must not release early. |
| EQUIP | 1 | Timeout only |
| PRAYER | 1 | {@code protectPrayerMask == 0} (overhead protect off). Registry bit 9 ({@code FingerprintRegistry.PROTECT_PRAYER_MASK}) / {@code CombatScript.protectPrayerMaskForTelemetry()}. |
| SPECIAL | until {@code specAvailableFromTick} | Timeout only ({@code specCooldown} in projection) |

**Task 5 expected delta:** Eat/prayer early-release on the candidate has **no legacy lease baseline**. {@code RULE_DIFF} clusters attributable to released-eat-early or released-prayer-early ticks are **expected and documented**, not defects.

## Dispatch order (orchestrator)

After {@code ChannelRules}: **DEFENSIVE → SUSTAIN → OFFENSIVE** (prayer/gear before food before attack). Source: {@code LiveTickOrchestrator.onTick}.

## NDJSON recorder schema v2 ({@code schemaVersion: 2}) — **frozen (PR #21 rev 2)**

**Replay rule:** replay must read projection fields from NDJSON; never recompute client randomness or re-derive vitals from the live client.

### {@code recordKind: "orch"}

**{@code replayProjection}** — union computed once per tick (must match {@code OffThreadNDJSONRecorder}):

| Field | Role |
|---|---|
| {@code fingerprint} | Composite 32-bit {@code FingerprintRegistry.compose(...)} |
| {@code localHp} | Raw HP (registry bits 0–7) |
| {@code specEnergy} | Raw spec % (registry bits 16–23) |
| {@code foodSlotIndex} | {@code findHpReducerSlotPublic()} (−1 if none); registry bit 8 derived as {@code foodSlotIndex >= 0} |
| {@code protectPrayerMask} | 0/1 overhead protect; registry bit 9 |
| {@code eatThreshold} | {@code comboEatHpThreshold} — suppression EAT early-release input |
| {@code specAvailableFromTick} | Spec cooldown / SPECIAL lease input ({@code specCooldown}) |
| {@code damageTaken} | Per-tick HP drop in orch lines (adapter/vitals delta, 0 if none). **Not** the session-cumulative {@code damageTaken} on periodic lines — same name, different {@code recordKind}. |
| {@code targetNpcIndex} | Reserved {@code -1} until advisor transcribes interacting target (rev 2) |

**{@code busIntents[]}** (every intent on the bus, winners and losers):

{@code kind}, {@code priority}, {@code advisorOrdinal}, {@code rank}, {@code itemId}, {@code npcIndex}, {@code slotIndex}, {@code bornTick}, {@code ttlTicks}, {@code byline} (interned id → label on writer thread only), {@code elimination} (enum name on non-winners / losers).

Also: {@code winners[]}, {@code channelDrops[]}, {@code dispatchState[]}, {@code busSize}, {@code droppedPublishes}, {@code maxRankDropped}, {@code recordsDropped} (queue drop counter snapshot), sidecar observe fields ({@code masklessIntents}, {@code sidecarAckLag}, {@code sidecarHealthy}, {@code staleTickDrops}, {@code staleStateDrops}, {@code wireRejects}).

### {@code recordKind: "legacy"}

{@code legacyAction}, optional {@code uncomparableSubtype}: {@code NO_OPINION} | {@code OUT_OF_VOCAB} (record-time {@code LegacyComparability}).

### {@code recordKind: "periodic"} (every **50 ticks**, best-effort on session close)

**Outcome counters** (projection transitions only — never from winners/dispatch): {@code eatsUsed}, {@code specsUsed}, {@code prayerUptimeTicks}, {@code damageTaken}.

**{@code sidecarArrivalLagTicks}:** p50/p99 from fixed histogram ({@code arrivalTickIndex - targetTickIndex}), distinct from per-tick {@code sidecarAckLag}.

{@code goldenDiff} uses {@code ParityInput} only; periodic/outcome/latency fields do not influence classification.

### Landed (PR #21 rev 2)

- {@code FingerprintRegistry} seed + coverage/layout tests; {@code docs/fingerprint-registry.md}
- Schema v2 {@code replayProjection}, full {@code busIntents[]}, overflow + queue drop counters
- {@code UNCOMPARABLE} subtypes on legacy lines; {@code ParityInput} structural parity gate
- Sidecar wire reject logging ({@code SidecarWireRejectLog}) + {@code wireRejects}
- Suppression EAT/PRAYER early-release (threshold + protect mask) + tests
- Intent pool {@code checkedOut()} leak test; {@code TickBusTieTest} / {@code TickBusOverflowPressureTest}
- Periodic NDJSON cadence (50 ticks); speculation trigger + replay-corpus rules (this doc)

### Remaining (post-capture or polish)

- [ ] **Operator:** first shadow capture + {@code goldenDiff} category paste
- [ ] Dispatch order as generated data table ({@code ChannelRules} polish)
- [ ] {@code targetNpcIndex} transcription when CombatAdvisor reads interacting target
- [ ] Watchdog two-threshold hysteresis (sidecar publish path still observe-only)
- [ ] Session-close {@code flushPeriodicBestEffort} wired from socket/shutdown hook (best-effort today)

## Sidecar frame (WIRE_V1 / WIRE_V2)

Negotiation uses a leading **version byte** ({@code WIRE_V1} 32B / {@code WIRE_V2} 40B). On version-negotiation mismatch or malformed frame: **never close silently** — increment {@code wireRejects}, log once per rejection burst ({@code SidecarWireRejectLog}), keep the socket reader alive with backoff. Observe-only sidecar still exercises this path; reject metrics are the first wire-contract violation signal. Healthy flag resets on fresh valid payload.

40-byte big-endian intent body — see `SidecarFrameCodec` ({@code WIRE_V2}).

## Known future extensions

**Trigger to build tick-ahead speculative evaluation:** p99 ({@code sidecarArrivalLagTicks}) ≥ 1 across ≥ 3 consecutive publishing sessions, OR late-answer rate (arrivals where {@code evalTick + ttlTicks < arrivalTick}) > 10% of ticks with sidecar payloads. Below threshold, speculation is out of scope; the reactive protocol with TTL + fingerprint preconditions is the end state.

**Replay-corpus rule:** High {@code OUT_OF_VOCAB} counts trigger a vocabulary-capture event; new captures are appended to the offline replay corpus. Replay regression shrinks capture frequency, never to zero.

**Periodic NDJSON:** Outcome counters are derived exclusively from projected {@code GameState} transitions — never from arbitration winners or dispatch. Periodic lines every **50 ticks** are the source of truth; session-close flush is **best-effort** (killed JVM may lose the final partial period).

### TODO (post-shadow / when sidecar socket lands)

- Watchdog **two-threshold** degrade (hysteresis) — sidecar is observe-only during shadow; policy does not affect parity gate yet.
- Generate {@code ChannelRules} exclusivity doc from a data table (polish).
