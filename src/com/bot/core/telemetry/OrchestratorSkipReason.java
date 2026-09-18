package com.bot.core.telemetry;

/**
 * Why the orchestrator did not run full arbitration this tick (stub {@code orch} line for pairing).
 */
public enum OrchestratorSkipReason {
    /** Not skipped — full orchestrator evaluation ran. */
    NONE,
    COMBO_PHASE,
    PENDING_SPEC,
    PAUSED,
    DISABLED
}
