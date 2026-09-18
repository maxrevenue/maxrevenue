package com.bot.overlay;

/**
 * Handoff snapshot for the paint thread. Filled completely on the tick thread, then published.
 *
 * <p>Two-buffer flip may tear by one frame under lag (paint straddling flip) — acceptable for debug HUD.
 */
public final class OverlayState {

    public long tickIndex;
    public long publishSequence;
    public int hp;
    public int specEnergy;
    public boolean sidecarHealthy;
    public long sidecarAckLag;
    public int masklessIntents;
    public int attackLeaseTicksRemaining;
    public int eatLeaseTicksRemaining;

    /** Precomputed on tick thread — paint uses drawString only. */
    public String headerLine;
    public String sidecarLine;
    public String leaseLine;

    public final ChannelWinner[] winners;
    public final String[] channelLines;

    public OverlayState() {
        winners = new ChannelWinner[3];
        channelLines = new String[3];
        for (int i = 0; i < winners.length; i++) {
            winners[i] = new ChannelWinner();
            channelLines[i] = "";
        }
    }

    public void reset() {
        tickIndex = 0L;
        publishSequence = 0L;
        hp = -1;
        specEnergy = -1;
        sidecarHealthy = true;
        sidecarAckLag = 0L;
        masklessIntents = 0;
        attackLeaseTicksRemaining = 0;
        eatLeaseTicksRemaining = 0;
        headerLine = "";
        sidecarLine = "";
        leaseLine = "";
        for (int i = 0; i < winners.length; i++) {
            winners[i].clear();
            channelLines[i] = "";
        }
    }
}
