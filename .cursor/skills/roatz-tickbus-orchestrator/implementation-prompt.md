# Cursor Prompt — Zero-Allocation TickBus Orchestrator (Java 11)

## Role & Objective

You are implementing a deterministic, zero-allocation, sub-millisecond combat orchestrator for a Java 11 OSRS-style client agent. Refactor the codebase so a single `LiveTickOrchestrator` is the **only** consumer of the client reflection `onTick` hook, fed by lightweight `Advisor`s publishing typed `Intent`s to a `TickBus`, with an asynchronous Java 17 EchoForge sidecar integrated over a binary socket protocol. NDJSON telemetry feeds an offline `GoldenReplayTest`.

**Non-negotiable invariants (violating any of these is a failed implementation):**

1. **Deterministic time.** All TTLs, expirations, and arbitration use `GameState.tickIndex` (monotonic long). `System.currentTimeMillis()` / `System.nanoTime()` / wall-clock reads are **forbidden** in `com.bot.core.bus` and every `Advisor.evaluate` path. Grep-enforce this; fail the build on violations.
2. **Zero allocation on the tick thread.** No `new` per tick except in explicitly whitelisted cold paths, no streams, no lambdas, no varargs expansion, no autoboxing, no `String` building. Preallocated everything. `snapshot()` must NOT be called on the tick thread (see Telemetry).
3. **Single owner.** Exactly one class calls the client's reflection tick hook. All other decision engines become `Advisor`s. Legacy monolith and terminator-style logic must be wrapped as advisors, never left as independent tick hooks — delete their registration sites.
4. **Arbitration is a pure function** of `(GameState, published intents, suppression state)`. Same inputs → same winners, always. This is what makes `GoldenReplayTest` meaningful.

## Package Layout

```
com.bot.core.bus        Intent, ActionKind, ActionPriority, TickBus, Advisor, Channel
com.bot.core.orchestrator  LiveTickOrchestrator, SuppressionTable, ChannelRules
com.bot.core.sidecar    AsyncSidecarAdvisor, SidecarFrameCodec, SidecarPayload, SidecarHealth
com.bot.core.telemetry  OffThreadNDJSONRecorder, TickRecord, DropLog
```

## 1. Core Domain (`com.bot.core.bus`)

### Enums & Intent

```java
public enum ActionKind { EAT, SIP, EQUIP, PRAYER, SPECIAL, ATTACK, MOVE, IDLE }

public enum ActionPriority {
    BACKGROUND(0), SUSTAIN(10), OFFENSIVE(20), CRITICAL(30);
    private final int weight;
    ActionPriority(int weight) { this.weight = weight; }
    public int getWeight() { return weight; }
}
```

`Intent` is a final flat struct (fields as in the spec): `kind, itemId, npcIndex, slotIndex, priority, advisorOrdinal, bornTick, ttlTicks, precondHash, precondMask`. Implement:

- `int rank()` → `(priority.getWeight() << 8) | (128 - advisorOrdinal)` — a single precomputed int; make `rank` cached at construction (store it in a field, not computed per comparison).
- `boolean isValidAt(long tick)` → `tick >= bornTick && tick < bornTick + ttlTicks`.
- **Fingerprint validation must be masked, not equality.** `boolean precondSatisfied(int stateFingerprint)` → `(stateFingerprint & precondMask) == (precondHash & precondMask)`. Rationale: strict `==` fails whenever *any* unrelated state bit changes between the sidecar's evalTick and the current tick (e.g., tick counter or XP drops fingerprinted into state). The sidecar decides which state bits its advice depends on via `precondMask`; the advisor checks only those bits. Default mask = `0` (unconditional). Document this in Javadoc — it is the difference between "late" and "wrong" advice.

### Channel

Replace varargs with a preallocated singleton to avoid implicit array allocation on the hot path:

```java
public final class Channel {
    public static final Channel OFFENSIVE = new Channel(ActionKind.ATTACK, ActionKind.SPECIAL, ActionKind.MOVE);
    public static final Channel SUSTAIN   = new Channel(ActionKind.EAT, ActionKind.SIP);
    public static final Channel DEFENSIVE = new Channel(ActionKind.PRAYER, ActionKind.EQUIP);
    private final ActionKind[] kinds; // internal, preallocated
    public boolean contains(ActionKind k) { /* plain loop, no allocation */ }
}
```

### TickBus

- Fixed-capacity preallocated `Intent[] intents` with an int `count` — **not** `ArrayList` (ArrayList still allocates on resize and Iterator paths; a raw array with a count is simpler and provably allocation-free). Capacity 32; `publish` beyond capacity drops lowest-rank intent and increments a `droppedPublishes` counter (never throw, never grow, never allocate).
- `Intent resolveChannel(Channel ch)` — single pass, plain loop, tracks best by `rank()`, skipping intents where `!isValidAt(currentTick)` (pass `currentTick` in via a `beginTick(long)` call from the orchestrator, not a field the bus reads).
- `clear()` resets `count = 0` only. Retains all `Intent` objects — they must be **reused**, so add a `reset()` method to `Intent` (mutable fields, but treated immutably after publish) and have advisors obtain intents from a preallocated per-advisor pool (`IntentPool.obtain()` / returns null when exhausted). No advisor may `new Intent(...)` in `evaluate`. Enforce via package-private constructor + pool.
- Expose `int size()` and `Intent get(int i)` for telemetry.

### Advisor

```java
public interface Advisor {
    void evaluate(GameState state, TickBus bus);
    // ordinal assigned once at registration; used in rank()
}
```

## 2. Orchestrator (`com.bot.core.orchestrator`)

`LiveTickOrchestrator` — the only reflection hook consumer. `onTick(GameState state)` in this exact order:

1. `bus.beginTick(state.tickIndex)`; `bus.clear()`.
2. Evaluate each advisor in a preallocated `Advisor[]` in registration order (order = tie-break precedence; document it).
3. Resolve per channel: `OFFENSIVE`, `SUSTAIN`, `DEFENSIVE`.
4. **Apply game-legal exclusivity rules** via `ChannelRules` (a static, table-driven class, no lambdas):
   - `EAT`/`SIP` winning SUSTAIN suppresses OFFENSIVE for this tick → record reason `SUPPRESSED_BY_RULE`.
   - `EQUIP` of the same item already equipped → drop, reason `NOOP`.
   - `SPECIAL` when spec energy insufficient per GameState → drop, reason `NOOP`.
5. **Suppression leases** (`SuppressionTable`): flat `int[] kindCooldowns[ActionKind.values().length]` of tick deadlines. After dispatch: EAT/SIP → 3 ticks, EQUIP → 1 tick, PRAYER flick → 1 tick, SPECIAL → until spec cooldown in GameState. `resolveChannel` skips suppressed kinds. Suppressing decisions are recorded with reason `SUPPRESSED_LEASE`.
6. Dispatch each surviving channel winner via a `ReflectionDispatcher` (no-op if winner null). Dispatch order: DEFENSIVE → SUSTAIN → OFFENSIVE (prayer and food register before the attack; verify against observed client behavior and reorder if needed — note this in a comment).
7. Hand telemetry a **reference** to the bus (not a copy) to the off-thread recorder via a preallocated `TickRecord` object taken from a ring buffer — see §4. **Never call `bus.snapshot()` on the tick thread.**
8. `bus.clear()` happens at the START of the next tick, not here, so the recorder reads post-resolution state.

## 3. Async Sidecar (`com.bot.core.sidecar`)

### Binary frame — fix the spec's missing `ackTick`

32-byte stride, big-endian, parsed with a reused `ByteBuffer` (never reallocated):

```
[evalTick: long 8B][ackTick: long 8B][kind: byte 1B][priority: byte 1B]
[ttlTicks: byte 1B][advisorOrdinal: byte 1B][precondMask: int 4B]
[precondHash: int 4B][itemId: int 4B][npcIndex: int 4B][slotIndex: int 4B]  → 40B total
```

Use 40 bytes (the 32-byte spec cannot fit `ackTick`, which the health watchdog requires). Multiple frames per payload, length-prefixed. If the Java 17 sidecar cannot be changed yet, gate the codec with a `WIRE_V1 (32B) / WIRE_V2 (40B)` negotiation byte and degrade the watchdog to frame-arrival-time-based until the sidecar catches up.

### AsyncSidecarAdvisor

- Background single daemon thread owns the `SocketChannel`. Parse into `SidecarPayload` objects from a **preallocated ring of 8 payloads** (never allocate on the reader thread either).
- Handoff: `AtomicReference<SidecarPayload>` + sequence guard: `AtomicLong lastAppliedEvalTick`; a payload with `evalTick <= lastAppliedEvalTick` is discarded (retransmit/reorder protection). CAS the long first, then set the reference.
- **Watchdog:** `long lastAckTick` from `ackTick`. In `evaluate`, if `state.tickIndex - lastAckTick > 5` → set `healthy=false` once (log exactly once via DropLog with reason `SIDECAR_UNHEALTHY`), stop reading the cache, fall back to native advisors. Reset on fresh payload. When unhealthy, do **not** touch the atomics at all (saves the reads and makes the fallback trivially provable).
- **Evaluation logic per intent:**
  1. Freshness window: `state.tickIndex <= si.evalTick + si.ttlTicks` (grace tick: an intent evaluated at tick N is publishable through N+1). Outside → DropLog `STALE_TICK`.
  2. Precondition: `precondSatisfied(state.getFingerprint())` — masked comparison. Fail → DropLog `STALE_STATE`.
  3. Both pass → republish into the bus with `bornTick = si.evalTick`.
- Reconnect with backoff on the reader thread (1s → 2s → 4s → cap 30s). Never propagate reader-thread exceptions into the tick thread; on parser error, drop the buffer and count `malformedFrames`.
- `GameState.getFingerprint()` must be a stable bitwise OR/XOR of the documented bits (hp bucket, npc index, target hash, spec energy, prayer state). **Document the exact bit layout** in a single place shared conceptually with the J17 sidecar — the fingerprint layout is a cross-JVM contract.

## 4. Telemetry (`com.bot.core.telemetry`)

`OffThreadNDJSONRecorder`:

- Preallocated ring of 64 `TickRecord` objects (mutable, reused). `enqueue` on the tick thread copies fields into the next ring slot and offers it to an `ArrayBlockingQueue` (capacity 256) — **`offer`, never `put`**; drop-on-full and increment `recordsDropped`.
- The tick thread writes only primitives into the ring slot — **no `bus.snapshot()`, no `String`s**. Serialization (including any intent-list expansion) happens entirely on the writer thread.
- Background writer thread: poll queue, write NDJSON lines. Schema per tick: `tickIndex`, per-channel `winners` (kind, ids, rank, byline), `losers` with elimination reasons (`RANK`, `STALE_TICK`, `STALE_STATE`, `SUPPRESSED_BY_RULE`, `SUPPRESSED_LEASE`, `NOOP`, `DROPPED_PUBLISH`), `sidecarHealth`, `sidecarAckLag`, `busSize`, `recordsDropped`.
- Writer thread buffered writer, flushed every N lines or 250ms of wall time (wall clock allowed **only here**). Daemon thread, clean shutdown hook drains the queue.

## 5. Tests (required, not optional)

1. **`TickBusArbitrationTest`** — pure-function property: same published intents + same tick → same winners across 10k shuffled publish orders within an advisor (order across advisors is defined by ordinal; assert rank tie-breaks resolve identically regardless of publish order among advisors of *equal* rank+ordinal).
2. **`SidecarFreshnessTest`** — intent evaluated tick N with ttl 1: publishable at N and N+1, dropped at N+2 with `STALE_TICK`.
3. **`FingerprintMaskTest`** — masked comparison passes when unrelated bits change, fails when masked bits change.
4. **`SuppressionLeaseTest`** — EAT dispatch blocks OFFENSIVE same tick and SPECIAL/EAT re-fire for 3 ticks.
5. **`GoldenReplayTest`** — feed a recorded NDJSON session through the bus offline, assert identical winners per tick (recorder→replayer round-trip is the acceptance criterion).
6. **`NoAllocationTest`** — run 1000 ticks with `-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC` (or a JFR allocation profile assertion) and assert zero allocation on the tick path.

## 6. Integration & Rollout

- Flag-gate: `-Droatz.tickbus=true` switches the reflection hook to `LiveTickOrchestrator`; default off. Legacy paths remain compilable until advisors are verified.
- Wrapping priority: SustainAdvisor (food/prayer) and CombatAdvisor (attack/spec) first — these replace the legacy monolith's hot path. Terminator kill-window math becomes a third advisor publishing MOVE/SPECIAL intents.
- Do NOT ship `com.automation.core` (Java 17) classes in the agent JAR. The J11 agent depends only on the wire format and fingerprint layout, never on EchoForge classes.

## Working Instructions for You (Cursor)

- Implement package by package in the order: `bus` → `orchestrator` → `sidecar` → `telemetry` → tests. Stop after `bus` and show me `TickBus`/`Intent` before continuing.
- When you are about to violate an invariant (e.g., you need an allocation or a clock read), stop and explain rather than doing it silently.
- Where the spec is ambiguous (dispatch order, channel membership, fingerprint bits), pick the safest default, mark it with `// DECISION:` comments, and list all such decisions at the end of your response.
- Target Java 11 strictly (`--release 11`); no records, no switch expressions, no `List.of` in hot paths (it's fine in static config).

---

**Three things in this prompt that differ from your spec — worth knowing why:**

1. **40-byte frames, not 32.** Your frame layout couldn't fit `ackTick`, which your own health watchdog requires. The prompt makes the watchdog actually implementable.
2. **Masked fingerprints, not `==`.** Equality fingerprints false-fail whenever any unrelated state bit changes between evalTick and consumption — that would silently kill valid sidecar advice. The mask (decided by the sidecar per-intent) checks only the bits the advice depends on.
3. **Intent pooling + mutable-reuse instead of `new Intent` per publish.** Your invariant says zero hot-path allocation, but advisors `new`-ing intents every tick violates it. Reuse via a pool is the only way to make invariant #2 actually true — and it's why `snapshot()` had to be banned from the tick thread.
