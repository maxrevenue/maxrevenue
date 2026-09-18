package com.sun.java.fontmgr.tickbus;

import com.bot.core.orchestrator.LiveTickOrchestrator;
import com.bot.core.telemetry.OrchestratorSkipReason;
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
        ensureIntegration(script).evaluateEarly(script, tick);
        return !LiveTickOrchestrator.SHADOW;
    }

    public static void onCombatTickStart(CombatScript script) {
        if (!LiveTickOrchestrator.ENABLED) {
            return;
        }
        TickBusIntegration local = ensureIntegration(script);
        local.onCombatTickStart();
    }

    public static void markOrchestratorSkip(CombatScript script, OrchestratorSkipReason reason) {
        if (!LiveTickOrchestrator.ENABLED || reason == null || reason == OrchestratorSkipReason.NONE) {
            return;
        }
        ensureIntegration(script).markOrchestratorSkip(reason);
    }

    /** Stub orch when {@code evaluateEarly} did not run; call before {@code recordShadowLegacy}. */
    public static void ensureOrchPairing(CombatScript script, int tick) {
        if (!LiveTickOrchestrator.ENABLED) {
            return;
        }
        ensureIntegration(script).ensureOrchPairing(tick);
    }

    private static TickBusIntegration ensureIntegration(CombatScript script) {
        TickBusIntegration local = integration;
        if (local != null) {
            return local;
        }
        synchronized (TickBusHooks.class) {
            local = integration;
            if (local == null) {
                local = new TickBusIntegration(script);
                integration = local;
            }
            return local;
        }
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
