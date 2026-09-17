package com.automation.core.engine;

import com.automation.core.engine.spec.SpecStrategy;
import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;
import com.automation.core.model.SpecAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Behavior-tree leaf that walks an ordered list of {@link SpecStrategy}s and
 * emits the first matching {@link SpecAction}.
 */
public final class SpecDecisionNode implements BehaviorNode {

    private final List<SpecStrategy> strategies;

    public SpecDecisionNode(List<SpecStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("strategies must be non-empty");
        }
        this.strategies = List.copyOf(new ArrayList<>(strategies));
    }

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        Objects.requireNonNull(state, "state");
        for (SpecStrategy strategy : strategies) {
            if (strategy.shouldExecute(state)) {
                return List.of(new SpecAction(strategy.label(), strategy.minSpecPct()));
            }
        }
        return List.of();
    }
}
