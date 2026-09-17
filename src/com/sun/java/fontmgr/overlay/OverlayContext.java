package com.sun.java.fontmgr.overlay;

/**
 * Per-frame context passed to every {@link GameOverlay}.
 */
public final class OverlayContext {

    public final Object client;
    public final RuneLiteBridge bridge;
    public final long frameNanos;
    public final int canvasWidth;
    public final int canvasHeight;
    public final boolean loggedIn;

    public OverlayContext(Object client, RuneLiteBridge bridge, long frameNanos,
                          int canvasWidth, int canvasHeight, boolean loggedIn) {
        this.client = client;
        this.bridge = bridge;
        this.frameNanos = frameNanos;
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        this.loggedIn = loggedIn;
    }
}
