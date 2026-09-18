package com.bot.core.bus;

/**
 * Mutable intent slot reused via {@link IntentPool}. Treat as immutable after {@link TickBus#publish(Intent)}.
 */
public final class Intent {

    private ActionKind kind;
    private int itemId;
    private int npcIndex;
    private int slotIndex;
    private ActionPriority priority;
    private int advisorOrdinal;
    private long bornTick;
    private int ttlTicks;
    private int precondHash;
    private int precondMask;
    private int rank;

    Intent() {
        reset();
    }

    void reset() {
        kind = ActionKind.IDLE;
        itemId = 0;
        npcIndex = -1;
        slotIndex = -1;
        priority = ActionPriority.BACKGROUND;
        advisorOrdinal = 127;
        bornTick = 0L;
        ttlTicks = 0;
        precondHash = 0;
        precondMask = 0;
        rank = 0;
    }

    Intent configure(ActionKind kind,
                     ActionPriority priority,
                     int itemId,
                     int npcIndex,
                     int slotIndex,
                     int advisorOrdinal,
                     long bornTick,
                     int ttlTicks,
                     int precondHash,
                     int precondMask) {
        this.kind = kind;
        this.priority = priority;
        this.itemId = itemId;
        this.npcIndex = npcIndex;
        this.slotIndex = slotIndex;
        this.advisorOrdinal = advisorOrdinal & 0x7F;
        this.bornTick = bornTick;
        this.ttlTicks = ttlTicks;
        this.precondHash = precondHash;
        this.precondMask = precondMask;
        this.rank = (priority.getWeight() << 8) | (128 - this.advisorOrdinal);
        return this;
    }

    public int rank() {
        return rank;
    }

    public boolean isValidAt(long tick) {
        return tick >= bornTick && tick < bornTick + (long) ttlTicks;
    }

    /**
     * Masked precondition check. {@code precondMask == 0} means unconditional (always satisfied).
     * Strict equality on the full fingerprint false-fails when unrelated bits drift between
     * sidecar evalTick and consumption — the mask selects only bits this advice depends on.
     */
    public boolean precondSatisfied(int stateFingerprint) {
        if (precondMask == 0) {
            return true;
        }
        return (stateFingerprint & precondMask) == (precondHash & precondMask);
    }

    public ActionKind kind() {
        return kind;
    }

    public int itemId() {
        return itemId;
    }

    public int npcIndex() {
        return npcIndex;
    }

    public int slotIndex() {
        return slotIndex;
    }

    public ActionPriority priority() {
        return priority;
    }

    public int advisorOrdinal() {
        return advisorOrdinal;
    }

    public long bornTick() {
        return bornTick;
    }

    public int ttlTicks() {
        return ttlTicks;
    }

    public int precondHash() {
        return precondHash;
    }

    public int precondMask() {
        return precondMask;
    }
}
