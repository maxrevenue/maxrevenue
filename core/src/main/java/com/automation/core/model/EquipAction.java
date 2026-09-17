package com.automation.core.model;

import java.util.Objects;

/**
 * Wield / wear an item, identified by both id and the slot it currently sits in
 * (the dispatcher may use either depending on the client interface available).
 *
 * @param itemId        item id to equip
 * @param inventorySlot slot the item currently occupies, or {@code -1} if unknown
 * @param priority      tier to resolve at (defaults to {@link ActionPriority#GEAR})
 */
public record EquipAction(int itemId, int inventorySlot, ActionPriority priority) implements ActionIntent {

    public EquipAction {
        Objects.requireNonNull(priority, "priority");
    }

    /** Gear switch at the default {@link ActionPriority#GEAR} tier. */
    public EquipAction(int itemId, int inventorySlot) {
        this(itemId, inventorySlot, ActionPriority.GEAR);
    }
}
