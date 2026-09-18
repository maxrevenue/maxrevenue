package com.bot.overlay;

/**
 * Immutable-after-handoff snapshot for the paint thread. Two instances alternate in {@link OverlayPublisher}.
 */
public final class OverlayState {

    public long tickIndex;
    public int hp;
    public int specEnergy;
    public boolean sidecarHealthy;
    public long sidecarAckLag;
    public int masklessIntents;
    /** Ticks until {@code ATTACK} lease expires ({@code 0} = not suppressed). */
    public int attackLeaseTicksRemaining;
    /** Ticks until {@code EAT} lease expires. */
    public int eatLeaseTicksRemaining;

    public final ChannelWinner[] winners;

    public OverlayState() {
        winners = new ChannelWinner[3];
        for (int i = 0; i < winners.length; i++) {
            winners[i] = new ChannelWinner();
        }
    }

    public void reset() {
        tickIndex = 0L;
        hp = -1;
        specEnergy = -1;
        sidecarHealthy = true;
        sidecarAckLag = 0L;
        masklessIntents = 0;
        attackLeaseTicksRemaining = 0;
        eatLeaseTicksRemaining = 0;
        for (int i = 0; i < winners.length; i++) {
            winners[i].clear();
        }
    }
}
