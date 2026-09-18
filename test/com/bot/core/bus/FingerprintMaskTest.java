package com.bot.core.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FingerprintMaskTest {

    private static final int HP_MASK = 0x0000_00FF;
    private static final int SPEC_MASK = 0x00FF_0000;

    @Test
    void unconditionalWhenMaskZero() {
        Intent intent = new IntentPool(0, 1).obtain(
                ActionKind.ATTACK, ActionPriority.OFFENSIVE, 0, 0, 0, 1L, 5, 0, 0);
        assertTrue(intent.precondSatisfied(0xDEAD_BEEF));
    }

    @Test
    void passesWhenUnrelatedBitsChange() {
        Intent intent = new IntentPool(1, 1).obtain(
                ActionKind.EAT, ActionPriority.CRITICAL, 385, 0, 0, 10L, 3,
                0x0000_0042, HP_MASK);
        assertTrue(intent.precondSatisfied(0x0000_0042));
        assertTrue(intent.precondSatisfied(0x00FF_0042));
        assertTrue(intent.precondSatisfied(0x1234_0042));
    }

    @Test
    void failsWhenMaskedBitsChange() {
        Intent intent = new IntentPool(2, 1).obtain(
                ActionKind.SPECIAL, ActionPriority.OFFENSIVE, 0, 0, 0, 20L, 2,
                0x0032_0000, SPEC_MASK);
        assertTrue(intent.precondSatisfied(0x0032_0000));
        assertTrue(intent.precondSatisfied(0x0032_00FF));
        assertFalse(intent.precondSatisfied(0x0040_0000));
    }
}
