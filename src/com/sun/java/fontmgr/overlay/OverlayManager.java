package com.sun.java.fontmgr.overlay;

import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.java.fontmgr.FontManager;

/**
 * Registry and render pump for in-game overlays.
 *
 * <p>{@link OverlayHook} drives this from the client's frame callback. Overlays
 * are held in registration order, are individually toggleable by name, and each
 * render is isolated so a single failing overlay is logged (rate-limited) and
 * skipped rather than corrupting the frame.
 */
public final class OverlayManager {

    private static final OverlayManager INSTANCE = new OverlayManager();

    public static OverlayManager get() {
        return INSTANCE;
    }

    private static final class Entry {
        final GameOverlay overlay;
        volatile boolean enabled = true;

        Entry(GameOverlay overlay) {
            this.overlay = overlay;
        }
    }

    private final List<Entry> overlays = new CopyOnWriteArrayList<>();
    private volatile RuneLiteBridge bridge;
    private volatile OverlayContext context;

    private OverlayManager() {}

    /** Binds the manager to the live client. Idempotent. */
    public void init(RuneLiteBridge bridge) {
        if (bridge == null) return;
        this.bridge = bridge;
        this.context = new OverlayContext(bridge);
    }

    public RuneLiteBridge bridge() {
        return bridge;
    }

    public OverlayContext context() {
        return context;
    }

    /** Registers an overlay. Duplicate instances are ignored. */
    public GameOverlay register(GameOverlay overlay) {
        if (overlay == null) return null;
        for (Entry e : overlays) {
            if (e.overlay == overlay) return overlay;
        }
        overlays.add(new Entry(overlay));
        FontManager.log("[Overlay] registered: " + overlay.name());
        return overlay;
    }

    public void unregister(GameOverlay overlay) {
        if (overlay != null) overlays.removeIf(e -> e.overlay == overlay);
    }

    /** First registered overlay with a matching name (case-insensitive), or null. */
    public GameOverlay byName(String name) {
        if (name == null) return null;
        for (Entry e : overlays) {
            if (name.equalsIgnoreCase(e.overlay.name())) return e.overlay;
        }
        return null;
    }

    /** Enables/disables one overlay by name, or every overlay when {@code name} is null. */
    public void setEnabled(String name, boolean enabled) {
        for (Entry e : overlays) {
            if (name == null || name.equalsIgnoreCase(e.overlay.name())) {
                e.enabled = enabled;
            }
        }
    }

    public boolean isEnabled(String name) {
        for (Entry e : overlays) {
            if (name != null && name.equalsIgnoreCase(e.overlay.name())) return e.enabled;
        }
        return false;
    }

    public boolean hasEnabled() {
        for (Entry e : overlays) {
            if (e.enabled) return true;
        }
        return false;
    }

    /** Renders every enabled overlay. Called from the client frame callback. */
    public void renderAll(Graphics2D g) {
        OverlayContext ctx = this.context;
        if (ctx == null) return;
        for (Entry e : overlays) {
            if (!e.enabled) continue;
            try {
                e.overlay.render(g, ctx);
            } catch (Throwable t) {
                FontManager.debug("[Overlay] render " + e.overlay.name() + ": " + OverlayHook.describe(t));
            }
        }
    }

    /** "Name" or "Name (off)" for each overlay, in registration order. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Entry e : overlays) {
            out.add(e.overlay.name() + (e.enabled ? "" : " (off)"));
        }
        return out;
    }
}
