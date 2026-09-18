package com.bot.core.model;

import com.bot.core.StubCombatState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FingerprintRegistryTest {

    @Test
    void stubStateUsesRegisteredFingerprintFields() {
        StubCombatState state = new StubCombatState(1L);
        state.hp = 42;
        state.spec = 80;
        state.fingerprint = FingerprintLayout.build(state.hp, state.spec);
        assertEquals(42, state.fingerprint & FingerprintLayout.HP_MASK);
        assertEquals(80 << FingerprintLayout.SPEC_SHIFT,
                state.fingerprint & FingerprintLayout.SPEC_MASK);
    }
}
