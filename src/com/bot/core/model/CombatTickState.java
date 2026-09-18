package com.bot.core.model;

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
}
