package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client.tick increments every render cycle (~20ms). A game tick is ~30 cycles.
 * Combat runs on this dedicated thread so a slow onTick cannot skip the next dump.
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
 *
 * <p>If neither field can be resolved the constructor fails and the failure is
 * recorded via {@link FontManager#warn(String)} — a client update renaming these
 * fields used to make the whole agent silently inert.
 */
public class TickEngine implements Runnable {

    private static final int POLL_INTERVAL_MS = 20;
    private static final int CYCLES_PER_TICK = 30;
    private static final long MISS_CATCH_MS = 680;
    /** OSRS game tick is ~600 ms — never run listeners faster than this. */
    private static final long MIN_TICK_FIRE_MS = 550;

    private final Field tickField;
    private final Field serverTickField;
    /** Non-null when the resolved field is an instance field. */
    private final Object tickTarget;
    private final Object serverTickTarget;
    private final List<TickListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private volatile int lastGameTick = -1;
    private volatile int lastServerTick = -1;
    private volatile long lastFireMs = 0;
    public volatile int actionDelayMs = 0;

    /** Consecutive poll failures; surfaced so a broken client field is visible. */
    private final AtomicLong pollErrors = new AtomicLong();

    public TickEngine(Class<?> clientClass, Object clientInstance) throws NoSuchFieldException {
        Field tick = findField(clientClass, "tick");
        if (tick == null) {
            throw new NoSuchFieldException("client field 'tick' not found on " + clientClass.getName()
                    + " (or any superclass)");
        }
        tickField = tick;
        tickTarget = Modifier.isStatic(tick.getModifiers()) ? null : clientInstance;

        Field server = findField(clientClass, "serverTick");
        serverTickField = server;
        serverTickTarget = (server != null && !Modifier.isStatic(server.getModifiers()))
                ? clientInstance : null;

        if (!Modifier.isStatic(tick.getModifiers()) && clientInstance == null) {
            FontManager.warn("[TickEngine] 'tick' is an instance field but no client instance was supplied; "
                    + "tick polling will fail");
        }
    }

    /** Walks the class hierarchy; {@code getDeclaredField} alone misses inherited fields. */
    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        worker = new Thread(this, "agent-tick");
        worker.setDaemon(true);
        worker.start();
        FontManager.log("[TickEngine] Started (tick=" + tickField.getName()
                + (serverTickField != null ? ", serverTick=" + serverTickField.getName() : ", no serverTick")
                + ")");
    }

    public void stop() {
        running.set(false);
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    public void addListener(TickListener l)    { listeners.add(l); }
    public void removeListener(TickListener l) { listeners.remove(l); }
    public int  getLastTick()                  { return lastGameTick; }
    public long getPollErrors()                { return pollErrors.get(); }

    @Override
    public void run() {
        while (running.get()) {
            try {
                pollOnce();
            } catch (Throwable t) {
                // A single bad read must not kill the poll loop, but it must be
                // visible: report the first few and then occasionally.
                long n = pollErrors.incrementAndGet();
                if (n <= 3 || n % 100 == 0) {
                    FontManager.warn("[TickEngine] poll error #" + n + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
            // Unconditional sleep. The previous implementation used `continue`
            // inside the try block, which skips a trailing sleep and spun this
            // thread at 100% CPU whenever the tick guard tripped.
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pollOnce() throws Exception {
        long now = System.currentTimeMillis();
        boolean gateOpen = lastFireMs == 0 || (now - lastFireMs) >= MIN_TICK_FIRE_MS;

        int server = -1;
        if (serverTickField != null) {
            server = serverTickField.getInt(serverTickTarget);
        }

        if (server > 0 && server != lastServerTick) {
            // Leave lastServerTick untouched while the gate is closed so the
            // tick still fires once one real tick has elapsed.
            if (gateOpen) {
                lastServerTick = server;
                fireGameTick(server, now);
            }
            return;
        }

        // Cycle counter fallback: tick / CYCLES_PER_TICK.
        int raw = tickField.getInt(tickTarget);
        int game = raw / CYCLES_PER_TICK;
        if (game != lastGameTick && game >= 0) {
            if (gateOpen) fireGameTick(game, now);
            return;
        }

        // Miss-catch — only when the client has no server-tick field and the
        // cycles have stalled longer than a tick.
        if (serverTickField == null && lastFireMs > 0 && (now - lastFireMs) >= MISS_CATCH_MS) {
            fireGameTick(lastGameTick + 1, now);
        }
    }

    private void fireGameTick(int tick, long now) {
        lastGameTick = tick;
        lastFireMs = now;

        // Fire early in the 600ms window so orb+axe+eat still land this tick.
        int delay = actionDelayMs;
        if (delay <= 0) delay = Humanizer.tickAlignMs();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        ClientThreadGuard.get().pump();
        for (TickListener l : listeners) {
            try {
                l.onTick(tick);
            } catch (Throwable t) {
                FontManager.warn("[TickEngine] Listener error tick=" + tick + ": "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }
}
