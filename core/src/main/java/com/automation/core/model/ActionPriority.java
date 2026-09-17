package com.automation.core.model;

/**
 * Relative execution order for {@link ActionIntent}s produced in a single tick.
 *
 * <p>The {@code level} is what the coordinator sorts on (highest first), so an
 * emergency heal always resolves before a prayer switch, which resolves before a
 * special attack, and so on. Keeping the ordering in one enum means the decision
 * engine never has to reason about absolute integers.
 */
public enum ActionPriority {

    /** Survive first: eat / pot off an incoming kill. */
    EMERGENCY_HEAL(100),
    /** Flick the correct overhead / offensive prayer. */
    PRAYER(80),
    /** Fire a special attack or offensive spell. */
    SPECIAL_ATTACK(60),
    /** Gear switch (tank up, weapon swap). */
    GEAR(40),
    /** Baseline re-attack / auto-retaliate. */
    ATTACK(20);

    private final int level;

    ActionPriority(int level) {
        this.level = level;
    }

    /** Higher wins. Used by the coordinator's descending sort. */
    public int level() {
        return level;
    }
}
