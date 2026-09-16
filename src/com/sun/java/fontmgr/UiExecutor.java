package com.sun.java.fontmgr;

/**
 * Facade for delayed / off-EDT work that must still mutate client state.
 *
 * <p>This used to be a {@code ScheduledExecutorService} named {@code ui} that
 * ran the action itself. Swap clicks, eats, and delayed wields therefore
 * happened on a daemon thread while {@link ClientThreadGuard#pump()} (called
 * from {@code agent-tick}) had marked that poller as the "client thread", so
 * {@link ClientThreadGuard#assertClientThread()} could not catch it.
 *
 * <p>Timing is now a wall-clock deadline on {@link ClientThreadGuard}; the
 * GameEngine tick hook drains due tasks on the real client thread. AHK-safe
 * inventory gaps stay millisecond-accurate provided {@code clientTick} runs
 * every client cycle (~20 ms), not once per 600 ms game tick.
 */
public final class UiExecutor {

    private UiExecutor() {}

    public static void exec(Runnable r, String name) {
        ClientThreadGuard.get().invokeLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                FontManager.warn("[UiExecutor] " + (name != null ? name : "task") + " failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    public static void schedule(Runnable r, long delayMs) {
        ClientThreadGuard.get().invokeAfter(delayMs, r);
    }
}
