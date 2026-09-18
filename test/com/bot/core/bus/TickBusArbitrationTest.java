package com.bot.core.bus;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TickBusArbitrationTest {

    @Test
    void sameIntentsSameWinnerRegardlessOfPublishOrder() {
        TickBus reference = new TickBus();
        reference.beginTick(50L);
        Intent a = intent(0, ActionKind.ATTACK, ActionPriority.OFFENSIVE, 50L, 5);
        Intent b = intent(1, ActionKind.SPECIAL, ActionPriority.CRITICAL, 50L, 5);
        Intent c = intent(2, ActionKind.EAT, ActionPriority.CRITICAL, 50L, 5);
        reference.publish(a);
        reference.publish(b);
        reference.publish(c);
        Intent refWinner = reference.resolveChannel(Channel.OFFENSIVE);

        Random rng = new Random(42);
        for (int trial = 0; trial < 10_000; trial++) {
            Intent[] batch = new Intent[]{a, b, c};
            shuffle(batch, rng);
            TickBus bus = new TickBus();
            bus.beginTick(50L);
            for (int i = 0; i < batch.length; i++) {
                bus.publish(batch[i]);
            }
            Intent winner = bus.resolveChannel(Channel.OFFENSIVE);
            assertNotNull(refWinner);
            assertEquals(refWinner.kind(), winner.kind());
            assertEquals(refWinner.rank(), winner.rank());
        }
    }

    @Test
    void sustainChannelPicksHighestRankEat() {
        TickBus bus = new TickBus();
        bus.beginTick(10L);
        bus.publish(intent(5, ActionKind.EAT, ActionPriority.SUSTAIN, 10L, 4));
        bus.publish(intent(3, ActionKind.EAT, ActionPriority.CRITICAL, 10L, 4));
        Intent winner = bus.resolveChannel(Channel.SUSTAIN);
        assertNotNull(winner);
        assertEquals(ActionPriority.CRITICAL, winner.priority());
    }

    @Test
    void skipsExpiredIntents() {
        TickBus bus = new TickBus();
        bus.beginTick(20L);
        bus.publish(intent(0, ActionKind.ATTACK, ActionPriority.CRITICAL, 18L, 2));
        assertEquals(null, bus.resolveChannel(Channel.OFFENSIVE));
    }

    private static Intent intent(int ordinal,
                                 ActionKind kind,
                                 ActionPriority priority,
                                 long bornTick,
                                 int ttl) {
        IntentPool pool = new IntentPool(ordinal, 1);
        return pool.obtain(kind, priority, 0, 0, 0, bornTick, ttl, 0, 0);
    }

    private static void shuffle(Intent[] arr, Random rng) {
        for (int i = arr.length - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Intent tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
    }
}
