package com.bot.core.bus;

import com.bot.core.StubCombatState;
import com.bot.core.orchestrator.LoggingReflectionDispatcher;
import com.bot.core.orchestrator.LiveTickOrchestrator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentPoolOutstandingTest {

    @Test
    void poolsBalancedAfterTwentyTicks() {
        StubCombatState state = new StubCombatState(0L);
        LoggingReflectionDispatcher dispatcher = new LoggingReflectionDispatcher();
        LeakCheckingAdvisor advisor = new LeakCheckingAdvisor();
        LiveTickOrchestrator orchestrator = new LiveTickOrchestrator(
                new Advisor[]{advisor}, dispatcher, null, null);
        for (int i = 0; i < 20; i++) {
            state.tick = i;
            orchestrator.onTick(state);
            assertEquals(0, advisor.pool.checkedOut(), "tick " + i);
        }
    }

    private static final class LeakCheckingAdvisor implements Advisor {
        private final IntentPool pool = new IntentPool(5, 2);

        @Override
        public void evaluate(com.bot.core.model.GameState state, TickBus bus) {
            pool.beginEvaluate();
            Intent eat = pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 385, 0, 0,
                    state.tickIndex(), 2, 0, 0);
            if (eat != null) {
                bus.publish(eat);
                pool.release();
            }
        }
    }
}
