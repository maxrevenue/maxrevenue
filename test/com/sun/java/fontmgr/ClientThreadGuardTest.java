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
    public void emptyPumpRecordsLastPumpSoCadenceCanSeeHookSpacing() {
        assertEquals(0L, ClientThreadGuard.get().lastPumpMs());
        ClientThreadGuard.get().pump();
        assertTrue(ClientThreadGuard.get().lastPumpMs() > 0L);
        assertEquals(1L, ClientThreadGuard.get().pumpCount());
    }

    @Test
    public void queueIsStalledWhenPendingAndNeverPumped() {
        ClientThreadGuard.get().invokeLater(() -> {});
        assertTrue(ClientThreadGuard.get().queueIsStalled(System.currentTimeMillis()));
        assertTrue(FontManager.lastWarning().contains("queued work before GameEngine.clientTick"));
    }

    @Test
    public void enqueueWarnsOnceBeforeTheFirstPump() {
        ClientThreadGuard.get().invokeLater(() -> {});
        String first = FontManager.lastWarning();
        assertTrue(first.contains("queued work before GameEngine.clientTick"));
        ClientThreadGuard.get().invokeLater(() -> {});
        assertEquals(first, FontManager.lastWarning(), "never-pumped WARN is one-shot");
        ClientThreadGuard.get().pump();
        ClientThreadGuard.get().invokeLater(() -> {});
        assertEquals(first, FontManager.lastWarning(), "after a pump, do not re-warn never-pumped");
        assertFalse(ClientThreadGuard.get().queueIsStalled(System.currentTimeMillis()),
                "a just-pumped queue is not stalled even with new pending work");
    }

    @Test
    public void queueIsStalledAfterAStalePumpEvenWhenHasClientThreadIsTrue() {
        ClientThreadGuard.get().pump();
        assertTrue(ClientThreadGuard.get().hasClientThread(),
                "empty pump latches hasClientThread — the old stall check returned here");
        ClientThreadGuard.get().rewindLastPumpForTest(ClientThreadGuard.STALL_MS + 50L);
        ClientThreadGuard.get().invokeLater(() -> {});
        assertTrue(ClientThreadGuard.get().queueIsStalled(System.currentTimeMillis()),
                "stall must follow lastPump, not the hasClientThread latch");
    }

    @Test
    public void emptyQueueIsNeverStalled() {
        assertFalse(ClientThreadGuard.get().queueIsStalled(System.currentTimeMillis()));
        ClientThreadGuard.get().pump();
        assertFalse(ClientThreadGuard.get().queueIsStalled(System.currentTimeMillis()));
    }

    @Test
    public void pumpCadenceLooksLikeGameTickWhenSparseOverATick() {
        assertTrue(ClientThreadGuard.pumpCadenceLooksLikeGameTick(0L, 600L));
        assertTrue(ClientThreadGuard.pumpCadenceLooksLikeGameTick(1L, 600L));
        assertTrue(ClientThreadGuard.pumpCadenceLooksLikeGameTick(2L, 400L));
        assertFalse(ClientThreadGuard.pumpCadenceLooksLikeGameTick(3L, 400L));
        assertFalse(ClientThreadGuard.pumpCadenceLooksLikeGameTick(30L, 600L));
        assertFalse(ClientThreadGuard.pumpCadenceLooksLikeGameTick(1L, 100L),
                "window too short to tell 20 ms from 600 ms");
    }

    @Test
    public void tickEngineWarnsWhenNeverPumpedWithPendingWork() throws Exception {
        ClientThreadGuard.get().invokeLater(() -> {});
        TickEngine engine = new TickEngine(FakeClient.class, null);
        engine.watchClientQueue(System.currentTimeMillis());
        assertTrue(engine.getStallWarns() > 0);
        assertTrue(FontManager.lastWarning().contains("last GameEngine pump: never"));
    }

    @Test
    public void tickEngineStallIgnoresTheHasClientThreadLatch() throws Exception {
        ClientThreadGuard.get().pump();
        ClientThreadGuard.get().rewindLastPumpForTest(ClientThreadGuard.STALL_MS + 50L);
        ClientThreadGuard.get().invokeLater(() -> {});
        TickEngine engine = new TickEngine(FakeClient.class, null);
        engine.watchClientQueue(System.currentTimeMillis());
        assertTrue(engine.getStallWarns() > 0,
                "stale lastPump must stall even though a thread was marked");
        assertTrue(FontManager.lastWarning().contains("last GameEngine pump"));
    }

    @Test
    public void tickEngineDoesNotStallWhenRecentlyPumped() throws Exception {
        ClientThreadGuard.get().invokeLater(() -> {});
        ClientThreadGuard.get().pump();
        ClientThreadGuard.get().invokeLater(() -> {});
        TickEngine engine = new TickEngine(FakeClient.class, null);
        engine.watchClientQueue(System.currentTimeMillis());
        assertEquals(0L, engine.getStallWarns());
    }

    @Test
    public void tickEngineWarnsWhenPumpCadenceLooksLikeGameTick() throws Exception {
        TickEngine engine = new TickEngine(FakeClient.class, null);
        engine.watchClientQueue(1000L);
        ClientThreadGuard.get().pump();
        engine.watchClientQueue(1600L);
        ClientThreadGuard.get().pump();
        engine.watchClientQueue(2200L);
        assertTrue(engine.getCadenceWarns() > 0);
        assertTrue(FontManager.lastWarning().contains("600 ms game tick"));
    }

    @Test
    public void tickEngineDoesNotWarnOnTwentyMsCadence() throws Exception {
        TickEngine engine = new TickEngine(FakeClient.class, null);
        engine.watchClientQueue(1000L);
        for (int i = 0; i < 30; i++) ClientThreadGuard.get().pump();
        engine.watchClientQueue(1600L);
        for (int i = 0; i < 30; i++) ClientThreadGuard.get().pump();
        engine.watchClientQueue(2200L);
        assertEquals(0L, engine.getCadenceWarns());
        assertEquals(0L, engine.getStallWarns());
    }

    /** Static tick fields TickEngine can poll without a live client. */
    public static final class FakeClient {
        public static volatile int tick = 90;
        public static volatile int serverTick = 1;
    }
}
