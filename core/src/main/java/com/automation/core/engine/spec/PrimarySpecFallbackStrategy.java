package com.automation.core.engine.spec;

import com.automation.core.model.GameState;

/**
 * Weapon-agnostic fallback when energy is available but no dedicated
 * {@link SpecStrategy} matched — preserves legacy {@code SPEC:primary} labels
 * from EchoForge recordings.
 */
public final class PrimarySpecFallbackStrategy implements SpecStrategy {

    private final int minSpecPct;

    public PrimarySpecFallbackStrategy(int minSpecPct) {
        this.minSpecPct = minSpecPct;
    }

    @Override
    public String label() {
        return "primary";
    }

    @Override
    public int minSpecPct() {
        return minSpecPct;
    }

    @Override
    public boolean isSpecAnim(int anim) {
        return false;
    }

    @Override
    public boolean shouldExecute(GameState state) {
        if (state == null || !state.hasTarget()) {
            return false;
        }
        return state.localPlayer().specialAttackEnergy() >= minSpecPct();
    }
}
