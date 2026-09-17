package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;

import java.util.List;

/**
 * PHASE 2 — THINK.
 *
 * <p>Pure decision logic: it evaluates an immutable {@link GameState} and returns
 * the {@link ActionIntent}s it wants executed this tick. Implementations
 * <strong>must not</strong> import or reference any client / reflection class,
 * which is what makes the engine 100% headless-testable.
 */
public interface DecisionEngine {

    /**
     * Evaluate the snapshot and return the intended actions, ordered by
     * descending {@link ActionIntent#priorityLevel()} (highest first).
     *
     * @param state immutable world snapshot
     * @return prioritized, possibly empty, immutable list of intents
     */
    List<ActionIntent> evaluate(GameState state);
}
