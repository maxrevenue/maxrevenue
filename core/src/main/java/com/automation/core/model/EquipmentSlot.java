package com.automation.core.model;

/**
 * Worn equipment slots, mapped to the client's worn-slot indices.
 *
 * <p>Kept in the model (not the sensor) so decision logic can reason about gear
 * without touching any client class.
 */
public enum EquipmentSlot {
    HEAD(0),
    CAPE(1),
    AMULET(2),
    WEAPON(3),
    BODY(4),
    SHIELD(5),
    LEGS(7),
    HANDS(9),
    FEET(10),
    RING(12),
    AMMO(13);

    private final int index;

    EquipmentSlot(int index) {
        this.index = index;
    }

    /** The client worn-slot index this maps to. */
    public int index() {
        return index;
    }
}
