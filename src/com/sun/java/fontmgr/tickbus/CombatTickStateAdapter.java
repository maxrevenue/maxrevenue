package com.sun.java.fontmgr.tickbus;

import com.bot.core.model.CombatTickState;
import com.sun.java.fontmgr.CombatScript;

/**
 * Maps live {@link CombatScript} fields into {@link CombatTickState} without allocation.
 */
public final class CombatTickStateAdapter implements CombatTickState {

    private final CombatScript script;
    private int tick;

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
        int fp = 0;
        int hp = script.readLocalHpPublic();
        if (hp >= 0) {
            fp |= (hp & 0xFF);
        }
        int spec = script.specEnergy;
        if (spec >= 0) {
            fp |= (spec & 0xFF) << 16;
        }
        return fp;
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
}
