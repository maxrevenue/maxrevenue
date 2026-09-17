package com.automation.core.model;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable view of a player's worn gear.
 *
 * @param itemIdBySlot item id worn in each populated {@link EquipmentSlot}
 *                     (defensively copied and unmodifiable)
 */
public record EquipmentSnapshot(Map<EquipmentSlot, Integer> itemIdBySlot) {

    public EquipmentSnapshot {
        itemIdBySlot = (itemIdBySlot == null || itemIdBySlot.isEmpty())
                ? Map.of()
                : Map.copyOf(itemIdBySlot);
    }

    /** No gear worn. */
    public static EquipmentSnapshot empty() {
        return new EquipmentSnapshot(Map.of());
    }

    public Optional<Integer> itemAt(EquipmentSlot slot) {
        return Optional.ofNullable(itemIdBySlot.get(slot));
    }

    /** Currently-wielded weapon id, if any. */
    public OptionalInt weaponId() {
        Integer id = itemIdBySlot.get(EquipmentSlot.WEAPON);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    public boolean isWearing(int itemId) {
        return itemIdBySlot.containsValue(itemId);
    }
}
