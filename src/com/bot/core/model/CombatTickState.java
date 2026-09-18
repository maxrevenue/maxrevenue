package com.bot.core.model;

import com.bot.core.telemetry.ReplayProjection;

/**
 * {@link GameState} plus combat fields required by {@link com.bot.core.orchestrator.ChannelRules}
 * and {@link com.bot.core.orchestrator.SuppressionTable}.
 */
public interface CombatTickState extends GameState {

    /** Special attack energy 0–100, or {@code -1} if unknown. */
    int specEnergyPercent();

    /** Tick index after which SPECIAL may fire again ({@code tickIndex} must be {@code >=} this). */
    long specAvailableFromTick();

    /** True when {@code itemId} is already worn (weapon or gear slot). */
    boolean isItemEquipped(int itemId);

    /** Local HP for overlay HUD, or {@code -1} if unknown. */
    default int localHp() {
        return -1;
    }

    /** Legacy eat threshold ({@code comboEatHpThreshold}) for suppression early-release. */
    default int eatThreshold() {
        return -1;
    }

    /** Non-zero when overhead protect prayer observed active. */
    default int protectPrayerMask() {
        return 0;
    }

    /**
     * Fills replay projection (registry ∪ vitals ∪ suppression-release inputs). No allocation.
     */
    void fillReplayProjection(ReplayProjection projection);
}
