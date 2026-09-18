package com.bot.overlay;

import com.bot.core.bus.ActionKind;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.telemetry.TickDispatchState;
import com.sun.java.fontmgr.overlay.GameOverlay;
import com.sun.java.fontmgr.overlay.OverlayContext;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

/**
 * Render-side HUD: tick, vitals, sidecar health, per-channel winners + dispatch feasibility.
 */
public final class TickBusHudOverlay implements GameOverlay {

    private static final Color BG = new Color(0, 0, 0, 160);
    private static final Color FG = new Color(240, 240, 240, 230);
    private static final Color WARN = new Color(255, 180, 60, 230);
    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private static final String[] CHANNEL_SHORT = {"OFF", "SUS", "DEF"};
    private static final Color[] CHANNEL_COLOR = {
            new Color(240, 70, 70, 200),
            new Color(70, 220, 90, 200),
            new Color(70, 200, 240, 200)
    };

    @Override
    public String name() {
        return "tickbus-hud";
    }

    @Override
    public void render(Graphics2D g, OverlayContext ctx) {
        if (!OverlayConfig.enabled()) {
            return;
        }
        OverlayState s = OverlayPublisher.current();
        if (s == null) {
            return;
        }
        Font prevFont = g.getFont();
        Color prevColor = g.getColor();
        try {
            g.setFont(FONT);
            int x = 8;
            int y = 18;
            int lineH = 14;
            g.setColor(BG);
            g.fillRect(x - 4, y - 14, 360, lineH * 7 + 8);
            g.setColor(FG);
            g.drawString("TB tick=" + s.tickIndex + " hp=" + s.hp + " spec=" + s.specEnergy, x, y);
            y += lineH;
            String sc = s.sidecarHealthy ? "OK" : "DOWN";
            g.drawString("SC:" + sc + " lag=" + s.sidecarAckLag + " maskless=" + s.masklessIntents, x, y);
            y += lineH;
            if (s.eatLeaseTicksRemaining > 0 || s.attackLeaseTicksRemaining > 0) {
                g.setColor(WARN);
                g.drawString("lease eat=" + s.eatLeaseTicksRemaining + " atk=" + s.attackLeaseTicksRemaining, x, y);
                y += lineH;
                g.setColor(FG);
            }
            for (int ch = 0; ch < s.winners.length; ch++) {
                ChannelWinner w = s.winners[ch];
                g.setColor(CHANNEL_COLOR[ch]);
                String kind = w.active && w.kindOrdinal >= 0
                        ? ActionKind.values()[w.kindOrdinal].name()
                        : "-";
                TickDispatchState ds = TickDispatchState.values()[Math.min(w.dispatchStateOrdinal,
                        TickDispatchState.values().length - 1)];
                String drop = w.dropReasonOrdinal > 0
                        ? EliminationReason.values()[w.dropReasonOrdinal].name()
                        : "";
                g.drawString(CHANNEL_SHORT[ch] + " " + kind + " ds=" + ds.name()
                        + (drop.isEmpty() ? "" : " drop=" + drop), x, y);
                y += lineH;
            }
        } finally {
            g.setFont(prevFont);
            g.setColor(prevColor);
        }
    }
}
