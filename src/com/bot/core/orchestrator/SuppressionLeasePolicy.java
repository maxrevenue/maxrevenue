package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;

/**
 * Lease durations and early-release rules (data table).
 */
public final class SuppressionLeasePolicy {

    public static final int EAT_DURATION = 3;
    public static final int EQUIP_DURATION = 1;
    public static final int PRAYER_DURATION = 1;

    private SuppressionLeasePolicy() {
    }

    public static int defaultDuration(ActionKind kind) {
        if (kind == ActionKind.EAT || kind == ActionKind.SIP) {
            return EAT_DURATION;
        }
        if (kind == ActionKind.EQUIP) {
            return EQUIP_DURATION;
        }
        if (kind == ActionKind.PRAYER) {
            return PRAYER_DURATION;
        }
        return 0;
    }
}
