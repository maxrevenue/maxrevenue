package com.bot.core.bus;

/**
 * Preallocated intents for one advisor. Advisors must not {@code new Intent()} in {@code evaluate}.
 */
public final class IntentPool {

    private final Intent[] pool;
    private final int advisorOrdinal;
    private int cursor;
    private int checkedOut;

    public IntentPool(int advisorOrdinal, int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity");
        }
        if (advisorOrdinal < 0 || advisorOrdinal > 127) {
            throw new IllegalArgumentException("advisorOrdinal");
        }
        this.advisorOrdinal = advisorOrdinal;
        this.pool = new Intent[capacity];
        for (int i = 0; i < capacity; i++) {
            pool[i] = new Intent();
        }
    }

    /**
     * Returns a configured intent slot, or {@code null} when the pool is exhausted for this tick.
     */
    public Intent obtain(ActionKind kind,
                         ActionPriority priority,
                         int itemId,
                         int npcIndex,
                         int slotIndex,
                         long bornTick,
                         int ttlTicks,
                         int precondHash,
                         int precondMask) {
        return obtain(kind, priority, itemId, npcIndex, slotIndex, bornTick, ttlTicks,
                precondHash, precondMask, IntentBylines.NONE);
    }

    public Intent obtain(ActionKind kind,
                         ActionPriority priority,
                         int itemId,
                         int npcIndex,
                         int slotIndex,
                         long bornTick,
                         int ttlTicks,
                         int precondHash,
                         int precondMask,
                         int bylineId) {
        if (cursor >= pool.length) {
            return null;
        }
        Intent intent = pool[cursor++];
        checkedOut++;
        return intent.configure(kind, priority, itemId, npcIndex, slotIndex, advisorOrdinal,
                bornTick, ttlTicks, precondHash, precondMask, bylineId);
    }

    /** Call at the start of each advisor evaluate pass (orchestrator clears bus, pool resets here). */
    public void beginEvaluate() {
        cursor = 0;
        checkedOut = 0;
    }

    /** Returns intents obtained but not {@link #release()}d this evaluate pass (tests / leak detection). */
    public int checkedOut() {
        return checkedOut;
    }

    /** Balance {@link #obtain} when an intent is not published to the bus (early return paths). */
    public void release() {
        if (checkedOut > 0) {
            checkedOut--;
        }
    }
}
