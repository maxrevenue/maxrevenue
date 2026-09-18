package com.sun.java.fontmgr.tickbus;

import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.FingerprintRegistry;
import com.bot.core.telemetry.ReplayProjection;
import com.sun.java.fontmgr.CombatScript;

/**
 * Maps live {@link CombatScript} fields into {@link CombatTickState} without allocation.
 */
public final class CombatTickStateAdapter implements CombatTickState {

    private final CombatScript script;
    private int tick;
    private int lastHp = -1;

    public CombatTickStateAdapter(CombatScript script) {
        this.script = script;
    }

    public void setTick(int tick) {
        this.tick = tick;
    }

    @Override
    public long tickIndex() {
        return tick;
    }

    @Override
    public int getFingerprint() {
        int hp = script.readLocalHpPublic();
        boolean food = script.findHpReducerSlotPublic() >= 0;
        boolean protect = script.protectPrayerMaskForTelemetry() != 0;
        return FingerprintRegistry.compose(hp, script.specEnergy, food, protect);
    }

    @Override
    public void fillReplayProjection(ReplayProjection projection) {
        projection.clear();
        int hp = script.readLocalHpPublic();
        projection.localHp = hp;
        projection.specEnergy = script.specEnergy;
        projection.specAvailableFromTick = tick;
        projection.eatThreshold = script.comboEatHpThreshold;
        projection.foodSlotIndex = script.findHpReducerSlotPublic();
        projection.protectPrayerMask = script.protectPrayerMaskForTelemetry();
        projection.targetNpcIndex = -1;
        if (lastHp >= 0 && hp >= 0 && hp < lastHp) {
            projection.damageTaken = lastHp - hp;
        }
        lastHp = hp;
        projection.fingerprint = FingerprintRegistry.compose(
                hp, script.specEnergy, projection.foodSlotIndex >= 0, projection.protectPrayerMask != 0);
    }

    @Override
    public int specEnergyPercent() {
        return script.specEnergy;
    }

    @Override
    public long specAvailableFromTick() {
        return tick;
    }

    @Override
    public boolean isItemEquipped(int itemId) {
        return script.isWearingItem(itemId);
    }

    @Override
    public int localHp() {
        return script.readLocalHpPublic();
    }

    @Override
    public int eatThreshold() {
        return script.comboEatHpThreshold;
    }

    @Override
    public int protectPrayerMask() {
        return script.protectPrayerMaskForTelemetry();
    }
}
