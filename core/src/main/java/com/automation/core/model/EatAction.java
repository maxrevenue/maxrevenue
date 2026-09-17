package com.automation.core.model;

import java.util.Objects;

/**
 * Eat / drink the item in the given inventory slot.
 *
 * @param inventorySlot zero-based inventory slot to click
 * @param priority      tier to resolve at (defaults to {@link ActionPriority#EMERGENCY_HEAL})
 */
public record EatAction(int inventorySlot, ActionPriority priority) implements ActionIntent {

    public EatAction {
        if (inventorySlot < 0) {
            throw new IllegalArgumentException("inventorySlot must be >= 0, was " + inventorySlot);
        }
        Objects.requireNonNull(priority, "priority");
    }

    /** Emergency heal at the default {@link ActionPriority#EMERGENCY_HEAL} tier. */
    public EatAction(int inventorySlot) {
        this(inventorySlot, ActionPriority.EMERGENCY_HEAL);
    }
}
