package com.bot.core.telemetry;

/**
 * Session-level outcome counters derived exclusively from {@link ReplayProjection} transitions.
 * Must not read arbitration winners or dispatch calls.
 */
public final class SessionOutcomeTracker {

    public long eatsUsed;
    public long specsUsed;
    public long prayerUptimeTicks;
    public long damageTaken;

    private int lastFoodSlot = -1;
    private int lastSpecEnergy = -1;
    private int lastProtectMask;

    public void onProjection(long tickIndex, ReplayProjection projection) {
        if (projection.foodSlotIndex >= 0 && lastFoodSlot >= 0
                && projection.foodSlotIndex != lastFoodSlot) {
            eatsUsed++;
        }
        if (projection.foodSlotIndex >= 0) {
            lastFoodSlot = projection.foodSlotIndex;
        }
        if (lastSpecEnergy >= 0 && projection.specEnergy >= 0
                && projection.specEnergy < lastSpecEnergy - 10) {
            specsUsed++;
        }
        if (projection.specEnergy >= 0) {
            lastSpecEnergy = projection.specEnergy;
        }
        if (projection.protectPrayerMask != 0) {
            prayerUptimeTicks++;
        }
        lastProtectMask = projection.protectPrayerMask;
        if (projection.damageTaken > 0) {
            damageTaken += projection.damageTaken;
        }
    }

    public void copyTo(TickRecord target) {
        target.eatsUsed = eatsUsed;
        target.specsUsed = specsUsed;
        target.prayerUptimeTicks = prayerUptimeTicks;
        target.outcomeDamageTaken = damageTaken;
    }
}
