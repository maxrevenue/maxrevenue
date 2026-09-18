package com.bot.core.sidecar;

/**
 * Fixed histogram for {@code arrivalTickIndex - targetTickIndex} in [-16, 16].
 * Updated on reader thread; snapshot on writer thread only.
 */
public final class SidecarArrivalLagHistogram {

    public static final int BUCKET_MIN = -16;
    public static final int BUCKET_MAX = 16;
    public static final int BUCKET_COUNT = BUCKET_MAX - BUCKET_MIN + 1;

    private final long[] buckets;

    public SidecarArrivalLagHistogram() {
        buckets = new long[BUCKET_COUNT];
    }

    public void record(long arrivalTickIndex, long targetTickIndex) {
        long lag = arrivalTickIndex - targetTickIndex;
        int bucket = (int) lag;
        if (bucket < BUCKET_MIN) {
            bucket = BUCKET_MIN;
        } else if (bucket > BUCKET_MAX) {
            bucket = BUCKET_MAX;
        }
        buckets[bucket - BUCKET_MIN]++;
    }

    public void snapshotPercentiles(long[] outP50P99) {
        long total = 0L;
        for (int i = 0; i < buckets.length; i++) {
            total += buckets[i];
        }
        if (total == 0L) {
            outP50P99[0] = 0L;
            outP50P99[1] = 0L;
            return;
        }
        outP50P99[0] = percentile(total, 0.50);
        outP50P99[1] = percentile(total, 0.99);
    }

    public long[] bucketsCopy() {
        long[] copy = new long[BUCKET_COUNT];
        System.arraycopy(buckets, 0, copy, 0, BUCKET_COUNT);
        return copy;
    }

    private long percentile(long total, double fraction) {
        long need = (long) Math.ceil(total * fraction);
        long seen = 0L;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= need) {
                return BUCKET_MIN + i;
            }
        }
        return BUCKET_MAX;
    }
}
