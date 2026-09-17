package com.automation.core.model;

import java.util.Objects;

/**
 * Fire the special attack for the currently-armed spec setup.
 *
 * <p>The weapon-specific mechanics (wield sequence, animation gating, gmaul
 * follow-up) belong to the dispatcher; the decision engine only expresses the
 * <em>intent</em> to spec and the energy it requires.
 *
 * @param weaponLabel human-readable label of the spec setup (e.g. {@code "AGS"})
 * @param minSpecPct  minimum special-attack energy the setup needs
 * @param priority    tier to resolve at (defaults to {@link ActionPriority#SPECIAL_ATTACK})
 */
public record SpecAction(String weaponLabel, int minSpecPct, ActionPriority priority) implements ActionIntent {

    public SpecAction {
        Objects.requireNonNull(weaponLabel, "weaponLabel");
        if (minSpecPct < 0 || minSpecPct > 100) {
            throw new IllegalArgumentException("minSpecPct must be 0..100, was " + minSpecPct);
        }
        Objects.requireNonNull(priority, "priority");
    }

    /** Spec at the default {@link ActionPriority#SPECIAL_ATTACK} tier. */
    public SpecAction(String weaponLabel, int minSpecPct) {
        this(weaponLabel, minSpecPct, ActionPriority.SPECIAL_ATTACK);
    }
}
