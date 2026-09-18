package com.bot.core.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sidecar grace window (eval at N, ttl 1 → publishable N and N+1) maps to {@code ttlTicks = 2}
 * under {@code isValidAt}: {@code tick < bornTick + ttlTicks}.
 */
class IntentFreshnessTest {

    @Test
    void ttlOneGraceIsValidOnEvalTickAndNext() {
        Intent intent = new IntentPool(0, 1).obtain(
                ActionKind.ATTACK, ActionPriority.OFFENSIVE, 0, 0, 0, 100L, 2, 0, 0);
        assertTrue(intent.isValidAt(100L));
        assertTrue(intent.isValidAt(101L));
        assertFalse(intent.isValidAt(102L));
    }
}
