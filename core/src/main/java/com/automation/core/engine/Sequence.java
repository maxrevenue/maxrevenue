package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregating combinator: evaluates every child and concatenates their intents.
 *
 * <p>Unlike a classic behavior-tree sequence, children here represent
 * independent concerns that can all fire on the same tick (for example: eat to
 * survive <em>and</em> fire a spec). The coordinator later sorts the combined
 * output by priority.
 */
public final class Sequence implements BehaviorNode {

    private final List<BehaviorNode> children;

    public Sequence(List<BehaviorNode> children) {
        this.children = List.copyOf(Objects.requireNonNull(children, "children"));
    }

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        List<ActionIntent> out = new ArrayList<>();
        for (BehaviorNode child : children) {
            out.addAll(child.evaluate(state));
        }
        return List.copyOf(out);
    }
}
