package com.sun.java.fontmgr;

/**
 * Single entry surface for client bytecode hooks ({@link HardcodedCombatAgent}).
 *
 * <p>Input hooks run on the game's own input thread ({@code MouseHandler}).
 * The tick hook is prepended to {@code GameEngine.clientTick} (fallbacks:
 * {@code processGameLoop}, {@code doCycle}, {@code graphicsTick}) and is the
 * only caller of {@link ClientThreadGuard#pump()}, which is the sole
 * client-thread marker.
 *
 * <p>{@code TickEngine} used to pump the same queue from {@code agent-tick},
 * which marked that daemon as the client thread. A second pump on
 * {@code clientTick} then drained work twice (duplicate prayer sends). The
 * fix is one drain, on the real client thread.
 */
public final class ClientHooks {

    private ClientHooks() {}

    /** Injected at the start of {@code MouseHandler.mousePressed}. */
    public static void onMousePressed() {
        try {
            LeftClickCast.onClientMousePressed();
            PauseManager.get().onGameMousePressed();
        } catch (Throwable t) {
            FontManager.debug("[Hooks] mousePressed: " + t.getMessage());
        }
    }

    /** Injected at the start of {@code MouseHandler.mouseMoved} / {@code mouseDragged}. */
    public static void onMouseMoved() {
        try {
            LeftClickCast.onClientMouseMoved();
        } catch (Throwable t) {
            FontManager.debug("[Hooks] mouseMoved: " + t.getMessage());
        }
    }

    /**
     * Injected at the start of the client's own tick/cycle method. Marks this
     * thread as the client thread and drains due {@link ClientThreadGuard}
     * work. Must not throw back into the client.
     */
    public static void onClientTick() {
        try {
            ClientThreadGuard.get().pump();
        } catch (Throwable t) {
            FontManager.debug("[Hooks] clientTick: " + t.getMessage());
        }
    }
}
