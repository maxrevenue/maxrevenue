package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;

/**
 * Per-kind tick deadlines (lease ends when {@code tickIndex >= deadline}).
 *
 * <p>Leases also {@link #onVitals(int, long) early-release} on invalidating vitals (see wire contract).
 */
public final class SuppressionTable {

    private final long[] kindDeadlineUntil;
    private final int kindCount;
    private int lastObservedHp = -1;

    public SuppressionTable() {
        kindCount = ActionKind.values().length;
        kindDeadlineUntil = new long[kindCount];
    }

    public boolean isSuppressed(ActionKind kind, long tickIndex) {
        if (kind == null) {
            return false;
        }
        return tickIndex < kindDeadlineUntil[kind.ordinal()];
    }

    public void lease(ActionKind kind, long tickIndex, int durationTicks) {
        if (kind == null || durationTicks <= 0) {
            return;
        }
        long until = tickIndex + (long) durationTicks;
        int ord = kind.ordinal();
        if (until > kindDeadlineUntil[ord]) {
            kindDeadlineUntil[ord] = until;
        }
    }

    public void leaseUntil(ActionKind kind, long untilTick) {
        if (kind == null) {
            return;
        }
        int ord = kind.ordinal();
        if (untilTick > kindDeadlineUntil[ord]) {
            kindDeadlineUntil[ord] = untilTick;
        }
    }

    /** For tests and telemetry snapshots. */
    public long deadlineFor(ActionKind kind) {
        return kindDeadlineUntil[kind.ordinal()];
    }

    /**
     * Early-release leases when vitals invalidate the lease (tick-thread only).
     *
     * @param localHp current local HP or {@code -1} if unknown
     * @param tickIndex current tick (deadline set to this tick = immediately expired)
     */
    public void onVitals(int localHp, long tickIndex) {
        if (localHp >= 0 && lastObservedHp >= 0 && localHp > lastObservedHp) {
            expireNow(ActionKind.EAT, tickIndex);
            expireNow(ActionKind.SIP, tickIndex);
        }
        if (localHp >= 0) {
            lastObservedHp = localHp;
        }
    }

    /** PRAYER flick lease: release when prayer no longer active (fingerprint bit TBD). */
    public void onPrayerInactive(long tickIndex) {
        expireNow(ActionKind.PRAYER, tickIndex);
    }

    private void expireNow(ActionKind kind, long tickIndex) {
        int ord = kind.ordinal();
        kindDeadlineUntil[ord] = tickIndex;
    }
}
