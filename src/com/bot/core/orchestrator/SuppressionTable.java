package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;

/**
 * Per-kind tick deadlines (lease ends when {@code tickIndex >= deadline}).
 *
 * <p>Leases also {@link #onReleaseInputs early-release} from replay projection (see wire contract).
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
    /**
     * Early-release inputs from {@link com.bot.core.telemetry.ReplayProjection}.
     *
     * <p>EAT/SIP: {@code localHp > eatThreshold} where {@code eatThreshold} is
     * {@code CombatScript.comboEatHpThreshold} — mirrors {@code SustainAdvisor}
     * ({@code hp <= threshold} triggers eat; above band invalidates eat-lease purpose).
     */
    public void onReleaseInputs(int localHp, int eatThreshold, int protectPrayerMask, long tickIndex) {
        if (localHp >= 0) {
            lastObservedHp = localHp;
        }
        if (localHp >= 0 && eatThreshold >= 0 && localHp > eatThreshold) {
            expireNow(ActionKind.EAT, tickIndex);
            expireNow(ActionKind.SIP, tickIndex);
        }
        if (protectPrayerMask == 0) {
            onPrayerInactive(tickIndex);
        }
    }

    /** PRAYER flick lease: release when overhead protect inactive ({@link FingerprintRegistry#PROTECT_PRAYER_MASK}). */
    public void onPrayerInactive(long tickIndex) {
        expireNow(ActionKind.PRAYER, tickIndex);
    }

    private void expireNow(ActionKind kind, long tickIndex) {
        int ord = kind.ordinal();
        kindDeadlineUntil[ord] = tickIndex;
    }
}
