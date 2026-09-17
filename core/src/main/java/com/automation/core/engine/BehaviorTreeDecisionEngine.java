package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A {@link DecisionEngine} backed by a lightweight behavior tree.
 *
 * <p>The engine owns a root {@link BehaviorNode}; {@link #evaluate(GameState)}
 * collects every intent the tree yields and returns them sorted by descending
 * priority. It has no dependency on any client or reflection class, so it is
 * fully unit-testable against hand-built {@link GameState} snapshots.
 */
public final class BehaviorTreeDecisionEngine implements DecisionEngine {

    private static final Comparator<ActionIntent> BY_PRIORITY_DESC =
            Comparator.comparingInt(ActionIntent::priorityLevel).reversed();

    private final BehaviorNode root;

    public BehaviorTreeDecisionEngine(BehaviorNode root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        Objects.requireNonNull(state, "state");
        List<ActionIntent> intents = new ArrayList<>(root.evaluate(state));
        intents.sort(BY_PRIORITY_DESC);
        return List.copyOf(intents);
    }

    /**
     * Build the sample tree used in tests and as a starting point:
     *
     * <pre>
     * Sequence
     *  ├─ EmergencyEatNode(eatThresholdPercent, foodItemIds)   // survive
     *  └─ Selector                                             // one offensive action
     *       ├─ SpecWhenReadyNode(minSpecPct)
     *       └─ ReattackNode
     * </pre>
     *
     * The {@link Sequence} lets an eat and an offensive action fire on the same
     * tick, while the {@link Selector} makes spec-vs-reattack mutually exclusive.
     *
     * @param eatThresholdPercent HP% at or below which we eat
     * @param foodItemIds         item ids treated as food
     * @param minSpecPct          special-attack energy the spec setup needs
     */
    public static BehaviorTreeDecisionEngine sample(int eatThresholdPercent,
                                                    Set<Integer> foodItemIds,
                                                    int minSpecPct) {
        BehaviorNode offensive = new Selector(List.of(
                new SpecWhenReadyNode(minSpecPct),
                new ReattackNode()));
        BehaviorNode root = new Sequence(List.of(
                new EmergencyEatNode(eatThresholdPercent, foodItemIds),
                offensive));
        return new BehaviorTreeDecisionEngine(root);
    }
}
