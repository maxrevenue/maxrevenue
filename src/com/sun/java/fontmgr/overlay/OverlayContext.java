package com.sun.java.fontmgr.overlay;

/**
 * Per-frame context handed to every {@link GameOverlay}. Currently a thin,
 * typed wrapper over {@link RuneLiteBridge}; future fields (tick, config,
 * local player snapshot) belong here so the {@link GameOverlay} signature does
 * not have to change.
 */
public final class OverlayContext {

    private final RuneLiteBridge bridge;

    OverlayContext(RuneLiteBridge bridge) {
        this.bridge = bridge;
    }

    /** Reflection view of the RuneLite client: tiles, projection, game state. */
    public RuneLiteBridge client() {
        return bridge;
    }

    /** True when the client believes it is logged in (safe to touch the scene). */
    public boolean inGame() {
        return bridge != null && bridge.isLoggedIn();
    }
}
