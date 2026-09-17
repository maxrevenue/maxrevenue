package com.automation.core.harness;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the agent → loader contract: a line exactly as
 * {@code com.sun.java.fontmgr.TickRecorder.formatNdjson} writes it (compact
 * {@code executedAction} + extra {@code actionLabel}) must parse to the same
 * action kind, and fixture discovery must not depend on a hardcoded list.
 */
public class NDJSONFixtureLoaderTest {

    /** Shape produced by the agent after the recorder change. */
    private static final String AGENT_LINE =
            "{\"tickCount\":13,\"player\":{\"hp\":55,\"maxHp\":99,\"prayer\":33,"
                    + "\"specEnergy\":50,\"equipment\":{\"3\":11802},\"inventory\":{\"3\":3144}},"
                    + "\"target\":{\"hp\":20,\"maxHp\":99,\"weaponId\":12006,\"animationId\":-1,"
                    + "\"distance\":-1},\"executedAction\":\"SPEC:\",\"actionLabel\":\"BIGHIT_SPEC@13\"}";

    @Test
    public void parsesAgentLineAndIgnoresExtraLabel() {
        ReplayTick tick = NDJSONFixtureLoader.parseLine(AGENT_LINE);
        assertEquals(13L, tick.state().tickCount());
        assertEquals(ActionExpectation.Kind.SPEC, tick.expected().kind());
        assertEquals(20, tick.state().target().orElseThrow().currentHp());
        assertEquals(55, tick.state().localPlayer().currentHp());
        assertFalse(tick.expected().raw().isBlank());
    }

    @Test
    public void compactEatAndAttackParse() {
        String eat = AGENT_LINE.replace("\"SPEC:\"", "\"EAT:\"").replace("BIGHIT_SPEC@13", "ARB_EAT_dh-axe@13");
        String attack = AGENT_LINE.replace("\"SPEC:\"", "\"ATTACK\"").replace("BIGHIT_SPEC@13", "PLAYER_ATK@13");
        assertEquals(ActionExpectation.Kind.EAT, NDJSONFixtureLoader.parseLine(eat).expected().kind());
        assertEquals(ActionExpectation.Kind.ATTACK, NDJSONFixtureLoader.parseLine(attack).expected().kind());
    }

    @Test
    public void discoveryIncludesShippedFixturesAndIsNotHardcodedToOneFile() {
        List<String> names = NDJSONFixtureLoader.fixtureResourceNames();
        assertTrue(names.contains("fixtures/sample_fight.ndjson"), names.toString());
        assertTrue(names.contains("fixtures/dh-combo-fixture.ndjson"), names.toString());
        assertTrue(names.contains("fixtures/survive-spec-fixture.ndjson"), names.toString());
        assertTrue(names.stream().allMatch(n -> n.endsWith(".ndjson")), names.toString());
    }

    @Test
    public void everyDiscoveredFixtureLoadsAndHasAssertableActions() {
        List<ReplayTick> ticks = NDJSONFixtureLoader.loadClasspathFixtures();
        long assertable = ticks.stream()
                .filter(t -> t.expected().kind() != ActionExpectation.Kind.OTHER)
                .count();
        assertTrue(assertable > 0, "no assertable ticks in " + NDJSONFixtureLoader.fixtureResourceNames());
    }
}
