package com.automation.core.model;

import java.util.Objects;

/**
 * Cast a spell (e.g. Ice Barrage, Vengeance) optionally on a specific target.
 *
 * @param spellName   engine-neutral spell identifier / display name
 * @param targetIndex client target index, or {@link #NO_TARGET} for self / no target
 * @param priority    tier to resolve at (defaults to {@link ActionPriority#SPECIAL_ATTACK})
 */
public record CastSpellAction(String spellName, int targetIndex, ActionPriority priority) implements ActionIntent {

    /** Sentinel for a self-cast or untargeted spell. */
    public static final int NO_TARGET = -1;

    public CastSpellAction {
        Objects.requireNonNull(spellName, "spellName");
        Objects.requireNonNull(priority, "priority");
    }

    /** Cast on a target at the default {@link ActionPriority#SPECIAL_ATTACK} tier. */
    public CastSpellAction(String spellName, int targetIndex) {
        this(spellName, targetIndex, ActionPriority.SPECIAL_ATTACK);
    }
}
