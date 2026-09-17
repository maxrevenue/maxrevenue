package com.automation.core.model;

/**
 * A single occupied inventory slot.
 *
 * @param slot     zero-based inventory slot
 * @param itemId   item id in the slot
 * @param quantity stack size (1 for non-stackable items)
 */
public record InventoryItem(int slot, int itemId, int quantity) {

    public InventoryItem {
        if (slot < 0) {
            throw new IllegalArgumentException("slot must be >= 0, was " + slot);
        }
    }
}
