package com.bot.core.sidecar;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sidecar counters surfaced in NDJSON. Per-tick fields reset in {@link #beginTick()};
 * {@link #wireRejects()} is session-cumulative (never reset).
 */
public final class SidecarTickMetrics {

    private int masklessIntents;
    private long sidecarAckLag;
    private boolean sidecarHealthy;
    private final AtomicLong wireRejectsSession = new AtomicLong();
    private int staleTickDrops;
    private int staleStateDrops;

    public void beginTick() {
        masklessIntents = 0;
        sidecarAckLag = 0L;
        sidecarHealthy = true;
        staleTickDrops = 0;
        staleStateDrops = 0;
    }

    public void noteMasklessIntent() {
        masklessIntents++;
    }

    public int masklessIntents() {
        return masklessIntents;
    }

    public void setSidecarAckLag(long lag) {
        sidecarAckLag = lag;
    }

    public long sidecarAckLag() {
        return sidecarAckLag;
    }

    public void setSidecarHealthy(boolean healthy) {
        sidecarHealthy = healthy;
    }

    public boolean sidecarHealthy() {
        return sidecarHealthy;
    }

    /** Version / length / decode reject (reader thread safe; session cumulative). */
    public void noteWireFrameReject() {
        wireRejectsSession.incrementAndGet();
    }

    public long wireRejects() {
        return wireRejectsSession.get();
    }

    public void noteStaleTickDrop() {
        staleTickDrops++;
    }

    public int staleTickDrops() {
        return staleTickDrops;
    }

    public void noteStaleStateDrop() {
        staleStateDrops++;
    }

    public int staleStateDrops() {
        return staleStateDrops;
    }
}
