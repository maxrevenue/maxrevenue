package com.bot.core.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyComparabilityTest {

    @Test
    void subtypesAtClassificationTime() {
        assertEquals(LegacyComparability.Subtype.NO_OPINION, LegacyComparability.classify(""));
        assertEquals(LegacyComparability.Subtype.NO_OPINION, LegacyComparability.classify(null));
        assertEquals(LegacyComparability.Subtype.OUT_OF_VOCAB, LegacyComparability.classify("UNKNOWN_FOO"));
        assertEquals(LegacyComparability.Subtype.COMPARABLE, LegacyComparability.classify("EAT@385"));
        assertEquals("NO_OPINION", LegacyComparability.subtypeJson(LegacyComparability.Subtype.NO_OPINION));
    }
}
