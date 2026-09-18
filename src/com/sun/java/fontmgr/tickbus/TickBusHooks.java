package com.sun.java.fontmgr.tickbus;

import com.bot.core.orchestrator.LiveTickOrchestrator;
import com.sun.java.fontmgr.CombatScript;

public final class TickBusHooks {

    private static volatile TickBusIntegration integration;

    private TickBusHooks() {
    }

    /**
     * @return {@code true} when caller should skip legacy combat dispatch for this tick.
     */
    public static boolean tryOnTick(CombatScript script, int tick) {
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
        return local.onTick(script, tick);
    }
}
