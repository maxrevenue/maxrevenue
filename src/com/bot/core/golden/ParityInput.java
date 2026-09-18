package com.bot.core.golden;

import com.bot.core.orchestrator.EliminationReason;

/**
 * Parity gate definition: {@code goldenDiff} classifications are computed solely from resolved
 * channel winners vs the recorded {@code legacyAction} per tick. {@code UNCOMPARABLE} ticks
 * ({@code NO_OPINION}, {@code OUT_OF_VOCAB}) are excluded from all rates and reported as raw
 * counts only. Outcome counters and sidecar latency metrics exist in the telemetry schema for
 * post-parity analysis and may not appear in, influence, or gate any classification category.
 * The oracle is the legacy monolith's actual dispatch; transcribed advisors are the candidate
 * under test, never a reference.
 */
public final class ParityInput {

    public final long tickIndex;
    public final String orchWinnerSummary;
    public final String legacyAction;
    public final String uncomparableSubtype;

    public ParityInput(long tickIndex,
                       String orchWinnerSummary,
                       String legacyAction,
                       String uncomparableSubtype) {
        this.tickIndex = tickIndex;
        this.orchWinnerSummary = orchWinnerSummary == null ? "" : orchWinnerSummary;
        this.legacyAction = legacyAction == null ? "" : legacyAction;
        this.uncomparableSubtype = uncomparableSubtype == null ? "" : uncomparableSubtype;
    }
}
