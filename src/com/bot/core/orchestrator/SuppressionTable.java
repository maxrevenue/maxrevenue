package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;

/**
 * Per-kind tick deadlines (lease ends when {@code tickIndex >= deadline}).
 */
public final class SuppressionTable {

    private final long[] kindDeadlineUntil;
    private final int kindCount;

    public SuppressionTable() {
        kindCount = ActionKind.values().length;
        kindDeadlineUntil = new long[kindCount];
    }

    public boolean isSuppressed(ActionKind kind, long tickIndex) {
        if (kind == null) {
            return false;
        }
        return tickIndex < kindDeadlineUntil[kind.ordinal()];
    }

    public void lease(ActionKind kind, long tickIndex, int durationTicks) {
        if (kind == null || durationTicks <= 0) {
            return;
        }
        long until = tickIndex + (long) durationTicks;
        int ord = kind.ordinal();
        if (until > kindDeadlineUntil[ord]) {
            kindDeadlineUntil[ord] = until;
        }
    }

    public void leaseUntil(ActionKind kind, long untilTick) {
        if (kind == null) {
            return;
        }
        int ord = kind.ordinal();
        if (untilTick > kindDeadlineUntil[ord]) {
            kindDeadlineUntil[ord] = untilTick;
        }
    }

    /** For tests and telemetry snapshots. */
    public long deadlineFor(ActionKind kind) {
        return kindDeadlineUntil[kind.ordinal()];
    }
}
