package com.automation.core.model;

import java.util.Objects;

/**
 * Immutable snapshot of a single actor (the local player or the current target).
 *
 * @param currentHp            current hitpoints
 * @param maxHp                maximum hitpoints
 * @param prayerPoints         current prayer points
 * @param specialAttackEnergy  special-attack energy, 0..100
 * @param animationId          current animation id, or {@code -1} when idle
 * @param prayers              active prayers
 * @param equipment            worn gear
 */
public record PlayerState(
        int currentHp,
        int maxHp,
        int prayerPoints,
        int specialAttackEnergy,
        int animationId,
        ActivePrayers prayers,
        EquipmentSnapshot equipment) {

    public PlayerState {
        Objects.requireNonNull(prayers, "prayers");
        Objects.requireNonNull(equipment, "equipment");
    }

    /** Current HP as a 0..100 percentage of max; 0 when {@code maxHp <= 0}. */
    public int hpPercent() {
        return maxHp <= 0 ? 0 : (int) Math.round(currentHp * 100.0 / maxHp);
    }

    public boolean isAnimating() {
        return animationId >= 0;
    }
}
