package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.AttackAction;
import com.automation.core.model.GameState;

import java.util.List;

/**
 * Leaf rule: the baseline fallback — if we have a target, keep attacking it.
 * Sits last in an offensive {@link Selector} so it only fires when nothing more
 * specific (e.g. a spec) applied.
 */
public final class ReattackNode implements BehaviorNode {

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        if (!state.hasTarget()) {
            return List.of();
        }
        return List.of(new AttackAction(AttackAction.LAST_TARGET));
    }
}
