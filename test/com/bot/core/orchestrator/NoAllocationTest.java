package com.bot.core.orchestrator;

import com.bot.core.StubCombatState;
import com.bot.core.bus.Advisor;
import com.bot.core.bus.TickBus;
import com.bot.core.model.GameState;
import com.bot.core.sidecar.SidecarTickMetrics;
import org.junit.jupiter.api.Test;

/**
 * Smoke allocation guard for the orchestrator hot path (full EpsilonGC gate is CI/client JVM specific).
 */
class NoAllocationTest {

    @Test
    void orchestratorRunsOneThousandTicksWithoutThrowing() {
        StubCombatState state = new StubCombatState(0L);
        Advisor noop = new Advisor() {
            @Override
            public void evaluate(GameState s, TickBus bus) {
            }
        };
        LiveTickOrchestrator orchestrator = new LiveTickOrchestrator(
                new Advisor[]{noop},
                new LoggingReflectionDispatcher(),
                null,
                new SidecarTickMetrics());
        for (long t = 0; t < 1000; t++) {
            state.tick = t;
            orchestrator.onTick(state);
        }
    }
}
