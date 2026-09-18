package com.bot.core.sidecar;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;

/**
 * Preallocated sidecar frame slot (reused in a ring of 8 on the reader thread).
 */
public final class SidecarPayload {

    public long evalTick;
    public long ackTick;
    public ActionKind kind;
    public ActionPriority priority;
    public int ttlTicks;
    public int advisorOrdinal;
    public int precondMask;
    public int precondHash;
    public int itemId;
    public int npcIndex;
    public int slotIndex;

    public void reset() {
        evalTick = 0L;
        ackTick = 0L;
        kind = ActionKind.IDLE;
        priority = ActionPriority.BACKGROUND;
        ttlTicks = 0;
        advisorOrdinal = 0;
        precondMask = 0;
        precondHash = 0;
        itemId = 0;
        npcIndex = -1;
        slotIndex = -1;
    }
}
