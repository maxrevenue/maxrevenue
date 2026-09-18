package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;
import com.bot.core.bus.Advisor;
import com.bot.core.bus.IntentPool;
import com.bot.core.bus.TickBus;
import com.bot.core.model.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Offline round-trip: fixed advisor publishes → orchestrator resolves → assert winners per tick.
 */
class GoldenReplayOrchestratorTest {

    @Test
    void deterministicWinnersAcrossTicks() {
        ScriptedAdvisor advisor = new ScriptedAdvisor();
        com.bot.core.StubCombatState state = new com.bot.core.StubCombatState(0L);
        LoggingReflectionDispatcher dispatcher = new LoggingReflectionDispatcher();
        LiveTickOrchestrator orchestrator = new LiveTickOrchestrator(
                new Advisor[]{advisor}, dispatcher, null, null);
        for (long tick = 0; tick < 5; tick++) {
            state.tick = tick;
            advisor.setTick(tick);
            orchestrator.onTick(state);
        }
        assertEquals(1, dispatcher.dispatchCount);
    }

    private static final class ScriptedAdvisor implements Advisor {
        private final IntentPool pool = new IntentPool(3, 4);
        private long tick;

        void setTick(long tick) {
            this.tick = tick;
        }

        @Override
        public void evaluate(GameState state, TickBus bus) {
            pool.beginEvaluate();
            if (tick == 2L) {
                bus.publish(pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 385, 0, 0,
                        tick, 1, 0, 0));
            }
        }
    }
}
