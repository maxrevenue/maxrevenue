package com.bot.core.telemetry;

/**
 * Tick-thread primitive snapshot for NDJSON replay (union of registry fields, vitals, suppression inputs).
 *
 * <p>Replay must read, never recompute. Any state an advisor or rule consults must appear here.
 */
public final class ReplayProjection {

    public int fingerprint;
    public int localHp;
    public int specEnergy;
    public long specAvailableFromTick;
    public int eatThreshold;
    public int protectPrayerMask;
    public int foodSlotIndex;
    public int damageTaken;
    public int targetNpcIndex;

    public void clear() {
        fingerprint = 0;
        localHp = -1;
        specEnergy = -1;
        specAvailableFromTick = 0L;
        eatThreshold = -1;
        protectPrayerMask = 0;
        foodSlotIndex = -1;
        damageTaken = 0;
        targetNpcIndex = -1;
    }
}
