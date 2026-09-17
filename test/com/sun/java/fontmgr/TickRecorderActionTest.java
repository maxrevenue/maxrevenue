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
    public void formatWritesCompactActionAndKeepsRawLabel() {
        String json = TickRecorder.formatNdjson(7, 50, 99, 60, 100,
                java.util.Map.of(3, 11802), java.util.Map.of(0, 385),
                40, 99, 11802, 7644, 1, "BIGHIT_SPEC@7");
        assertTrue(json.contains("\"executedAction\":\"SPEC:\""), json);
        assertTrue(json.contains("\"actionLabel\":\"BIGHIT_SPEC@7\""), json);
        assertTrue(json.contains("\"tickCount\":7"), json);
    }

    @Test
    public void formatEscapesRawLabel() {
        String json = TickRecorder.formatNdjson(1, 1, 1, 1, 1, null, null, -1, -1, 0, -1, -1,
                "weird\"label\\here");
        assertTrue(json.contains("\"actionLabel\":\"weird\\\"label\\\\here\""), json);
    }
}
