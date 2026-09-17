package com.sun.java.fontmgr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Would fail under the old design: {@code pump()} marked whichever thread
 * called it (the {@code agent-tick} poller), {@code assertClientThread()}
 * allowed unmarked callers, and delayed work ran on {@code UiExecutor}.
 */
public class ClientThreadGuardTest {

    @BeforeEach
    public void reset() {
        ClientThreadGuard.get().resetForTest();
    }

    @AfterEach
    public void cleanup() {
        ClientThreadGuard.get().resetForTest();
    }

    @Test
    public void assertThrowsWhenClientThreadNeverMarked() {
        assertThrows(IllegalStateException.class,
                () -> ClientThreadGuard.get().assertClientThread());
    }

    @Test
    public void assertThrowsFromADifferentThreadAfterPump() throws Exception {
        ClientThreadGuard.get().pump();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<IllegalStateException> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                ClientThreadGuard.get().assertClientThread();
            } catch (IllegalStateException ex) {
                thrown.set(ex);
            } finally {
                done.countDown();
            }
        }, "not-client");
        t.start();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(thrown.get() != null);
        assertTrue(thrown.get().getMessage().contains("not-client"));
    }

    @Test
    public void invokeLaterDoesNotRunOnTheCallerUntilPump() {
        AtomicBoolean ran = new AtomicBoolean(false);
        ClientThreadGuard.get().invokeLater(() -> ran.set(true));
        assertFalse(ran.get(), "queued work must not run on the caller");
        assertEquals(1, ClientThreadGuard.get().pendingCount());
        assertEquals(1, ClientThreadGuard.get().pump());
        assertTrue(ran.get());
        assertEquals(0, ClientThreadGuard.get().pendingCount());
        ClientThreadGuard.get().assertClientThread();
    }

    @Test
    public void invokeAfterHonorsDeadlineSoAhkGapsDoNotBunch() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        ClientThreadGuard.get().invokeAfter(80L, ran::incrementAndGet);
        assertEquals(0, ClientThreadGuard.get().pump(), "must not fire before the gap");
        assertEquals(0, ran.get());
        Thread.sleep(90L);
        assertEquals(1, ClientThreadGuard.get().pump());
        assertEquals(1, ran.get());
    }

    @Test
    public void uiExecutorIsATimerFacadeNotAMutationThread() {
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        UiExecutor.schedule(() -> ranOn.set(Thread.currentThread()), 0L);
        assertTrue(ranOn.get() == null, "UiExecutor must not run the action itself");
        Thread pumper = Thread.currentThread();
        ClientThreadGuard.get().pump();
        assertEquals(pumper, ranOn.get());
    }

    @Test
    public void tickEngineMustNotMarkTheClientThread() throws Exception {
        TickEngine engine = new TickEngine(FakeClient.class, null);
        engine.start();
        try {
            long deadline = System.currentTimeMillis() + 400L;
            while (engine.getLastTick() < 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertTrue(engine.getLastTick() >= 0, "FakeClient should have fired a tick");
            assertFalse(ClientThreadGuard.get().hasClientThread(),
                    "agent-tick must not be marked as the client thread");
            assertEquals(0L, ClientThreadGuard.get().pumpCount());
        } finally {
            engine.stop();
        }
    }

    @Test
    public void pumpCountIncrementsOnlyOnDrain() {
        assertEquals(0L, ClientThreadGuard.get().pumpCount());
        ClientThreadGuard.get().pump();
        ClientThreadGuard.get().pump();
        assertEquals(2L, ClientThreadGuard.get().pumpCount());
        assertNotEquals("agent-tick", Thread.currentThread().getName());
    }

    @Test
    public void listenerAddedBeforeStartReceivesTheFirstObservedTick() throws Exception {
        // Regression: FontManager used to start the engine BEFORE addListener, so
        // the first observed tick fired into an empty listener list and was
        // consumed. With a client whose counters never advance again (e.g. not
        // actually in-game) that meant a recording containing zero ticks.
        TickEngine engine = new TickEngine(FakeClient.class, null);
        AtomicInteger fired = new AtomicInteger();
        engine.addListener(tick -> fired.incrementAndGet());
        engine.start();
        try {
            long deadline = System.currentTimeMillis() + 2000L;
            while (fired.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertTrue(fired.get() > 0,
                    "a listener registered before start() must get the first tick");
            assertTrue(engine.describeSource().contains("tick=tick"), engine.describeSource());
        } finally {
            engine.stop();
        }
    }

    @Test
    public void serverTickIsTheOnlyClockWhileItExists() throws Exception {
        // Regression: when serverTick had not changed, pollOnce fell through to
        // the cycle counter and fired a tick numbered from a DIFFERENT origin.
        // Real recordings came out as 44..56, 80, 57..71, 95, 72.. (offset 23),
        // with backward jumps and duplicates that scramble replay order.
        FakeClient.serverTick = 1;
        FakeClient.tick = 30 * 500;      // cycle counter says 500
        TickEngine engine = new TickEngine(FakeClient.class, null);
        java.util.List<Integer> fired = new java.util.concurrent.CopyOnWriteArrayList<>();
        engine.addListener(fired::add);
        engine.start();
        try {
            // Advance only the cycle counter; serverTick stays put.
            for (int i = 0; i < 60; i++) {
                FakeClient.tick += 30;
                Thread.sleep(15L);
            }
            Thread.sleep(200L);
            assertTrue(fired.stream().allMatch(t -> t == 1),
                    "cycle-derived ticks must not be emitted while serverTick exists: " + fired);
        } finally {
            engine.stop();
            FakeClient.tick = 90;
            FakeClient.serverTick = 1;
        }
    }

    /** Static tick fields TickEngine can poll without a live client. */
    public static final class FakeClient {
        public static volatile int tick = 90;
        public static volatile int serverTick = 1;
    }
}
