package com.sun.java.fontmgr.overlay;

import java.awt.Graphics2D;

/**
 * One in-game overlay. Implementations are registered with
 * {@link OverlayManager} and rendered once per client frame, on the game's own
 * render thread, with {@code Graphics2D} positioned in canvas coordinates.
 *
 * <p>This is the extension point for plugins: a plugin JAR dropped on the
 * agent's {@code plugins=} path (or appended through
 * {@code PluginBootstrap.onAttach}) can call
 * {@code OverlayManager.get().register(myOverlay)} and immediately draw into
 * the game.
 *
 * <p>{@link #render} must be fast and must not block — it runs inside the
 * client's frame. Exceptions are caught per overlay by the manager, so one
 * broken overlay cannot take the frame down.
 */
public interface GameOverlay {

    /** Stable name, unique enough to address it from config and the command socket. */
    String name();

    /**
     * Draw this frame.
     *
     * @param g   canvas-space graphics; already clipped to the game viewport
     * @param ctx live client access (tiles, projection, game state)
     */
    void render(Graphics2D g, OverlayContext ctx);
}
