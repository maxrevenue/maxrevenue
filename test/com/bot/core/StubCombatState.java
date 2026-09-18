package com.bot.core;

import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.FingerprintRegistry;
import com.bot.core.telemetry.ReplayProjection;

public final class StubCombatState implements CombatTickState {

    public long tick;
    public int fingerprint;
    public int spec = 100;
    public long specAvailableFromTick;
    public int equippedItemId = -1;
    public int hp = -1;
    public int eatThreshold = 32;
    public int protectPrayerMask;
    public int foodSlotIndex = -1;
    public int damageTaken;

    public StubCombatState(long tick) {
        this.tick = tick;
    }

    @Override
    public long tickIndex() {
        return tick;
    }

    @Override
    public int getFingerprint() {
        if (fingerprint != 0) {
            return fingerprint;
        }
        return FingerprintRegistry.compose(hp, spec, foodSlotIndex >= 0, protectPrayerMask != 0);
    }

    @Override
    public void fillReplayProjection(ReplayProjection projection) {
        projection.clear();
        projection.localHp = hp;
        projection.specEnergy = spec;
        projection.specAvailableFromTick = specAvailableFromTick;
        projection.eatThreshold = eatThreshold;
        projection.protectPrayerMask = protectPrayerMask;
        projection.foodSlotIndex = foodSlotIndex;
        projection.damageTaken = damageTaken;
        projection.fingerprint = getFingerprint();
    }

    @Override
    public int specEnergyPercent() {
        return spec;
    }

    @Override
    public long specAvailableFromTick() {
        return specAvailableFromTick;
    }

    @Override
    public boolean isItemEquipped(int itemId) {
        return equippedItemId == itemId;
    }

    @Override
    public int localHp() {
        return hp;
    }

    @Override
    public int eatThreshold() {
        return eatThreshold;
    }

    @Override
    public int protectPrayerMask() {
        return protectPrayerMask;
    }
}
