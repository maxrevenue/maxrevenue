package com.bot.core.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FingerprintCoverageTest {

    @Test
    void registeredFieldsFlipBitsIndependently() {
        int base = FingerprintRegistry.compose(40, 50, false, false);
        int hpBump = FingerprintRegistry.compose(41, 50, false, false);
        int specBump = FingerprintRegistry.compose(40, 51, false, false);
        int foodBump = FingerprintRegistry.compose(40, 50, true, false);
        int prayerBump = FingerprintRegistry.compose(40, 50, false, true);

        assertNotEquals(base, hpBump);
        assertNotEquals(base, specBump);
        assertNotEquals(base, foodBump);
        assertNotEquals(base, prayerBump);

        assertEquals(41, FingerprintRegistry.extract(hpBump, FingerprintRegistry.LOCAL_HP_SHIFT,
                FingerprintRegistry.LOCAL_HP_WIDTH));
        assertEquals(51, FingerprintRegistry.extract(specBump, FingerprintRegistry.SPEC_ENERGY_SHIFT,
                FingerprintRegistry.SPEC_ENERGY_WIDTH));
    }
}
