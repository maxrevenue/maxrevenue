package com.bot.core.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TickBusTieTest {

    @Test
    void equalRankIncumbentWinsByAdvisorOrder() {
        TickBus bus = new TickBus();
        bus.beginTick(7L);
        Intent first = intent(1, ActionKind.ATTACK, ActionPriority.OFFENSIVE, 7L, 3);
        Intent second = intent(2, ActionKind.ATTACK, ActionPriority.OFFENSIVE, 7L, 3);
        bus.publish(first);
        bus.publish(second);
        Intent winner = bus.resolveChannel(Channel.OFFENSIVE);
        assertNotNull(winner);
        assertEquals(1, winner.advisorOrdinal());
    }

    private static Intent intent(int ordinal, ActionKind kind, ActionPriority priority, long bornTick, int ttl) {
        IntentPool pool = new IntentPool(ordinal, 1);
        return pool.obtain(kind, priority, 0, 0, 0, bornTick, ttl, 0, 0);
    }
}
