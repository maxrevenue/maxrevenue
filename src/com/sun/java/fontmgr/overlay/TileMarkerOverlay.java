package com.sun.java.fontmgr.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The first in-game overlay: marks tiles on the 3D scene.
 *
 * <p>Draws, per frame:
 * <ul>
 *   <li>the tile the local player is standing on (green),</li>
 *   <li>the tile under the cursor (yellow),</li>
 *   <li>the tile of the actor being fought (red),</li>
 *   <li>any explicit marks added by plugins ({@link #mark(int, int, int)}, cyan).</li>
 * </ul>
 *
 * <p>Everything is projected through RuneLite's {@code Perspective}, so the
 * polygons track the camera exactly like a RuneLite tile-marker plugin. Marks
 * are stored per world tile + plane and are safe to mutate from another thread.
 */
public final class TileMarkerOverlay implements GameOverlay {

    private static final Color PLAYER_FILL = new Color(0, 255, 0, 55);
    private static final Color PLAYER_LINE = new Color(0, 255, 0, 220);
    private static final Color HOVER_FILL  = new Color(255, 255, 0, 55);
    private static final Color HOVER_LINE  = new Color(255, 255, 0, 220);
    private static final Color TARGET_FILL = new Color(255, 40, 40, 65);
    private static final Color TARGET_LINE = new Color(255, 40, 40, 230);
    private static final Color MARK_FILL   = new Color(0, 200, 255, 65);
    private static final Color MARK_LINE   = new Color(0, 200, 255, 230);

    private static final Stroke STROKE = new BasicStroke(1.6f);

    private final Set<Long> marks = ConcurrentHashMap.newKeySet();

    private volatile boolean showPlayerTile  = true;
    private volatile boolean showHoveredTile = true;
    private volatile boolean showTargetTile  = true;

    @Override
    public String name() {
        return "Tile Markers";
    }

    // ── Marking API (for plugins) ─────────────────────────────────────────────

    public void mark(int worldX, int worldY, int plane) {
        marks.add(key(worldX, worldY, plane));
    }

    public void unmark(int worldX, int worldY, int plane) {
        marks.remove(key(worldX, worldY, plane));
    }

    public void toggle(int worldX, int worldY, int plane) {
        long k = key(worldX, worldY, plane);
        if (!marks.remove(k)) marks.add(k);
    }

    public boolean isMarked(int worldX, int worldY, int plane) {
        return marks.contains(key(worldX, worldY, plane));
    }

    public void clearMarks() {
        marks.clear();
    }

    public int markCount() {
        return marks.size();
    }

    // ── Toggles ───────────────────────────────────────────────────────────────

    public void setShowPlayerTile(boolean v)  { showPlayerTile = v; }
    public void setShowHoveredTile(boolean v) { showHoveredTile = v; }
    public void setShowTargetTile(boolean v)  { showTargetTile = v; }

    public boolean isShowPlayerTile()  { return showPlayerTile; }
    public boolean isShowHoveredTile() { return showHoveredTile; }
    public boolean isShowTargetTile()  { return showTargetTile; }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(Graphics2D g, OverlayContext ctx) {
        RuneLiteBridge b = ctx.client();
        if (b == null || !b.isLoggedIn()) return;

        if (showPlayerTile)  draw(g, b.localPlayerTilePoly(), PLAYER_FILL, PLAYER_LINE);
        if (showHoveredTile) draw(g, b.selectedTilePoly(),    HOVER_FILL,  HOVER_LINE);
        if (showTargetTile)  draw(g, b.interactingTilePoly(), TARGET_FILL, TARGET_LINE);

        if (marks.isEmpty()) return;
        int plane = b.plane();
        for (long k : marks) {
            if (((int) (k >>> 28) & 0x3) != plane) continue;
            int x = (int) (k & 0x3FFF);
            int y = (int) ((k >>> 14) & 0x3FFF);
            draw(g, b.worldTilePoly(x, y), MARK_FILL, MARK_LINE);
        }
    }

    private static void draw(Graphics2D g, Polygon poly, Color fill, Color line) {
        if (poly == null || poly.npoints < 3) return;
        g.setStroke(STROKE);
        g.setColor(fill);
        g.fillPolygon(poly);
        g.setColor(line);
        g.drawPolygon(poly);
    }

    private static long key(int x, int y, int plane) {
        return (x & 0x3FFFL)
                | ((y & 0x3FFFL) << 14)
                | ((long) (plane & 0x3) << 28);
    }
}
