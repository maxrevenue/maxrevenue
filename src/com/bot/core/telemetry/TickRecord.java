package com.bot.core.telemetry;

import com.bot.core.bus.ActionKind;
import com.bot.core.orchestrator.EliminationReason;

/**
 * Mutable ring slot filled on the tick thread (primitives only).
 */
public final class TickRecord {

    public long tickIndex;
    public int busSize;
    public int droppedPublishes;
    public int masklessIntents;
    public long sidecarAckLag;
    public boolean sidecarHealthy;
    public int recordsDropped;

    public final ActionKind[] winnerKind = new ActionKind[3];
    public final int[] winnerRank = new int[3];
    public final int[] winnerAdvisor = new int[3];
    public final EliminationReason[] channelDropReason = new EliminationReason[3];

    public void reset() {
        tickIndex = 0L;
        busSize = 0;
        droppedPublishes = 0;
        masklessIntents = 0;
        sidecarAckLag = 0L;
        sidecarHealthy = true;
        recordsDropped = 0;
        for (int i = 0; i < 3; i++) {
            winnerKind[i] = null;
            winnerRank[i] = 0;
            winnerAdvisor[i] = -1;
            channelDropReason[i] = EliminationReason.NONE;
        }
    }
}
