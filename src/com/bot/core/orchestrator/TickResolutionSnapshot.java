package com.bot.core.orchestrator;

import com.bot.core.bus.Intent;

/**
 * Single resolution pass output shared by NDJSON recorder and overlay (must not diverge).
 */
public final class TickResolutionSnapshot {

    public final Intent[] winners;
    public final EliminationReason[] eliminationReasons;
    public final int[] dispatchStateOrdinals;

    public TickResolutionSnapshot(Intent[] winners,
                                  EliminationReason[] eliminationReasons,
                                  int[] dispatchStateOrdinals) {
        this.winners = winners;
        this.eliminationReasons = eliminationReasons;
        this.dispatchStateOrdinals = dispatchStateOrdinals;
    }
}
