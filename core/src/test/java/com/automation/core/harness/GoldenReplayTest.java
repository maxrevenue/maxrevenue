package com.automation.core.harness;

import com.automation.core.engine.BehaviorTreeDecisionEngine;
import com.automation.core.engine.DecisionEngine;
import com.automation.core.model.ActionIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EchoForge golden-master suite: replays NDJSON tick fixtures through
 * {@link BehaviorTreeDecisionEngine} and asserts the top-priority
 * {@link ActionIntent} matches the recorded legacy {@code executedAction}.
 *
 * <p>Fully headless — no client attach, no reflection, no {@code game.jar}.
 */
class GoldenReplayTest {

    private static final int SHARK_ITEM_ID = 385;
    private static final int ANGLERFISH_ITEM_ID = 13441;
    private static final int EAT_THRESHOLD_PCT = 50;
    private static final int MIN_SPEC_PCT = 50;

    private final DecisionEngine engine = BehaviorTreeDecisionEngine.sample(
            EAT_THRESHOLD_PCT,
            Set.of(SHARK_ITEM_ID, ANGLERFISH_ITEM_ID),
            MIN_SPEC_PCT);

    static Stream<Arguments> fixtureTicks() {
        return NDJSONFixtureLoader.loadClasspathFixtures().stream()
                .map(tick -> Arguments.of(
                        "tick=" + tick.tickCount() + " expect=" + tick.expected().raw(),
                        tick));
    }

    static Stream<Arguments> namedFixtures() {
        return Stream.of(
                        "fixtures/sample_fight.ndjson",
                        "fixtures/dh-combo-fixture.ndjson",
                        "fixtures/survive-spec-fixture.ndjson")
                .flatMap(path -> NDJSONFixtureLoader.loadClasspathResource(path).stream()
                        .map(tick -> Arguments.of(path + "#" + tick.tickCount(), tick)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureTicks")
    @DisplayName("EchoForge replay: top ActionIntent matches recorded executedAction")
    void topIntentMatchesRecordedAction(String label, ReplayTick tick) {
        ActionExpectation expected = tick.expected();
        if (expected.kind() == ActionExpectation.Kind.OTHER) {
            // Fixtures may include observational ticks (prayer/gear) that the
            // sample tree does not yet model — skip rather than false-fail.
            return;
        }

        List<ActionIntent> intents = engine.evaluate(tick.state());
        assertFalse(intents.isEmpty(),
                () -> label + " produced no intents for state " + tick.state());

        ActionIntent top = intents.get(0);
        assertTrue(expected.matches(top),
                () -> label + " expected " + expected.kind()
                        + " (" + expected.raw() + ") but top intent was "
                        + top.getClass().getSimpleName() + " " + top
                        + " from intents=" + intents);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("namedFixtures")
    @DisplayName("EchoForge per-fixture replay coverage")
    void eachNamedFixtureReplaysCleanly(String label, ReplayTick tick) {
        // Ensures every shipped fixture file is readable and yields a GameState
        // the engine can evaluate without throwing.
        engine.evaluate(tick.state());
        assertTrue(tick.tickCount() >= 0, label + " must carry a non-negative tickCount");
        assertFalse(tick.expected().raw().isBlank(), label + " missing executedAction");
    }
}
