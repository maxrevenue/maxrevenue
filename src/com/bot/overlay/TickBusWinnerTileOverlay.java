package com.bot.overlay;

import com.bot.core.telemetry.TickDispatchState;
import com.sun.java.fontmgr.overlay.GameOverlay;
import com.sun.java.fontmgr.overlay.OverlayContext;
import com.sun.java.fontmgr.overlay.RuneLiteBridge;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;

/**
 * Colors the combat target tile by resolved channel winners (shadow visual verifier).
 */
public final class TickBusWinnerTileOverlay implements GameOverlay {

    private static final Color OFF = new Color(240, 70, 70, 200);
    private static final Color SUS = new Color(70, 220, 90, 200);
    private static final Color DEF = new Color(70, 200, 240, 200);
    private static final BasicStroke STROKE = new BasicStroke(2.5f);

    @Override
    public String name() {
        return "tickbus-tiles";
    }

    @Override
    public void render(Graphics2D g, OverlayContext ctx) {
        if (!OverlayConfig.enabled() || ctx == null || ctx.bridge == null || !ctx.loggedIn) {
            return;
        }
        OverlayState s = OverlayPublisher.current();
        if (s == null) {
            return;
        }
        RuneLiteBridge b = ctx.bridge;
        Object player = b.localPlayer();
        if (player == null) {
            return;
        }
        Object target = b.interacting(player);
        if (target == null) {
            return;
        }
        Object world = b.worldLocationOfActor(target);
        if (world == null) {
            return;
        }
        Polygon poly = b.worldTilePoly(world);
        if (poly == null) {
            return;
        }
        drawChannelRing(g, poly, s, 0, OFF);
        drawChannelRing(g, poly, s, 1, SUS);
        drawChannelRing(g, poly, s, 2, DEF);
    }

    private static void drawChannelRing(Graphics2D g, Polygon poly, OverlayState s, int ch, Color color) {
        ChannelWinner w = s.winners[ch];
        if (w == null || !w.active) {
            return;
        }
        TickDispatchState ds = TickDispatchState.values()[
                Math.min(w.dispatchStateOrdinal, TickDispatchState.values().length - 1)];
        if (ds == TickDispatchState.LABEL_ONLY || ds == TickDispatchState.NO_DISPATCHER) {
            return;
        }
        int alpha = w.sidecarAgeStale ? 110 : 55;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        g.fillPolygon(poly);
        g.setStroke(STROKE);
        g.setColor(color);
        g.drawPolygon(poly);
    }
}
