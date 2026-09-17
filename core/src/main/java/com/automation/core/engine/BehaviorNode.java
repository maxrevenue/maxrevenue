package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;

import java.util.List;

/**
 * A node in the lightweight behavior tree used by {@link BehaviorTreeDecisionEngine}.
 *
 * <p>Each node inspects the immutable {@link GameState} and contributes zero or
 * more {@link ActionIntent}s. Composite nodes ({@link Selector}, {@link Sequence})
 * combine children; leaf nodes encode a single combat rule. Returning an empty
 * list means "this node has nothing to do this tick".
 */
@FunctionalInterface
public interface BehaviorNode {

    /** Evaluate this node against the snapshot and return its intents (never null). */
    List<ActionIntent> evaluate(GameState state);
}
