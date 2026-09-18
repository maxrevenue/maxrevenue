package com.bot.core.bus;

/**
 * Interned byline ids — tick thread stores id only; writer thread maps to label string.
 */
public final class IntentBylines {

    public static final int NONE = 0;
    public static final int SUSTAIN_EAT_LEGACY = 1;
    public static final int COMBAT_SPEC_LEGACY = 2;
    public static final int COMBAT_ATTACK_LEGACY = 3;
    public static final int SIDECAR_WIRE = 4;

    private static final String[] LABELS = {
            "",
            "sustain.eat.legacy",
            "combat.spec.legacy",
            "combat.attack.legacy",
            "sidecar.wire"
    };

    private IntentBylines() {
    }

    public static String label(int bylineId) {
        if (bylineId <= 0 || bylineId >= LABELS.length) {
            return "";
        }
        return LABELS[bylineId];
    }
}
