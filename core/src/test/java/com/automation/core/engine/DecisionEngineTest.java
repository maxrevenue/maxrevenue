package com.automation.core.engine;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.ActionPriority;
import com.automation.core.model.ActivePrayers;
import com.automation.core.model.AttackAction;
import com.automation.core.model.EatAction;
import com.automation.core.model.EquipmentSnapshot;
import com.automation.core.model.EquipmentSlot;
import com.automation.core.model.GameState;
import com.automation.core.model.InventoryItem;
import com.automation.core.model.InventorySnapshot;
import com.automation.core.model.PlayerState;
import com.automation.core.model.SpecAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fully headless tests for {@link BehaviorTreeDecisionEngine}: no live client, no
 * reflection, no {@code game.jar} — just hand-built {@link GameState} snapshots
 * in and asserted {@link ActionIntent}s out.
 */
class DecisionEngineTest {

    private static final int AGS_ITEM_ID = 11802;
    private static final int SHARK_ITEM_ID = 385;
    private static final int FOOD_SLOT = 3;
    private static final int EAT_THRESHOLD_PCT = 50;
    private static final int MIN_SPEC_PCT = 50;

    private final DecisionEngine engine =
            BehaviorTreeDecisionEngine.sample(EAT_THRESHOLD_PCT, Set.of(SHARK_ITEM_ID), MIN_SPEC_PCT);

    private static PlayerState player(int hp, int maxHp, int specEnergy, EquipmentSnapshot gear) {
        return new PlayerState(hp, maxHp, 99, specEnergy, -1, ActivePrayers.none(), gear);
    }

    private static InventorySnapshot withShark() {
        return new InventorySnapshot(List.of(new InventoryItem(FOOD_SLOT, SHARK_ITEM_ID, 1)));
    }

    private static PlayerState opponentWithAgs() {
        return player(99, 99, 100, new EquipmentSnapshot(Map.of(EquipmentSlot.WEAPON, AGS_ITEM_ID)));
    }

    @Test
    @DisplayName("Low HP against an AGS opponent yields an EatAction for the food slot")
    void lowHpYieldsEmergencyEat() {
        GameState state = new GameState(
                1,
                player(15, 99, 0, EquipmentSnapshot.empty()),
                Optional.of(opponentWithAgs()),
                withShark());

        List<ActionIntent> intents = engine.evaluate(state);

        assertFalse(intents.isEmpty(), "expected at least one intent at 15/99 HP");
        assertInstanceOf(EatAction.class, intents.get(0), "eat must be the highest-priority intent");
        assertEquals(FOOD_SLOT, ((EatAction) intents.get(0)).inventorySlot());
        assertEquals(ActionPriority.EMERGENCY_HEAL, intents.get(0).priority());
    }

    @Test
    @DisplayName("Full HP with no spec energy falls back to a re-attack, never an eat")
    void fullHpFallsBackToAttack() {
        GameState state = new GameState(
                2,
                player(99, 99, 0, EquipmentSnapshot.empty()),
                Optional.of(opponentWithAgs()),
                withShark());

        List<ActionIntent> intents = engine.evaluate(state);

        assertTrue(intents.stream().noneMatch(EatAction.class::isInstance), "should not eat at full HP");
        assertTrue(intents.stream().anyMatch(AttackAction.class::isInstance), "should re-attack");
    }

    @Test
    @DisplayName("Spec energy available makes the Selector choose a spec over a plain attack")
    void specReadyChoosesSpecOverAttack() {
        GameState state = new GameState(
                3,
                player(99, 99, 75, new EquipmentSnapshot(Map.of(EquipmentSlot.WEAPON, AGS_ITEM_ID))),
                Optional.of(player(38, 99, 0, EquipmentSnapshot.empty())),
                withShark());

        List<ActionIntent> intents = engine.evaluate(state);

        assertTrue(intents.stream().anyMatch(SpecAction.class::isInstance), "should spec when energy is up");
        assertTrue(intents.stream().noneMatch(AttackAction.class::isInstance),
                "selector must pick spec instead of a plain attack");
    }

    @Test
    @DisplayName("Eat and spec on the same tick come back priority-sorted (eat first)")
    void simultaneousIntentsArePrioritySorted() {
        GameState state = new GameState(
                4,
                player(15, 99, 75, new EquipmentSnapshot(Map.of(EquipmentSlot.WEAPON, AGS_ITEM_ID))),
                Optional.of(player(38, 99, 0, EquipmentSnapshot.empty())),
                withShark());

        List<ActionIntent> intents = engine.evaluate(state);

        assertInstanceOf(EatAction.class, intents.get(0), "emergency heal outranks the spec");
        assertTrue(intents.stream().anyMatch(SpecAction.class::isInstance), "spec should also be queued");
        // Verify the whole list is sorted by descending priority.
        for (int i = 1; i < intents.size(); i++) {
            assertTrue(intents.get(i - 1).priorityLevel() >= intents.get(i).priorityLevel(),
                    "intents must be sorted by descending priority");
        }
    }

    @Test
    @DisplayName("No target and full HP produces no intents")
    void idleProducesNothing() {
        GameState state = new GameState(
                5,
                player(99, 99, 100, EquipmentSnapshot.empty()),
                Optional.empty(),
                withShark());

        assertTrue(engine.evaluate(state).isEmpty(), "idle with full HP and no target should be a no-op");
    }
}
