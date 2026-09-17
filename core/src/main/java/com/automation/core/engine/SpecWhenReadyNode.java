package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;
import com.automation.core.model.SpecAction;

import java.util.List;

/**
 * Leaf rule: if there is a target and we have enough special-attack energy,
 * emit a {@link SpecAction}. Weapon-agnostic in the skeleton — the concrete
 * spec setup is resolved by the dispatcher.
 */
public final class SpecWhenReadyNode implements BehaviorNode {

    private final int minSpecPct;
    private final String weaponLabel;

    public SpecWhenReadyNode(int minSpecPct) {
        this(minSpecPct, "primary");
    }

    public SpecWhenReadyNode(int minSpecPct, String weaponLabel) {
        this.minSpecPct = minSpecPct;
        this.weaponLabel = weaponLabel;
    }

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        if (!state.hasTarget()) {
            return List.of();
        }
        if (state.localPlayer().specialAttackEnergy() < minSpecPct) {
            return List.of();
        }
        return List.of(new SpecAction(weaponLabel, minSpecPct));
    }
}
