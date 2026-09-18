package com.bot.core.golden;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoldenParityClassifierTest {

    @Test
    void stubOrchSkippedExcludedFromParity() {
        ParityInput input = new ParityInput(1L, "", "EAT@1", "", "COMBO_PHASE");
        assertEquals(GoldenParityClassifier.Category.ORCH_SKIPPED,
                GoldenParityClassifier.classify(input));
    }

}
