package com.sun.java.fontmgr;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Single shared executor for UI-initiated background tasks.
 */
public final class UiExecutor {
    private static final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, String.format("Worker-%d", ThreadLocalRandom.current().nextInt(100_000)));
            t.setDaemon(true);
            return t;
        }
    });

    private UiExecutor() {}

    public static void exec(Runnable r, String name) {
        exec.submit(() -> {
            try { r.run(); } catch (Throwable ignored) {}
        });
    }

    public static void schedule(Runnable r, long delayMs) {
        exec.schedule(r, delayMs, TimeUnit.MILLISECONDS);
    }
}
