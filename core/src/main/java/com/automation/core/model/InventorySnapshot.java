package com.automation.core.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Immutable view of the player's inventory.
 *
 * @param items the occupied slots (defensively copied and unmodifiable)
 */
public record InventorySnapshot(List<InventoryItem> items) {

    public InventorySnapshot {
        items = (items == null || items.isEmpty()) ? List.of() : List.copyOf(items);
    }

    /** Empty inventory. */
    public static InventorySnapshot empty() {
        return new InventorySnapshot(List.of());
    }

    /** First slot matching {@code predicate}, in slot order as supplied. */
    public Optional<InventoryItem> firstMatching(Predicate<InventoryItem> predicate) {
        return items.stream().filter(predicate).findFirst();
    }

    /** First item whose id is contained in {@code itemIds}. Handy for food tables. */
    public Optional<InventoryItem> firstOfAny(Set<Integer> itemIds) {
        return firstMatching(item -> itemIds.contains(item.itemId()));
    }

    public boolean contains(int itemId) {
        return items.stream().anyMatch(item -> item.itemId() == itemId);
    }
}
