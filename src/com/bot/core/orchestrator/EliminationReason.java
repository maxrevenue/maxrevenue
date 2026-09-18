package com.bot.core.orchestrator;

public enum EliminationReason {
    NONE,
    RANK,
    STALE_TICK,
    STALE_STATE,
    SUPPRESSED_BY_RULE,
    SUPPRESSED_LEASE,
    NOOP,
    DROPPED_PUBLISH,
    SIDECAR_UNHEALTHY
}
