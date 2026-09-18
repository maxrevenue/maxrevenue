package com.sun.java.fontmgr.tickbus;

import com.bot.core.orchestrator.LiveTickOrchestrator;
import com.sun.java.fontmgr.CombatScript;

public final class TickBusHooks {

    private static volatile TickBusIntegration integration;

    private TickBusHooks() {
    }

    /**
     * Runs the orchestrator on pre-legacy vitals. In shadow mode legacy combat still runs afterward.
     *
     * @return {@code true} when legacy combat tail should be skipped (tickbus owns dispatch).
     */
    public static boolean evaluateEarly(CombatScript script, int tick) {
        if (!LiveTickOrchestrator.ENABLED) {
            return false;
        }
        TickBusIntegration local = integration;
        if (local == null) {
            synchronized (TickBusHooks.class) {
                local = integration;
                if (local == null) {
                    local = new TickBusIntegration(script);
                    integration = local;
                }
            }
        }
        local.evaluateEarly(script, tick);
        return !LiveTickOrchestrator.SHADOW;
    }

    /** After legacy sequencing; pairs {@code lastAction} with the orchestrator orch line. */
    public static void recordShadowLegacy(CombatScript script, int tick) {
        if (!LiveTickOrchestrator.ENABLED || !LiveTickOrchestrator.SHADOW) {
            return;
        }
        TickBusIntegration local = integration;
        if (local != null) {
            local.recordLegacyTail(script, tick);
        }
    }
}
