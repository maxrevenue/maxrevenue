package com.automation.core.engine.spec;

import com.automation.core.model.GameState;
import com.automation.core.model.PlayerState;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Shared helpers for weapon-bound spec strategies (gear check, target HP KO band).
 */
abstract class AbstractWeaponSpecStrategy implements SpecStrategy {

    private final String label;
    private final int minSpecPct;
    private final Set<Integer> weaponItemIds;
    private final int maxTargetHpInclusive;

    AbstractWeaponSpecStrategy(String label,
                               int minSpecPct,
                               int[] weaponItemIds,
                               int maxTargetHpInclusive) {
        this.label = Objects.requireNonNull(label, "label");
        this.minSpecPct = minSpecPct;
        this.weaponItemIds = Set.copyOf(Arrays.stream(weaponItemIds).boxed().toList());
        this.maxTargetHpInclusive = maxTargetHpInclusive;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public int minSpecPct() {
        return minSpecPct;
    }

    @Override
    public boolean shouldExecute(GameState state) {
        if (state == null || !state.hasTarget()) {
            return false;
        }
        PlayerState local = state.localPlayer();
        if (local == null || local.specialAttackEnergy() < minSpecPct()) {
            return false;
        }
        if (!hasWeaponAvailable(state)) {
            return false;
        }
        PlayerState target = state.target().orElse(null);
        if (target == null) {
            return false;
        }
        int hp = target.currentHp();
        return hp > 0 && hp <= maxTargetHpInclusive;
    }

    private boolean hasWeaponAvailable(GameState state) {
        PlayerState local = state.localPlayer();
        if (local == null) {
            return false;
        }
        if (local.equipment().weaponId().stream().anyMatch(weaponItemIds::contains)) {
            return true;
        }
        for (int id : weaponItemIds) {
            if (local.equipment().isWearing(id) || state.inventory().contains(id)) {
                return true;
            }
        }
        return false;
    }
}
