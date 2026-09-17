package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.ActionPriority;
import com.automation.core.model.EatAction;
import com.automation.core.model.GameState;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Leaf rule: if local HP has dropped to or below {@code hpThresholdPercent} and
 * a food item is in the inventory, emit an {@link EatAction} for that slot at
 * {@link ActionPriority#EMERGENCY_HEAL}.
 */
public final class EmergencyEatNode implements BehaviorNode {

    private final int hpThresholdPercent;
    private final Set<Integer> foodItemIds;

    public EmergencyEatNode(int hpThresholdPercent, Set<Integer> foodItemIds) {
        this.hpThresholdPercent = hpThresholdPercent;
        this.foodItemIds = Set.copyOf(Objects.requireNonNull(foodItemIds, "foodItemIds"));
    }

    @Override
    public List<ActionIntent> evaluate(GameState state) {
        if (state.localPlayer().hpPercent() > hpThresholdPercent) {
            return List.of();
        }
        return state.inventory().firstOfAny(foodItemIds)
                .<List<ActionIntent>>map(item -> List.of(new EatAction(item.slot(), ActionPriority.EMERGENCY_HEAL)))
                .orElseGet(List::of);
    }
}
