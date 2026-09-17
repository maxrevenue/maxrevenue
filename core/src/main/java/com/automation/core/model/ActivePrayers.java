package com.automation.core.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable snapshot of which {@link Prayer}s are currently active.
 *
 * @param active the set of active prayers (defensively copied and unmodifiable)
 */
public record ActivePrayers(Set<Prayer> active) {

    public ActivePrayers {
        active = (active == null || active.isEmpty())
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(active));
    }

    /** Empty overhead / offensive prayer state. */
    public static ActivePrayers none() {
        return new ActivePrayers(Set.of());
    }

    public boolean isActive(Prayer prayer) {
        return active.contains(prayer);
    }
}
