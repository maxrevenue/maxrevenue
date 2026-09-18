package com.bot.core.sidecar;

/**
 * Per-tick sidecar counters surfaced in NDJSON (tick thread only writes ints).
 */
public final class SidecarTickMetrics {

    private int masklessIntents;
    private long sidecarAckLag;
    private boolean sidecarHealthy;
    private int wireFrameRejects;

    public void beginTick() {
        masklessIntents = 0;
        sidecarAckLag = 0L;
        sidecarHealthy = true;
        wireFrameRejects = 0;
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

    /** Version / length / decode reject (log + count; never silent close). */
    public void noteWireFrameReject() {
        wireFrameRejects++;
    }

    public int wireFrameRejects() {
        return wireFrameRejects;
    }
}
