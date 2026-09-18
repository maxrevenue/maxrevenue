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

Double-buffer rules (tick thread): **fill completely, then** assign {@code visible = buffer}; never publish then fill. Alternate A/B each tick — do not refill the buffer just published until the next tick. Under a lag spike, paint can straddle a flip and show a **one-frame tear** (mixed channel lines). Acceptable for debug HUD only; do not treat the overlay as a consistent single frame for automation.

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

## Sidecar frame (WIRE_V2)

40 bytes big-endian per intent — see `SidecarFrameCodec`.
