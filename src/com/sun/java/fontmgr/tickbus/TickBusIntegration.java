package com.sun.java.fontmgr.tickbus;

import com.bot.core.bus.Advisor;
import com.bot.core.orchestrator.LiveTickOrchestrator;
import com.bot.core.orchestrator.LoggingReflectionDispatcher;
import com.bot.core.orchestrator.ReflectionDispatcher;
import com.bot.core.sidecar.AsyncSidecarAdvisor;
import com.bot.core.sidecar.SidecarTickMetrics;
import com.bot.core.telemetry.OffThreadNDJSONRecorder;
import com.sun.java.fontmgr.CombatScript;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires {@link LiveTickOrchestrator} for {@code -Droatz.tickbus=true}.
 *
 * <p>Acceptance gate: run with {@code -Droatz.tickbus.shadow=true} so orchestrator logs intent
 * dispatch while legacy combat still executes — NDJSON diff baseline before flipping default dispatch.
 */
public final class TickBusIntegration {

    private final LiveTickOrchestrator orchestrator;
    private final CombatTickStateAdapter stateAdapter;

    public TickBusIntegration(CombatScript script) {
        SidecarTickMetrics metrics = new SidecarTickMetrics();
        Advisor sidecar = new AsyncSidecarAdvisor(0, metrics);
        Advisor[] advisors = new Advisor[]{sidecar};
        ReflectionDispatcher dispatcher = LiveTickOrchestrator.SHADOW
                ? new LoggingReflectionDispatcher()
                : new CombatReflectionDispatcher(script);
        OffThreadNDJSONRecorder recorder = createRecorder();
        stateAdapter = new CombatTickStateAdapter(script);
        orchestrator = new LiveTickOrchestrator(advisors, dispatcher, recorder, metrics);
    }

    /**
     * @return {@code true} when legacy combat tail should be skipped (tickbus owns dispatch).
     */
    public boolean onTick(CombatScript script, int tick) {
        stateAdapter.setTick(tick);
        orchestrator.onTick(stateAdapter);
        return !LiveTickOrchestrator.SHADOW;
    }

    private static OffThreadNDJSONRecorder createRecorder() {
        String path = System.getProperty("roatz.tickbus.rec");
        if (path == null || path.isEmpty()) {
            return null;
        }
        Path output = Paths.get(path);
        return new OffThreadNDJSONRecorder(output);
    }
}
