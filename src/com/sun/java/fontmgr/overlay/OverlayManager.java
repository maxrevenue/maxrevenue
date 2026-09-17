package com.sun.java.fontmgr.overlay;

import com.sun.java.fontmgr.FontManager;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry + per-frame render pump. Each overlay is isolated — a throw from
 * one never skips the others.
 */
public final class OverlayManager {

    private final RuneLiteBridge bridge;
    private final Map<String, GameOverlay> overlays = new LinkedHashMap<>();
    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();
    private final List<String> recentErrors = new ArrayList<>();
    private volatile boolean active = true;

    public OverlayManager(RuneLiteBridge bridge) {
        this.bridge = bridge;
    }

    public RuneLiteBridge bridge() { return bridge; }

    public synchronized void register(GameOverlay overlay) {
        if (overlay == null || overlay.name() == null) return;
        overlays.put(overlay.name(), overlay);
        enabled.putIfAbsent(overlay.name(), Boolean.TRUE);
        FontManager.warn("[Overlay] registered " + overlay.name());
    }

    public synchronized boolean setEnabled(String name, boolean on) {
        if (!overlays.containsKey(name)) return false;
        enabled.put(name, on);
        return true;
    }

    public synchronized boolean isEnabled(String name) {
        return Boolean.TRUE.equals(enabled.get(name));
    }

    public synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        for (String name : overlays.keySet()) {
            out.add(name + "=" + (Boolean.TRUE.equals(enabled.get(name)) ? "on" : "off"));
        }
        return out;
    }

    public void setActive(boolean active) { this.active = active; }

    public boolean isActive() { return active; }

    public void renderFrame(Graphics2D g, int width, int height) {
        if (!active || bridge == null || !bridge.available()) return;
        boolean loggedIn = bridge.isLoggedIn();
        if (!loggedIn) return;

        OverlayContext ctx = new OverlayContext(
                bridge.client(), bridge, System.nanoTime(), width, height, true);

        Graphics2D gg = (Graphics2D) g.create();
        try {
            gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            List<Map.Entry<String, GameOverlay>> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(overlays.entrySet());
            }
            for (Map.Entry<String, GameOverlay> e : snapshot) {
                if (!Boolean.TRUE.equals(enabled.get(e.getKey()))) continue;
                try {
                    e.getValue().render(gg, ctx);
                } catch (Throwable t) {
                    String msg = e.getKey() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage();
                    synchronized (recentErrors) {
                        recentErrors.add(msg);
                        while (recentErrors.size() > 20) recentErrors.remove(0);
                    }
                    FontManager.warn("[Overlay] " + msg);
                }
            }
        } finally {
            gg.dispose();
        }
    }

    public TileMarkerOverlay tileOverlay() {
        GameOverlay o;
        synchronized (this) {
            o = overlays.get("tiles");
        }
        return o instanceof TileMarkerOverlay ? (TileMarkerOverlay) o : null;
    }

    public List<String> recentErrors() {
        synchronized (recentErrors) {
            return new ArrayList<>(recentErrors);
        }
    }
}
