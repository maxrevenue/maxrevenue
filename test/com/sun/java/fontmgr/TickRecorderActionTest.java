package com.sun.java.fontmgr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the agent action label → EchoForge vocabulary mapping.
 *
 * <p>Would fail if a completed eat/spec/attack mapped to {@code ""} (recordings
 * would silently assert nothing) or if a failed attempt mapped to an action
 * (the golden replay would assert an intent that never executed).
 */
public class TickRecorderActionTest {

    private static String c(String label) {
        return TickRecorder.compactExecutedAction(label);
    }

    @Test
    public void completedEatsMapToEat() {
        assertEquals("EAT:", c("ARB_EAT_dh-axe@123"));
        assertEquals("EAT:", c("DH_EAT1@10"));
        assertEquals("EAT:", c("DH_EAT2@10"));
        assertEquals("EAT:", c("DH_EAT3@10"));
        assertEquals("EAT:", c("DH_SPEC_EAT@10"));
        assertEquals("EAT:", c("PK_SINGLE@10"));
        assertEquals("EAT:", c("PK_DOUBLE@10"));
        assertEquals("EAT:", c("PK_TRIPLE@10"));
        assertEquals("EAT:", c("PK_MARLIN_BREW@10"));
        assertEquals("EAT:", c("PK_SINGLE_FALLBACK@10"));
        assertEquals("EAT:", c("NH_BREW1@10"));
        assertEquals("EAT:", c("NH_MARLIN_BREW@10"));
        assertEquals("EAT:", c("PK_EAT3_BREW@10"));
    }

    @Test
    public void completedSpecsMapToSpec() {
        assertEquals("SPEC:", c("KO_SPEC@10"));
        assertEquals("SPEC:", c("BIGHIT_SPEC@10"));
        assertEquals("SPEC:", c("HARDHIT_SPEC@10"));
        assertEquals("SPEC:", c("COUNTER_SPEC@10"));
        assertEquals("SPEC:", c("NH_SPEC@10"));
        assertEquals("SPEC:", c("VLS_SPEC@10"));
        assertEquals("SPEC:", c("VOIDWAKER_SPEC@10"));
        assertEquals("SPEC:", c("VOIDWAKER_COMBO@10"));
        assertEquals("SPEC:", c("DMACE_SPEC@10"));
        assertEquals("SPEC:", c("DBOW_SPEC@10"));
        assertEquals("SPEC:", c("AXE_SPEC_DONE@10"));
        assertEquals("SPEC:", c("GMAUL_FORCE@10"));
        assertEquals("SPEC:", c("GMAUL_1T@10"));
        assertEquals("SPEC:", c("DH_GMAUL_FOLLOW@10"));
        assertEquals("SPEC:", c("SW_WACK@10"));
        assertEquals("SPEC:", c("Q_CLAWS+GMAUL"));
    }

    @Test
    public void attacksMapToAttack() {
        assertEquals("ATTACK", c("PLAYER_ATK@10"));
        assertEquals("ATTACK", c("NPC_ATK@10"));
        assertEquals("ATTACK", c("RE_ATTACK@10"));
        assertEquals("ATTACK", c("DH_MANUAL_AXE@10"));
    }

    @Test
    public void failedAttemptsAndObservationTicksMapToEmpty() {
        assertEquals("", c("DH_EAT_NOFOOD@10"));
        assertEquals("", c("DH_SPEC_EAT_NOFOOD@10"));
        assertEquals("", c("NH_EAT1_MISS@10"));
        assertEquals("", c("PK_EAT3_MISS@10"));
        assertEquals("", c("GMAUL_NOENERGY@10"));
        assertEquals("", c("GMAUL_NO_WIELD@10"));
        assertEquals("", c("GMAUL_SKIP_KO_10"));
        assertEquals("", c("DH_GMAUL_HOLD@10"));
        assertEquals("", c("DMACE_STALL@10"));
        assertEquals("", c("DMACE_NOSPLAT@10"));
        assertEquals("", c("DBOW_NOENERGY@10"));
        assertEquals("", c("NO_SPEC_WEP@10"));
        assertEquals("", c("SPEC_ERR@10"));
        assertEquals("", c("LC_FAIL@10"));
        assertEquals("", c("PROTECT_ITEM@10"));
        assertEquals("", c("VENG@10"));
        assertEquals("", c("ICE_CAST@10"));
        assertEquals("", c("RESTORE@10"));
        assertEquals("", c("none"));
        assertEquals("", c(""));
        assertEquals("", c(null));
    }

    @Test
    public void equipAndPrayerTokensPassThroughWithDetail() {
        assertEquals("EQUIP:11802", c("EQUIP:11802"));
        assertEquals("EQUIP:11802", c("equip:11802"));
        assertEquals("PRAYER:PROTECT_FROM_MAGIC", c("PRAYER:PROTECT_FROM_MAGIC"));
        assertEquals("PRAYER:piety", c("PRAYER:piety"));
        // A bridge token is never mistaken for a failed attempt.
        assertEquals("EQUIP:12954", c("EQUIP:12954"));
    }

    @Test
    public void formatCarriesEquipPrayerAndDistance() {
        String equip = TickRecorder.formatNdjson(9, 99, 99, 50, 100,
                null, null, 40, 99, 0, -1, 2,
                "MELEE", java.util.Collections.<String>emptyList(), "EQUIP:11802", "SWAP_MELEE@9");
        assertTrue(equip.contains("\"executedAction\":\"EQUIP:11802\""), equip);
        assertTrue(equip.contains("\"distance\":2"), equip);
        String pray = TickRecorder.formatNdjson(9, 99, 99, 50, 100,
                null, null, 40, 99, 0, -1, -1,
                "UNKNOWN", java.util.List.of("PIETY"), "PRAYER:PIETY", "PIETY@9");
        assertTrue(pray.contains("\"executedAction\":\"PRAYER:PIETY\""), pray);
    }

    @Test
    public void formatEmitsPrayersAndAttackStyleForTheEchoForgeSchema() {
        String json = TickRecorder.formatNdjson(31, 90, 99, 68, 0,
                java.util.Map.of(3, 11802), java.util.Map.of(1, 385),
                70, 99, 861, -1, 5,
                "RANGED", java.util.List.of("PROTECT_FROM_MISSILES", "RIGOUR"),
                "PRAYER:PROTECT_FROM_MISSILES", "PRAYER:PROTECT_FROM_MISSILES");
        assertTrue(json.contains("\"prayers\":[\"PROTECT_FROM_MISSILES\",\"RIGOUR\"]"), json);
        assertTrue(json.contains("\"attackStyle\":\"RANGED\""), json);
        assertTrue(json.contains("\"distance\":5"), json);
        // prayers live inside player, attackStyle inside target
        assertTrue(json.indexOf("\"prayers\"") < json.indexOf("\"target\""), json);
        assertTrue(json.indexOf("\"attackStyle\"") > json.indexOf("\"target\""), json);
    }

    @Test
    public void formatEmitsEmptyArrayAndUnknownWhenAbsent() {
        String json = TickRecorder.formatNdjson(1, 1, 1, 1, 1, null, null, -1, -1, 0, -1, -1,
                null, null, "ATTACK", "PLAYER_ATK@1");
        assertTrue(json.contains("\"prayers\":[]"), json);
        assertTrue(json.contains("\"attackStyle\":\"UNKNOWN\""), json);
    }

    @Test
    public void configTogglesAreNotEatActions() {
        // "AUTO_EAT_OFF" is a switch, not an eat; it was scoring as a false
        // EAT: expectation on every repeated row of a real capture.
        assertEquals("", c("AUTO_EAT_OFF"));
        assertEquals("", c("AUTO_EAT_ON"));
        assertEquals("", c("NHV2_ON"));
        assertEquals("", c("AUTO_SPEC_OFF"));
        // A real eat is unaffected.
        assertEquals("EAT:", c("NH_BREW1@698"));
    }

    @Test
    public void recordedActionIsNotSticky() throws Exception {
        // lastAction keeps its value until the next action overwrites it, so a
        // naive read stamped 84 later rows with one eat's label. The agent only
        // reports a label for the tick it happened on.
        String prev = System.getProperty("roatz.rec");
        System.clearProperty("roatz.rec");
        try {
            CombatScript s = new CombatScript(null, Object.class, null);

            s.lastAction = "NH_MARLIN_BREW@730";
            assertEquals("NH_MARLIN_BREW@730", s.recordedAction(730));
            assertEquals("", s.recordedAction(731), "stale label must not mark later ticks");
            assertEquals("", s.recordedAction(875));

            s.lastAction = "NH_BREW1@876";
            assertEquals("NH_BREW1@876", s.recordedAction(876));

            // No tick stamp: reported once, when it changes.
            s.lastAction = "SW_WACK";
            assertEquals("SW_WACK", s.recordedAction(900));
            assertEquals("", s.recordedAction(901));
        } finally {
            if (prev != null) System.setProperty("roatz.rec", prev);
        }
    }

    @Test
    public void normalizeStyleCollapsesToTheFourEngineValues() {
        assertEquals("MELEE", TickRecorder.normalizeStyle("melee"));
        assertEquals("RANGED", TickRecorder.normalizeStyle(" RANGED "));
        assertEquals("MAGIC", TickRecorder.normalizeStyle("Magic"));
        assertEquals("UNKNOWN", TickRecorder.normalizeStyle(null));
        assertEquals("UNKNOWN", TickRecorder.normalizeStyle(""));
        assertEquals("UNKNOWN", TickRecorder.normalizeStyle("PIRATE"));
    }

    @Test
    public void startupNoteAndFirstRowLandInTheFile() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("echoforge-rec", ".ndjson");
        String prev = System.getProperty("roatz.rec");
        try {
            System.setProperty("roatz.rec", tmp.toString());
            TickRecorder.setStartupNote("tick engine ok");
            TickRecorder rec = TickRecorder.fromProperty();
            assertTrue(rec.isEnabled(), "explicit path must start the recorder");
            rec.recordTick(1, 99, 99, 50, 0, null, null, -1, -1, 0, -1, -1,
                    "MELEE", null, "ATTACK", "PLAYER_ATK@1");
            rec.flushAndClose();

            String body = new String(java.nio.file.Files.readAllBytes(tmp),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("# tick engine ok"),
                    "health note must be the first line so a row-less file is diagnosable: " + body);
            assertTrue(body.contains("\"tickCount\":1"), body);
            assertTrue(body.indexOf("# tick engine ok") < body.indexOf("\"tickCount\""),
                    "note must precede the first tick row");
        } finally {
            if (prev == null) System.clearProperty("roatz.rec");
            else System.setProperty("roatz.rec", prev);
            TickRecorder.setStartupNote("");
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void formatWritesCompactActionAndKeepsRawLabel() {
        String json = TickRecorder.formatNdjson(7, 50, 99, 60, 100,
                java.util.Map.of(3, 11802), java.util.Map.of(0, 385),
                40, 99, 11802, 7644, 1,
                "MELEE", java.util.List.of("PIETY"), "BIGHIT_SPEC@7", "BIGHIT_SPEC@7");
        assertTrue(json.contains("\"executedAction\":\"SPEC:\""), json);
        assertTrue(json.contains("\"actionLabel\":\"BIGHIT_SPEC@7\""), json);
        assertTrue(json.contains("\"tickCount\":7"), json);
    }

    @Test
    public void formatEscapesRawLabel() {
        String json = TickRecorder.formatNdjson(1, 1, 1, 1, 1, null, null, -1, -1, 0, -1, -1,
                "UNKNOWN", null, "weird\"label\\here", "weird\"label\\here");
        assertTrue(json.contains("\"actionLabel\":\"weird\\\"label\\\\here\""), json);
    }

    @Test
    public void rawLabelSurvivesAnEmptyExecutedAction() {
        // Regression: the sticky suppression filtered executedAction and, because
        // both fields came from one argument, erased actionLabel too — a whole
        // capture ended up with no provenance at all (664 rows of "").
        String json = TickRecorder.formatNdjson(700, 90, 99, 60, 0, null, null, -1, -1, 0, -1, -1,
                "UNKNOWN", null, "", "LC_SKIP_NO_STAFF@700");
        assertTrue(json.contains("\"executedAction\":\"\""), json);
        assertTrue(json.contains("\"actionLabel\":\"LC_SKIP_NO_STAFF@700\""), json);
    }
}
