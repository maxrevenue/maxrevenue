package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client.tick increments every render cycle (~20ms). A game tick is ~30 cycles.
 * Combat runs on a dedicated thread so a slow onTick cannot skip the next dump.
 *
 * <p>Resolution order (most to least authoritative):
 *   1. {@code serverTick} field — set by the server, exact.
 *   2. {@code tick / CYCLES_PER_TICK} — client cycle counter fallback.
 *   3. Miss-catch — pace ourselves when no server tick exists and the cycle
 *      counter stalls (only used when {@code serverTick} is absent).
 *
 * <p>The poll loop and the combat listener dispatch run on one daemon thread.
 * A slow {@code onTick} simply delays the next poll; ticks are never queued
 * ahead of the listener and never dropped out of order.
 */
public class TickEngine implements Runnable {

    private static final int POLL_INTERVAL_MS = 20;
    private static final int CYCLES_PER_TICK = 30;
    private static final long MISS_CATCH_MS = 680;

    private final Field tickField;
    private final Field serverTickField;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private volatile int lastGameTick = -1;
    private volatile int lastServerTick = -1;
    private volatile long lastFireMs = 0;
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

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this, workerName());
        worker.setDaemon(true);
        worker.start();
        FontManager.log("[TickEngine] Started");
    }

    public void stop() {
        running.set(false);
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    public void addListener(TickListener l)    { listeners.add(l); }
    public void removeListener(TickListener l) { listeners.remove(l); }
    public int  getLastTick()                  { return lastGameTick; }

    private static String workerName() {
        return String.format("Worker-%d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000));
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                long now = System.currentTimeMillis();

                // 1) Server tick is exact — prefer it whenever present.
                int server = -1;
                if (serverTickField != null) {
                    try { server = serverTickField.getInt(null); } catch (Exception ignored) {}
                }
                if (server > 0 && server != lastServerTick) {
                    lastServerTick = server;
                    fireGameTick(server, now);
                } else {
                    // 2) Cycle counter fallback: tick / 30.
                    int raw = tickField.getInt(null);
                    int game = raw / CYCLES_PER_TICK;
                    if (game != lastGameTick && game >= 0) {
                        fireGameTick(game, now);
                    }
                    // 3) Miss-catch — only when the client has no server-tick
                    //    field and the cycles have stalled longer than a tick.
                    else if (serverTickField == null && lastFireMs > 0 && (now - lastFireMs) >= MISS_CATCH_MS) {
                        fireGameTick(lastGameTick + 1, now);
                    }
                }
            } catch (Throwable ignored) {
                // A single bad read must not kill the poll loop.
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void fireGameTick(int tick, long now) {
        lastGameTick = tick;
        lastFireMs = now;

        // Fire early in the 600ms window so orb+axe+eat still land this tick.
        int delay = actionDelayMs;
        if (delay <= 0) delay = Humanizer.tickAlignMs();
        if (delay > 0) {
            try { Thread.sleep(delay); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }

        ClientThreadGuard.get().pump();
        for (TickListener l : listeners) {
            try { l.onTick(tick); }
            catch (Throwable t) {
                FontManager.log("[TickEngine] Listener error tick=" + tick + ": " + t.getMessage());
            }
        }
    }
}
