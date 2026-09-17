package com.automation.core.harness;

import com.automation.core.model.GameState;

import java.util.Objects;

/**
 * One EchoForge NDJSON tick: an immutable {@link GameState} plus the legacy
 * action the agent recorded for that tick.
 */
public record ReplayTick(GameState state, ActionExpectation expected, String sourceLine) {

    public ReplayTick {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expected, "expected");
        sourceLine = sourceLine == null ? "" : sourceLine;
    }

    public long tickCount() {
        return state.tickCount();
    }
}
