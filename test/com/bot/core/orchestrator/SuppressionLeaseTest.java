package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;
import com.bot.core.bus.Advisor;
import com.bot.core.bus.IntentPool;
import com.bot.core.model.GameState;
import com.bot.core.bus.TickBus;
import com.bot.core.StubCombatState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuppressionLeaseTest {

    @Test
    void eatWinnerSuppressesOffensiveSameTickViaChannelRules() {
        StubCombatState state = new StubCombatState(10L);
        LoggingReflectionDispatcher dispatcher = new LoggingReflectionDispatcher();
        LiveTickOrchestrator orchestrator = new LiveTickOrchestrator(
                new Advisor[]{new EatThenAttackAdvisor()}, dispatcher, null, null);
        orchestrator.onTick(state);
        assertEquals(1, dispatcher.dispatchCount);
        assertEquals(13L, orchestrator.suppression().deadlineFor(ActionKind.EAT));
    }

    @Test
    void eatLeaseBlocksReEatForThreeTicks() {
        StubCombatState state = new StubCombatState(0L);
        EatAdvisor eatAdvisor = new EatAdvisor();
        LoggingReflectionDispatcher dispatcher = new LoggingReflectionDispatcher();
        LiveTickOrchestrator orchestrator = new LiveTickOrchestrator(
                new Advisor[]{eatAdvisor}, dispatcher, null, null);
        orchestrator.onTick(state);
        assertEquals(1, dispatcher.dispatchCount);
        state.tick = 1L;
        orchestrator.onTick(state);
        assertEquals(1, dispatcher.dispatchCount);
        state.tick = 3L;
        orchestrator.onTick(state);
        assertEquals(2, dispatcher.dispatchCount);
    }

    private static final class EatAdvisor implements Advisor {
        private final IntentPool pool = new IntentPool(1, 2);

        @Override
        public void evaluate(GameState state, TickBus bus) {
            pool.beginEvaluate();
            bus.publish(pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 385, 0, 0,
                    state.tickIndex(), 2, 0, 0));
        }
    }

    private static final class EatThenAttackAdvisor implements Advisor {
        private final IntentPool pool = new IntentPool(2, 4);

        @Override
        public void evaluate(GameState state, TickBus bus) {
            pool.beginEvaluate();
            bus.publish(pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 385, 0, 0,
                    state.tickIndex(), 2, 0, 0));
            bus.publish(pool.obtain(ActionKind.ATTACK, ActionPriority.OFFENSIVE, 0, 0, 0,
                    state.tickIndex(), 2, 0, 0));
        }
    }

}
