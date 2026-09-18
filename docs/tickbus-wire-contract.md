# TickBus / EchoForge sidecar wire contract

## Tick hook order (shadow baseline)

In `CombatScript.onTick`, ordering is fixed:

1. Vitals refresh (`readLatestHitsplat`, `refreshPvpVitals`, `noteLocalHpDrop`)
2. **`TickBusHooks.evaluateEarly`** — orchestrator evaluates on **pre-legacy** state
3. Legacy monolith (auto-prayer, NH, spec dumps, …) when `-Droatz.tickbus.shadow=true`
4. **`publishState`** → `TickBusHooks.recordShadowLegacy` pairs legacy `lastAction` with the orch NDJSON line

If the orchestrator ran after legacy dispatch, golden diffs would show ordering artifacts (HP/prayer already mutated), not logic bugs.

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
