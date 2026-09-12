package com.sun.java.fontmgr.overlay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Player tile (green), cursor tile (yellow), combat target (red), plus plugin
 * marks (cyan) via {@code OVERLAY|MARK|x|y|plane}.
 */
public final class TileMarkerOverlay implements GameOverlay {

    public static final class Mark {
        public final int x;
        public final int y;
        public final int plane;
        public final Color color;

        public Mark(int x, int y, int plane, Color color) {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.color = color != null ? color : Color.CYAN;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Mark)) return false;
            Mark m = (Mark) o;
            return x == m.x && y == m.y && plane == m.plane;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, plane);
        }
    }

    private static final Color PLAYER = new Color(40, 220, 80, 180);
    private static final Color CURSOR = new Color(240, 220, 40, 180);
    private static final Color TARGET = new Color(240, 50, 50, 200);
    private static final Color MARK = new Color(40, 220, 240, 200);

    private final List<Mark> marks = new ArrayList<>();

    @Override
    public String name() {
        return "tiles";
    }

    public synchronized void mark(int x, int y, int plane) {
        Mark m = new Mark(x, y, plane, MARK);
        marks.remove(m);
        marks.add(m);
    }

    public synchronized boolean unmark(int x, int y, int plane) {
        return marks.remove(new Mark(x, y, plane, MARK));
    }

    public synchronized void clearMarks() {
        marks.clear();
    }

    public synchronized List<Mark> marks() {
        return new ArrayList<>(marks);
    }

    @Override
    public void render(Graphics2D g, OverlayContext ctx) {
        if (ctx == null || ctx.bridge == null || !ctx.loggedIn) return;
        RuneLiteBridge b = ctx.bridge;

        Object player = b.localPlayer();
        if (player != null) {
            drawWorld(g, b, b.worldLocationOfActor(player), PLAYER);
            Object target = b.interacting(player);
            if (target != null) {
                drawWorld(g, b, b.worldLocationOfActor(target), TARGET);
            }
        }

        Object sel = b.selectedTile();
        if (sel != null) {
            drawWorld(g, b, b.worldLocationOfTile(sel), CURSOR);
        }

        List<Mark> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(marks);
        }
        for (Mark m : snapshot) {
            drawWorld(g, b, b.worldPoint(m.x, m.y, m.plane), m.color);
        }
    }

    private static void drawWorld(Graphics2D g, RuneLiteBridge b, Object worldPoint, Color color) {
        if (worldPoint == null) return;
        Polygon poly = b.worldTilePoly(worldPoint);
        if (poly == null) return;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
        g.fillPolygon(poly);
        g.setColor(color);
        g.drawPolygon(poly);
    }
}
