package com.bot.core.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire {@code ttlTicks=1} → publishable at evalTick N and N+1 (inclusive grace). */
class IntentFreshnessTest {

    @Test
    void wireTtlOneIsValidOnEvalTickAndNext() {
        Intent intent = new IntentPool(0, 1).obtain(
                ActionKind.ATTACK, ActionPriority.OFFENSIVE, 0, 0, 0, 100L, 1, 0, 0);
        assertTrue(intent.isValidAt(100L));
        assertTrue(intent.isValidAt(101L));
        assertFalse(intent.isValidAt(99L));
        assertFalse(intent.isValidAt(102L));
    }

    @Test
    void wireTtlZeroIsEvalTickOnly() {
        Intent intent = new IntentPool(0, 1).obtain(
                ActionKind.ATTACK, ActionPriority.OFFENSIVE, 0, 0, 0, 50L, 0, 0, 0);
        assertTrue(intent.isValidAt(50L));
        assertFalse(intent.isValidAt(51L));
    }
}
