package com.bot.overlay;

/**
 * One resolved channel winner slot. Mutated on the tick thread before {@link OverlayPublisher} handoff.
 */
public final class ChannelWinner {

    public boolean active;
    public int channel;
    /** {@link com.bot.core.bus.ActionKind#ordinal()}, or {@code -1}. */
    public int kindOrdinal;
    public int npcIndex;
    public int itemId;
    /** {@link com.bot.core.telemetry.TickDispatchState#ordinal()}. */
    public int dispatchStateOrdinal;
    /** {@link com.bot.core.orchestrator.EliminationReason#ordinal()} when winner cleared by rules. */
    public int dropReasonOrdinal;

    public void clear() {
        active = false;
        channel = 0;
        kindOrdinal = -1;
        npcIndex = -1;
        itemId = 0;
        dispatchStateOrdinal = 0;
        dropReasonOrdinal = 0;
    }
}
