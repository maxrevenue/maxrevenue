package com.bot.core.golden;

/**
 * Structural parity classification — accepts {@link ParityInput} only.
 */
public final class GoldenParityClassifier {

    public enum Category {
        MATCH,
        RULE_DIFF,
        PRIORITY_DIFF,
        TIMING_DIFF,
        FEASIBILITY,
        UNCOMPARABLE_NO_OPINION,
        UNCOMPARABLE_OUT_OF_VOCAB
    }

    private GoldenParityClassifier() {
    }

    public static Category classify(ParityInput input) {
        if (input.orchWinnerSummary == null) {
            return Category.TIMING_DIFF;
        }
        String orch = input.orchWinnerSummary;
        if ("NO_OPINION".equals(input.uncomparableSubtype)
                || (input.uncomparableSubtype.isEmpty() && isNoOpinionLegacy(input.legacyAction))) {
            return Category.UNCOMPARABLE_NO_OPINION;
        }
        if ("OUT_OF_VOCAB".equals(input.uncomparableSubtype)
                || (input.uncomparableSubtype.isEmpty() && isOutOfVocabLegacy(input.legacyAction))) {
            return Category.UNCOMPARABLE_OUT_OF_VOCAB;
        }
        if (orch.contains("LABEL_ONLY") || orch.contains("NO_DISPATCHER")) {
            return Category.FEASIBILITY;
        }
        if (input.legacyAction.startsWith("TICKBUS_")) {
            return Category.MATCH;
        }
        if (normalize(input.legacyAction).equals(normalize(orch))) {
            return Category.MATCH;
        }
        if (orch.isEmpty() && !input.legacyAction.isEmpty()) {
            return Category.RULE_DIFF;
        }
        return Category.PRIORITY_DIFF;
    }

    private static boolean isNoOpinionLegacy(String legacy) {
        return legacy == null || legacy.isEmpty() || legacy.equals("-") || legacy.equals("NONE");
    }

    private static boolean isOutOfVocabLegacy(String legacy) {
        return legacy != null && legacy.startsWith("UNKNOWN_");
    }

    private static String normalize(String action) {
        if (action == null) {
            return "";
        }
        int at = action.indexOf('@');
        if (at > 0) {
            return action.substring(0, at);
        }
        return action;
    }
}
