package com.sun.java.fontmgr.tickbus;

import com.bot.core.bus.Advisor;
import com.bot.core.orchestrator.LiveTickOrchestrator;
import com.bot.core.sidecar.AsyncSidecarAdvisor;
import com.bot.core.sidecar.SidecarTickMetrics;
import com.bot.core.telemetry.OffThreadNDJSONRecorder;
import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.tickbus.advisor.CombatAdvisor;
import com.sun.java.fontmgr.tickbus.advisor.SustainAdvisor;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Wires {@link LiveTickOrchestrator} for {@code -Droatz.tickbus=true}.
 *
 * <p>Hook order (see docs/tickbus-wire-contract.md): orchestrator evaluates immediately after
 * {@code refreshPvpVitals()}, before auto-prayer and the legacy monolith — shadow baseline
 * reflects pre-legacy combat vitals.
 */
public final class TickBusIntegration {

    private final LiveTickOrchestrator orchestrator;
    private final CombatTickStateAdapter stateAdapter;
    private final OffThreadNDJSONRecorder recorder;
    private final int[] dispatchStateOrdinals;

    public TickBusIntegration(CombatScript script) {
        SidecarTickMetrics metrics = new SidecarTickMetrics();
        dispatchStateOrdinals = FeasibilityRecordingDispatcher.newDispatchStateBuffer();
        boolean execute = !LiveTickOrchestrator.SHADOW;
        FeasibilityRecordingDispatcher dispatcher =
                new FeasibilityRecordingDispatcher(script, execute, dispatchStateOrdinals);
        Advisor sidecar = new AsyncSidecarAdvisor(0, metrics);
        Advisor sustain = new SustainAdvisor(script, 1);
        Advisor combat = new CombatAdvisor(script, 2);
        Advisor[] advisors = new Advisor[]{sidecar, sustain, combat};
        recorder = createRecorder();
        stateAdapter = new CombatTickStateAdapter(script);
        orchestrator = new LiveTickOrchestrator(advisors, dispatcher, recorder, metrics);
    }

    public void evaluateEarly(CombatScript script, int tick) {
        stateAdapter.setTick(tick);
        orchestrator.onTick(stateAdapter);
    }

    public void recordLegacyTail(CombatScript script, int tick) {
        if (recorder != null) {
            recorder.enqueueLegacyTail(tick, script.lastAction);
        }
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
