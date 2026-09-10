package com.sun.java.fontmgr;

/**
 * Single entry surface for client bytecode hooks ({@link HardcodedCombatAgent}).
 *
 * <p>Hooks run on the game's own input thread ({@code MouseHandler}), not global
 * AWT listeners. {@link ClientThreadGuard} is pumped once per game tick from
 * {@link TickEngine} only — a second pump on {@code clientTick} caused duplicate
 * prayer sends and server rejections.
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
}
