package com.bot.overlay;

import com.sun.java.fontmgr.overlay.GameOverlay;
import com.sun.java.fontmgr.overlay.OverlayContext;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

/**
 * Render-side HUD — draws precomputed strings only (no tick-thread state, no per-frame allocation).
 */
public final class TickBusHudOverlay implements GameOverlay {

    private static final Color BG = new Color(0, 0, 0, 160);
    private static final Color FG = new Color(240, 240, 240, 230);
    private static final Color WARN = new Color(255, 180, 60, 230);
    private static final Font FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    private static final Color[] CHANNEL_COLOR = {
            new Color(240, 70, 70, 200),
            new Color(70, 220, 90, 200),
            new Color(70, 200, 240, 200)
    };

    private long lastSeenPublishSequence = -1L;
    private int framesSameSequence;

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
        if (s.publishSequence == lastSeenPublishSequence) {
            framesSameSequence++;
        } else {
            lastSeenPublishSequence = s.publishSequence;
            framesSameSequence = 0;
        }

        Font prevFont = g.getFont();
        Color prevColor = g.getColor();
        try {
            g.setFont(FONT);
            int x = 8;
            int y = 18;
            int lineH = 14;
            int lines = 3 + s.winners.length + (s.leaseLine.isEmpty() ? 0 : 1)
                    + (framesSameSequence > 45 ? 1 : 0);
            g.setColor(BG);
            g.fillRect(x - 4, y - 14, 380, lineH * lines + 8);
            g.setColor(FG);
            g.drawString(s.headerLine, x, y);
            y += lineH;
            g.drawString(s.sidecarLine, x, y);
            y += lineH;
            if (!s.leaseLine.isEmpty()) {
                g.setColor(WARN);
                g.drawString(s.leaseLine, x, y);
                y += lineH;
                g.setColor(FG);
            }
            if (framesSameSequence > 45) {
                g.setColor(WARN);
                g.drawString("STALE frames=" + framesSameSequence + " (tick frozen?)", x, y);
                y += lineH;
                g.setColor(FG);
            }
            for (int ch = 0; ch < s.channelLines.length; ch++) {
                g.setColor(CHANNEL_COLOR[ch]);
                g.drawString(s.channelLines[ch], x, y);
                y += lineH;
            }
        } finally {
            g.setFont(prevFont);
            g.setColor(prevColor);
        }
    }
}
