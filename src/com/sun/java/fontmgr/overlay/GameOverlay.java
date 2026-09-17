package com.sun.java.fontmgr.overlay;

import java.awt.Graphics2D;

/** In-game overlay drawn into the client's graphics buffer each frame. */
public interface GameOverlay {

    /** Stable id used by {@code OVERLAY|ON/OFF} and the registry. */
    String name();

    /** Draw into the game buffer. {@code g} is already clipped to the buffer. */
    void render(Graphics2D g, OverlayContext ctx);
}
