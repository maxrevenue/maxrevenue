package com.bot.core.sidecar;

/**
 * Per-tick sidecar counters surfaced in NDJSON (tick thread only writes ints).
 */
public final class SidecarTickMetrics {

    private int masklessIntents;
    private long sidecarAckLag;
    private boolean sidecarHealthy;

    public void beginTick() {
        masklessIntents = 0;
        sidecarAckLag = 0L;
        sidecarHealthy = true;
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
}
