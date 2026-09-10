package com.sun.java.fontmgr;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Single shared executor for UI-initiated background tasks.
 */
public final class UiExecutor {
    private static final ScheduledExecutorService EXEC =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, FontManager.threadName("ui"));
                    t.setDaemon(true);
                    return t;
                }
            });

    private UiExecutor() {}

    public static void exec(Runnable r, String name) {
        EXEC.submit(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                FontManager.warn("[UiExecutor] " + (name != null ? name : "task") + " failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    public static void schedule(Runnable r, long delayMs) {
        EXEC.schedule(r, delayMs, TimeUnit.MILLISECONDS);
    }
}
