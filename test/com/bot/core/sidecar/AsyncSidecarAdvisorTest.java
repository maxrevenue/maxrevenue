package com.bot.core.sidecar;

import com.bot.core.bus.TickBus;
import com.bot.core.StubCombatState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncSidecarAdvisorTest {

    @BeforeEach
    void enableSidecarFlag() {
        System.setProperty("roatz.sidecar", "test");
    }

    @Test
    void masklessIntentIncrementsCounter() {
        SidecarTickMetrics metrics = new SidecarTickMetrics();
        AsyncSidecarAdvisor advisor = new AsyncSidecarAdvisor(9, metrics);
        SidecarPayload payload = new SidecarPayload();
        payload.evalTick = 5L;
        payload.ackTick = 5L;
        payload.ttlTicks = 1;
        payload.precondMask = 0;
        payload.precondHash = 0;
        payload.kind = com.bot.core.bus.ActionKind.ATTACK;
        payload.priority = com.bot.core.bus.ActionPriority.OFFENSIVE;
        advisor.offerPayload(payload);

        StubCombatState state = new StubCombatState(5L);
        TickBus bus = new TickBus();
        bus.beginTick(5L);
        advisor.evaluate(state, bus);
        assertEquals(1, metrics.masklessIntents());
        assertTrue(bus.size() > 0);
    }

    @Test
    void wireTtlOneAllowsEvalTickAndNext() {
        SidecarTickMetrics metrics = new SidecarTickMetrics();
        AsyncSidecarAdvisor advisor = new AsyncSidecarAdvisor(9, metrics);
        SidecarPayload payload = new SidecarPayload();
        payload.evalTick = 100L;
        payload.ackTick = 100L;
        payload.ttlTicks = 1;
        payload.precondMask = 0xFF;
        payload.precondHash = 0x12;
        payload.kind = com.bot.core.bus.ActionKind.SPECIAL;
        payload.priority = com.bot.core.bus.ActionPriority.OFFENSIVE;
        advisor.offerPayload(payload);

        StubCombatState state = new StubCombatState(101L);
        state.fingerprint = 0x12;
        TickBus bus = new TickBus();
        bus.beginTick(101L);
        advisor.evaluate(state, bus);
        assertEquals(1, bus.size());

        state.tick = 102L;
        bus.clear();
        bus.beginTick(102L);
        advisor.evaluate(state, bus);
        assertEquals(0, bus.size());
    }
}
