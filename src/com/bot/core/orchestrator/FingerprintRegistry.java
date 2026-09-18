package com.bot.core.orchestrator;

/**
 * Single source of truth for the 32-bit combat fingerprint bit layout.
 */
public final class FingerprintRegistry {

    public static final int LOCAL_HP_SHIFT = 0;
    public static final int LOCAL_HP_WIDTH = 8;
    public static final int LOCAL_HP_MASK = mask(LOCAL_HP_SHIFT, LOCAL_HP_WIDTH);

    public static final int FOOD_PRESENT_SHIFT = 8;
    public static final int FOOD_PRESENT_WIDTH = 1;
    public static final int FOOD_PRESENT_MASK = mask(FOOD_PRESENT_SHIFT, FOOD_PRESENT_WIDTH);

    public static final int PROTECT_PRAYER_SHIFT = 9;
    public static final int PROTECT_PRAYER_WIDTH = 1;
    public static final int PROTECT_PRAYER_MASK = mask(PROTECT_PRAYER_SHIFT, PROTECT_PRAYER_WIDTH);

    public static final int SPEC_ENERGY_SHIFT = 16;
    public static final int SPEC_ENERGY_WIDTH = 8;
    public static final int SPEC_ENERGY_MASK = mask(SPEC_ENERGY_SHIFT, SPEC_ENERGY_WIDTH);

    private FingerprintRegistry() {
    }

    public static int compose(int localHp,
                              int specEnergyPercent,
                              boolean foodPresent,
                              boolean protectPrayerActive) {
        int fp = 0;
        if (localHp >= 0) {
            fp |= (localHp & ((1 << LOCAL_HP_WIDTH) - 1)) << LOCAL_HP_SHIFT;
        }
        if (specEnergyPercent >= 0) {
            fp |= (specEnergyPercent & ((1 << SPEC_ENERGY_WIDTH) - 1)) << SPEC_ENERGY_SHIFT;
        }
        if (foodPresent) {
            fp |= 1 << FOOD_PRESENT_SHIFT;
        }
        if (protectPrayerActive) {
            fp |= 1 << PROTECT_PRAYER_SHIFT;
        }
        return fp;
    }

    public static int extract(int fingerprint, int shift, int width) {
        return (fingerprint >>> shift) & ((1 << width) - 1);
    }

    private static int mask(int shift, int width) {
        return (((1 << width) - 1) << shift);
    }

    /** Sum of registered field widths (must be ≤ 32 with non-overlap — see {@code FingerprintLayoutTest}). */
    public static int registeredWidthSum() {
        return LOCAL_HP_WIDTH + FOOD_PRESENT_WIDTH + PROTECT_PRAYER_WIDTH + SPEC_ENERGY_WIDTH;
    }
}
