package com.automation.core.coordinator;

import com.automation.core.dispatcher.ActionDispatcher;
import com.automation.core.engine.BehaviorTreeDecisionEngine;
import com.automation.core.engine.DecisionEngine;
import com.automation.core.model.ActionIntent;
import com.automation.core.model.ActivePrayers;
import com.automation.core.model.EatAction;
import com.automation.core.model.EquipmentSnapshot;
import com.automation.core.model.GameState;
import com.automation.core.model.InventoryItem;
import com.automation.core.model.InventorySnapshot;
import com.automation.core.model.PlayerState;
import com.automation.core.sensor.StateSensor;
import com.automation.core.sensor.StubStateSensor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies the coordinator wires Sense &rarr; Think &rarr; sort &rarr; Act and never throws. */
class CombatCoordinatorTest {

    private static final int SHARK_ITEM_ID = 385;

    private static GameState lowHpState() {
        PlayerState me = new PlayerState(10, 99, 99, 100, -1, ActivePrayers.none(), EquipmentSnapshot.empty());
        PlayerState target = new PlayerState(99, 99, 99, 100, -1, ActivePrayers.none(), EquipmentSnapshot.empty());
        InventorySnapshot inv = new InventorySnapshot(List.of(new InventoryItem(0, SHARK_ITEM_ID, 1)));
        return new GameState(1, me, Optional.of(target), inv);
    }

    @Test
    @DisplayName("onTick senses, decides, and dispatches priority-sorted intents (eat first)")
    void pipelineDispatchesSortedIntents() {
        StateSensor sensor = new StubStateSensor(lowHpState());
        DecisionEngine brain = BehaviorTreeDecisionEngine.sample(50, Set.of(SHARK_ITEM_ID), 50);

        AtomicReference<List<ActionIntent>> dispatched = new AtomicReference<>();
        ActionDispatcher dispatcher = dispatched::set;

        new CombatCoordinator(sensor, brain, dispatcher).onTick();

        List<ActionIntent> result = dispatched.get();
        assertNotNull(result, "dispatcher should have been called");
        assertInstanceOf(EatAction.class, result.get(0), "eat must dispatch first at 10/99 HP");
    }

    @Test
    @DisplayName("A failure inside any phase is contained, not thrown onto the tick thread")
    void exceptionsAreContained() {
        StateSensor sensor = StubStateSensor.idle();
        DecisionEngine explodingBrain = state -> {
            throw new IllegalStateException("boom");
        };
        AtomicReference<List<ActionIntent>> dispatched = new AtomicReference<>();
        ActionDispatcher dispatcher = dispatched::set;

        CombatCoordinator coordinator = new CombatCoordinator(sensor, explodingBrain, dispatcher);

        assertDoesNotThrow(coordinator::onTick, "onTick must swallow and log pipeline errors");
        assertNull(dispatched.get(), "dispatch should not run when the brain fails");
    }
}
