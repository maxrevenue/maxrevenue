package com.bot.core.bus;

/**
 * Fixed-capacity intent bus for one tick. Hot path must not allocate.
 */
public final class TickBus {

    public static final int CAPACITY = 32;

    private final Intent[] intents;
    private int count;
    private long currentTick;
    private int droppedPublishes;
    private int maxRankDropped;

    public TickBus() {
        intents = new Intent[CAPACITY];
        count = 0;
        currentTick = 0L;
        droppedPublishes = 0;
        maxRankDropped = 0;
    }

    public void beginTick(long tickIndex) {
        currentTick = tickIndex;
    }

    public void clear() {
        count = 0;
        droppedPublishes = 0;
        maxRankDropped = 0;
    }

    public long currentTick() {
        return currentTick;
    }

    public int droppedPublishes() {
        return droppedPublishes;
    }

    /** Max {@link Intent#rank()} among intents dropped due to bus overflow this tick. */
    public int maxRankDropped() {
        return maxRankDropped;
    }

    public int size() {
        return count;
    }

    public Intent get(int index) {
        if (index < 0 || index >= count) {
            return null;
        }
        return intents[index];
    }

    /**
     * Publishes an intent reference (from {@link IntentPool}). When full, evicts the lowest-rank
     * intent if the incoming intent outranks it; otherwise counts a drop.
     */
    public void publish(Intent intent) {
        if (intent == null) {
            return;
        }
        if (count < CAPACITY) {
            intents[count] = intent;
            count++;
            return;
        }
        int lowestIndex = 0;
        int lowestRank = intents[0].rank();
        for (int i = 1; i < CAPACITY; i++) {
            int r = intents[i].rank();
            if (r < lowestRank) {
                lowestRank = r;
                lowestIndex = i;
            }
        }
        // Strictly greater: equal rank keeps the incumbent (advisor registration order).
        if (intent.rank() > lowestRank) {
            noteDrop(lowestRank);
            intents[lowestIndex] = intent;
        } else {
            noteDrop(intent.rank());
        }
    }

    private void noteDrop(int droppedRank) {
        droppedPublishes++;
        if (droppedRank > maxRankDropped) {
            maxRankDropped = droppedRank;
        }
    }

    /**
     * Highest-rank valid intent for the channel at {@link #currentTick()}, or {@code null}.
     */
    public Intent resolveChannel(Channel channel) {
        Intent best = null;
        int bestRank = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            Intent intent = intents[i];
            if (!intent.isValidAt(currentTick)) {
                continue;
            }
            if (!channel.contains(intent.kind())) {
                continue;
            }
            int r = intent.rank();
            if (r > bestRank) {
                bestRank = r;
                best = intent;
            }
        }
        return best;
    }
}
