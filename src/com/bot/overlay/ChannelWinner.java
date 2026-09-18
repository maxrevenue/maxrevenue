package com.bot.overlay;

/**
 * One resolved channel slot. Mutated on the tick thread before {@link OverlayPublisher} handoff.
 */
public final class ChannelWinner {

    public boolean active;
    public int channel;
    public int kindOrdinal;
    public int npcIndex;
    public int itemId;
    public int dispatchStateOrdinal;
    /** Same {@link com.bot.core.orchestrator.EliminationReason} ordinals as NDJSON {@code channelDrops}. */
    public int dropReasonOrdinal;
    public long bornTick;
    /** {@code true} when {@code bornTick != snapshot.tickIndex} (sidecar grace replay). */
    public boolean sidecarAgeStale;

    public void clear() {
        active = false;
        channel = 0;
        kindOrdinal = -1;
        npcIndex = -1;
        itemId = 0;
        dispatchStateOrdinal = 0;
        dropReasonOrdinal = com.bot.core.orchestrator.EliminationReason.NONE.ordinal();
        bornTick = 0L;
        sidecarAgeStale = false;
    }
}
