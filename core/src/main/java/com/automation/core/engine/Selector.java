package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;

import java.util.List;
import java.util.Objects;

/**
 * OR combinator: returns the intents of the first child that produces any,
 * and stops there. Use it to pick exactly one of several mutually-exclusive
 * options (e.g. spec <em>or</em> re-attack, but not both).
 */
public final class Selector implements BehaviorNode {

    private final List<BehaviorNode> children;

    public Selector(List<BehaviorNode> children) {
        this.children = List.copyOf(Objects.requireNonNull(children, "children"));
    }

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        for (BehaviorNode child : children) {
            List<ActionIntent> result = child.evaluate(state);
            if (!result.isEmpty()) {
                return result;
            }
        }
        return List.of();
    }
}
