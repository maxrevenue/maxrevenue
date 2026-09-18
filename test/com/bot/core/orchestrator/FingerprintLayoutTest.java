package com.bot.core.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintLayoutTest {

    @Test
    void registeredFieldsDoNotOverlapAndFitIn32Bits() {
        int[] shifts = {
                FingerprintRegistry.LOCAL_HP_SHIFT,
                FingerprintRegistry.FOOD_PRESENT_SHIFT,
                FingerprintRegistry.PROTECT_PRAYER_SHIFT,
                FingerprintRegistry.SPEC_ENERGY_SHIFT
        };
        int[] widths = {
                FingerprintRegistry.LOCAL_HP_WIDTH,
                FingerprintRegistry.FOOD_PRESENT_WIDTH,
                FingerprintRegistry.PROTECT_PRAYER_WIDTH,
                FingerprintRegistry.SPEC_ENERGY_WIDTH
        };
        for (int i = 0; i < shifts.length; i++) {
            assertTrue(shifts[i] + widths[i] <= 32);
            for (int j = i + 1; j < shifts.length; j++) {
                int a0 = shifts[i];
                int a1 = shifts[i] + widths[i];
                int b0 = shifts[j];
                int b1 = shifts[j] + widths[j];
                boolean disjoint = a1 <= b0 || b1 <= a0;
                assertTrue(disjoint, "overlap between field " + i + " and " + j);
            }
        }
        assertTrue(FingerprintRegistry.registeredWidthSum() <= 32);
    }
}
