package com.automation.core.coordinator;

import com.automation.core.dispatcher.ActionDispatcher;
import com.automation.core.engine.DecisionEngine;
import com.automation.core.model.ActionIntent;
import com.automation.core.model.GameState;
import com.automation.core.sensor.StateSensor;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Drives one tick of the Sense &rarr; Think &rarr; Act pipeline.
 *
 * <p>The coordinator owns no combat logic of its own; it wires the three phases
 * together: read an immutable {@link GameState}, ask the {@link DecisionEngine}
 * what to do, sort the intents by descending priority, and hand them to the
 * {@link ActionDispatcher}. The whole loop runs inside an exception boundary so a
 * fault in any phase is logged rather than propagated onto the client thread.
 */
public final class CombatCoordinator {

    private static final Logger LOG = System.getLogger(CombatCoordinator.class.getName());

    private static final Comparator<ActionIntent> BY_PRIORITY_DESC =
            Comparator.comparingInt(ActionIntent::priorityLevel).reversed();

    private final StateSensor sensor;
    private final DecisionEngine brain;
    private final ActionDispatcher dispatcher;

    public CombatCoordinator(StateSensor sensor, DecisionEngine brain, ActionDispatcher dispatcher) {
        this.sensor = Objects.requireNonNull(sensor, "sensor");
        this.brain = Objects.requireNonNull(brain, "brain");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Execute a single pipeline pass. Never throws: failures are logged. */
    public void onTick() {
        try {
            GameState state = sensor.readGameState();

            List<ActionIntent> intents = new ArrayList<>(brain.evaluate(state));
            intents.sort(BY_PRIORITY_DESC);

            dispatcher.dispatch(List.copyOf(intents));
        } catch (Exception e) {
            LOG.log(Level.ERROR, "Error during combat tick pipeline", e);
        }
    }
}
