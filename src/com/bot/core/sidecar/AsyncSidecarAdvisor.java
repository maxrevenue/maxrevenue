package com.bot.core.sidecar;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.bus.IntentPool;
import com.bot.core.bus.TickBus;
import com.bot.core.bus.Advisor;
import com.bot.core.model.GameState;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes sidecar intents on the tick thread. Socket IO stays on a daemon reader thread
 * (connect/backoff wiring lands with {@code -Droatz.sidecar=} host config).
 */
public final class AsyncSidecarAdvisor implements Advisor {

    public static boolean enabled() {
        return System.getProperty("roatz.sidecar") != null;
    }

    /** Shadow-of-shadow: observe sidecar frames without publishing to the bus. */
    public static boolean observeOnly() {
        return Boolean.getBoolean("roatz.sidecar.observe");
    }
    private static final long UNHEALTHY_LAG_TICKS = 5L;

    private final int advisorOrdinal;
    private final IntentPool intentPool;
    private final SidecarTickMetrics metrics;
    private final SidecarFrameCodec codec;
    private SidecarArrivalLagHistogram arrivalLagHistogram;
    private final SidecarPayload[] payloadRing;
    private int payloadRingCursor;

    private final AtomicReference<SidecarPayload> latestPayload;
    private final AtomicLong lastAppliedEvalTick;
    private long lastAckTick;
    private boolean unhealthyLogged;

    public AsyncSidecarAdvisor(int advisorOrdinal, SidecarTickMetrics metrics) {
        this.advisorOrdinal = advisorOrdinal;
        this.metrics = metrics;
        this.intentPool = new IntentPool(advisorOrdinal, 8);
        this.codec = new SidecarFrameCodec();
        this.payloadRing = new SidecarPayload[8];
        for (int i = 0; i < payloadRing.length; i++) {
            payloadRing[i] = new SidecarPayload();
        }
        this.latestPayload = new AtomicReference<>();
        this.lastAppliedEvalTick = new AtomicLong(Long.MIN_VALUE);
    }

    /**
     * Reader thread: version-prefixed frame. Rejects log + increment {@link SidecarTickMetrics} (watchdog separate).
     */
    public void setArrivalLagHistogram(SidecarArrivalLagHistogram histogram) {
        this.arrivalLagHistogram = histogram;
    }

    public void ingestWireFrame(byte[] source, int offset, int length, long readerTickIndex) {
        SidecarPayload parsed = payloadRing[payloadRingCursor];
        if (codec.tryDecodeWireFrame(source, offset, length, parsed, metrics)) {
            if (arrivalLagHistogram != null) {
                arrivalLagHistogram.record(readerTickIndex, parsed.evalTick);
            }
            offerPayload(parsed);
        }
    }

    /** Reader thread: install a freshly parsed payload (CAS ordering). */
    public void offerPayload(SidecarPayload parsed) {
        if (parsed == null) {
            return;
        }
        long eval = parsed.evalTick;
        long prev = lastAppliedEvalTick.get();
        if (eval <= prev) {
            return;
        }
        if (!lastAppliedEvalTick.compareAndSet(prev, eval)) {
            return;
        }
        SidecarPayload copy = payloadRing[payloadRingCursor];
        payloadRingCursor = (payloadRingCursor + 1) % payloadRing.length;
        copy.evalTick = parsed.evalTick;
        copy.ackTick = parsed.ackTick;
        copy.kind = parsed.kind;
        copy.priority = parsed.priority;
        copy.ttlTicks = parsed.ttlTicks;
        copy.advisorOrdinal = parsed.advisorOrdinal;
        copy.precondMask = parsed.precondMask;
        copy.precondHash = parsed.precondHash;
        copy.itemId = parsed.itemId;
        copy.npcIndex = parsed.npcIndex;
        copy.slotIndex = parsed.slotIndex;
        latestPayload.set(copy);
        lastAckTick = copy.ackTick;
        unhealthyLogged = false;
    }

    @Override
    public void evaluate(GameState state, TickBus bus) {
        if (!enabled()) {
            return;
        }
        long tick = state.tickIndex();
        long ackLag = tick - lastAckTick;
        if (metrics != null) {
            metrics.setSidecarAckLag(ackLag);
        }
        if (lastAckTick > 0L && ackLag > UNHEALTHY_LAG_TICKS) {
            if (metrics != null) {
                metrics.setSidecarHealthy(false);
            }
            return;
        }
        if (metrics != null) {
            metrics.setSidecarHealthy(true);
        }
        SidecarPayload payload = latestPayload.get();
        if (payload == null || payload.kind == ActionKind.IDLE) {
            return;
        }
        if (tick < payload.evalTick || tick > payload.evalTick + (long) payload.ttlTicks) {
            return;
        }
        int fp = state.getFingerprint();
        if (payload.precondMask != 0) {
            if ((fp & payload.precondMask) != (payload.precondHash & payload.precondMask)) {
                return;
            }
        } else if (metrics != null) {
            metrics.noteMasklessIntent();
        }
        if (observeOnly()) {
            return;
        }
        intentPool.beginEvaluate();
        Intent intent = intentPool.obtain(
                payload.kind,
                payload.priority,
                payload.itemId,
                payload.npcIndex,
                payload.slotIndex,
                payload.evalTick,
                payload.ttlTicks,
                payload.precondHash,
                payload.precondMask,
                com.bot.core.bus.IntentBylines.SIDECAR_WIRE);
        if (intent != null) {
            bus.publish(intent);
            intentPool.release();
        }
    }
}
