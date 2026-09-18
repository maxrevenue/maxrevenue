package com.bot.core;

import com.bot.core.model.CombatTickState;

public final class StubCombatState implements CombatTickState {

    public long tick;
    public int fingerprint;
    public int spec = 100;
    public long specAvailableFromTick;
    public int equippedItemId = -1;

    public StubCombatState(long tick) {
        this.tick = tick;
    }

    @Override
    public long tickIndex() {
        return tick;
    }

    @Override
    public int getFingerprint() {
        return fingerprint;
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
}
