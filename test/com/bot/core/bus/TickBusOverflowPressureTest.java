package com.bot.core.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickBusOverflowPressureTest {

    @Test
    void sustainedPressureEvictsByRankAndAccountsDrops() {
        TickBus bus = new TickBus();
        bus.beginTick(1L);
        IntentPool pool = new IntentPool(0, TickBus.CAPACITY + 5);
        int lowestRank = Integer.MAX_VALUE;
        for (int i = 0; i < TickBus.CAPACITY; i++) {
            ActionPriority priority = ActionPriority.values()[i % ActionPriority.values().length];
            Intent intent = pool.obtain(ActionKind.ATTACK, priority, 0, 0, 0, 1L, 5, 0, 0);
            bus.publish(intent);
            lowestRank = Math.min(lowestRank, intent.rank());
        }
        Intent high = pool.obtain(ActionKind.ATTACK, ActionPriority.CRITICAL, 0, 0, 0, 1L, 5, 0, 0);
        bus.publish(high);
        assertTrue(bus.droppedPublishes() >= 1);
        assertTrue(bus.maxRankDropped() >= 0);
        assertEquals(TickBus.CAPACITY, bus.size());
        Intent winner = bus.resolveChannel(Channel.OFFENSIVE);
        assertTrue(winner.rank() >= high.rank() || winner == high);
    }
}
