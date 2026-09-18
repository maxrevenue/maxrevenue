package com.bot.overlay;

import com.bot.core.StubCombatState;
import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;
import com.bot.core.bus.IntentPool;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.orchestrator.SuppressionTable;
import com.bot.core.telemetry.TickDispatchState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayPublisherTest {

    @Test
    void doubleBufferHandoffVisibleToReader() {
        System.setProperty("roatz.overlay", "true");
        StubCombatState state = new StubCombatState(42L);
        state.hp = 55;
        state.spec = 75;
        IntentPool pool = new IntentPool(1, 2);
        com.bot.core.bus.Intent eat = pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 0, 0, 3,
                42L, 1, 0, 0);
        com.bot.core.bus.Intent[] winners = new com.bot.core.bus.Intent[]{null, eat, null};
        int[] dispatch = new int[]{
                TickDispatchState.NO_DISPATCHER.ordinal(),
                TickDispatchState.WIRED.ordinal(),
                TickDispatchState.NO_DISPATCHER.ordinal()
        };
        EliminationReason[] drops = new EliminationReason[]{
                EliminationReason.SUPPRESSED_BY_RULE,
                EliminationReason.NONE,
                EliminationReason.NONE
        };
        OverlayPublisher.publish(state, winners, dispatch, drops, new SuppressionTable(), null);
        OverlayState visible = OverlayPublisher.current();
        assertNotNull(visible);
        assertEquals(42L, visible.tickIndex);
        assertEquals(55, visible.hp);
        assertTrue(visible.winners[1].active);
        assertEquals(ActionKind.EAT.ordinal(), visible.winners[1].kindOrdinal);
        assertEquals(EliminationReason.SUPPRESSED_BY_RULE.ordinal(), visible.winners[0].dropReasonOrdinal);
    }
}
