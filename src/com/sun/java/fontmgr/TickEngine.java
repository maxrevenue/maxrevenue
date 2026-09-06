package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client.tick increments every render cycle (~20ms). A game tick is ~30 cycles.
 * Combat runs on a dedicated thread so a slow onTick cannot skip the next dump.
 */
public class TickEngine implements Runnable {

    private static final int POLL_INTERVAL_MS = 20;
    private static final int CYCLES_PER_TICK = 30;
    private static final long MISS_CATCH_MS = 680;

    private final Field tickField;
    private final Field serverTickField;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService poller =
            Executors.newSingleThreadScheduledExecutor(r -> daemon(r));
    private final ExecutorService combat =
            Executors.newSingleThreadExecutor(r -> daemon(r));
    private final AtomicBoolean combatBusy = new AtomicBoolean(false);
    /** Never drop a game tick because the last burst is still draining. */
    private final ConcurrentLinkedQueue<Integer> pendingTicks = new ConcurrentLinkedQueue<>();

    private volatile int lastRawTick = -1;
    private volatile int lastGameTick = -1;
    private volatile int lastServerTick = -1;
    private volatile long lastFireMs = 0;
    private volatile boolean running = false;
    public volatile int actionDelayMs = 0;

    public TickEngine(Class<?> clientClass) throws NoSuchFieldException {
        tickField = clientClass.getDeclaredField("tick");
        tickField.setAccessible(true);
        Field server = null;
        try {
            server = clientClass.getDeclaredField("serverTick");
            server.setAccessible(true);
        } catch (NoSuchFieldException ignored) {}
        serverTickField = server;
    }

    private static Thread daemon(Runnable r) {
        Thread t = new Thread(r, String.format("Worker-%d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000)));
        t.setDaemon(true);
        return t;
    }

    public void start() {
        running = true;
        poller.scheduleAtFixedRate(this, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        FontManager.log("[TickEngine] Started");
    }

    public void stop() {
        running = false;
        poller.shutdown();
        combat.shutdown();
    }

    public void addListener(TickListener l)    { listeners.add(l); }
    public void removeListener(TickListener l) { listeners.remove(l); }
    public int  getLastTick()                  { return lastGameTick; }

    @Override
    public void run() {
        if (!running) return;
        try {
            long now = System.currentTimeMillis();
            int server = -1;
            if (serverTickField != null) {
                try { server = serverTickField.getInt(null); } catch (Exception ignored) {}
            }
            if (server > 0 && server != lastServerTick) {
                lastServerTick = server;
                lastGameTick = server;
                lastFireMs = now;
                dispatch(server);
                return;
            }

            int raw = tickField.getInt(null);
            int game = raw / CYCLES_PER_TICK;
            if (game != lastGameTick && game >= 0) {
                lastGameTick = game;
                lastRawTick = raw;
                lastFireMs = now;
                dispatch(game);
            } else if (serverTickField == null && lastFireMs > 0 && (now - lastFireMs) >= MISS_CATCH_MS) {
                lastGameTick++;
                lastFireMs = now;
                dispatch(lastGameTick);
            }
        } catch (Throwable ignored) {}
    }

    private void dispatch(int tick) {
        int delay = actionDelayMs;
        // Fire early in the 600ms window so orb+axe+eat still land this tick.
        if (delay <= 0) delay = Humanizer.tickAlignMs();
        final int t = tick;
        poller.schedule(() -> fireTick(t), delay, TimeUnit.MILLISECONDS);
    }

    private void fireTick(int tick) {
        pendingTicks.add(tick);
        combat.execute(this::drainTicks);
    }

    private void drainTicks() {
        if (!combatBusy.compareAndSet(false, true)) return;
        try {
            Integer tick;
            while ((tick = pendingTicks.poll()) != null) {
                ClientThreadGuard.get().pump();
                for (TickListener l : listeners) {
                    try { l.onTick(tick); }
                    catch (Throwable t) {
                        FontManager.log("[TickEngine] Listener error tick=" + tick + ": " + t.getMessage());
                    }
                }
            }
        } finally {
            combatBusy.set(false);
            if (!pendingTicks.isEmpty()) combat.execute(this::drainTicks);
        }
    }
}
