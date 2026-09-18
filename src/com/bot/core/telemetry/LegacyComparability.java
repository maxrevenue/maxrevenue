package com.bot.core.telemetry;

/**
 * Legacy {@code lastAction} comparability for shadow NDJSON (frozen at record time).
 */
public final class LegacyComparability {

    public enum Subtype {
        /** Legacy line participates in MATCH / *_DIFF buckets. */
        COMPARABLE,
        /** Empty / no monolith opinion this tick. */
        NO_OPINION,
        /** Monolith acted outside captured vocabulary. */
        OUT_OF_VOCAB
    }

    private LegacyComparability() {
    }

    public static Subtype classify(String legacyAction) {
        if (legacyAction == null || legacyAction.isEmpty()
                || legacyAction.equals("-") || legacyAction.equals("NONE")) {
            return Subtype.NO_OPINION;
        }
        if (legacyAction.startsWith("TICKBUS_")) {
            return Subtype.COMPARABLE;
        }
        if (legacyAction.startsWith("UNKNOWN_")) {
            return Subtype.OUT_OF_VOCAB;
        }
        if (isCapturedVocab(legacyAction)) {
            return Subtype.COMPARABLE;
        }
        return Subtype.OUT_OF_VOCAB;
    }

    /** Tokens observed in {@code CombatScript.lastAction} shadow capture (expand as vocab grows). */
    private static boolean isCapturedVocab(String action) {
        if (action.indexOf('@') > 0) {
            return true;
        }
        String head = action;
        int space = action.indexOf(' ');
        if (space > 0) {
            head = action.substring(0, space);
        }
        return head.equals("EAT") || head.equals("SIP") || head.equals("ATTACK") || head.equals("SPECIAL")
                || head.equals("PRAYER") || head.equals("EQUIP") || head.equals("IDLE")
                || head.startsWith("SPEC") || head.startsWith("PRAY");
    }

    public static String subtypeJson(Subtype subtype) {
        if (subtype == null || subtype == Subtype.COMPARABLE) {
            return null;
        }
        return subtype.name();
    }
}
