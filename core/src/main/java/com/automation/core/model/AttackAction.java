package com.automation.core.model;

import java.util.Objects;

/**
 * Attack (or re-attack) a target.
 *
 * @param targetIndex client target index, or {@link #LAST_TARGET} to re-attack the current target
 * @param priority    tier to resolve at (defaults to {@link ActionPriority#ATTACK})
 */
public record AttackAction(int targetIndex, ActionPriority priority) implements ActionIntent {

    /** Sentinel meaning "re-attack whatever we are already fighting". */
    public static final int LAST_TARGET = -1;

    public AttackAction {
        Objects.requireNonNull(priority, "priority");
    }

    /** Re-attack the current target at the default {@link ActionPriority#ATTACK} tier. */
    public AttackAction(int targetIndex) {
        this(targetIndex, ActionPriority.ATTACK);
    }
}
