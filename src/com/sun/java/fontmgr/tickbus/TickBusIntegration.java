package com.sun.java.fontmgr.tickbus;

import com.bot.core.bus.Advisor;
import com.bot.core.orchestrator.LiveTickOrchestrator;
import com.bot.core.sidecar.AsyncSidecarAdvisor;
import com.bot.core.sidecar.SidecarArrivalLagHistogram;
import com.bot.core.sidecar.SidecarTickMetrics;
import com.bot.core.telemetry.OrchestratorSkipReason;
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
    private final SidecarArrivalLagHistogram arrivalLagHistogram;
    private final SidecarTickMetrics sidecarMetrics;

    private boolean orchRecordedThisTick;
    private OrchestratorSkipReason pendingSkip = OrchestratorSkipReason.NONE;

    public TickBusIntegration(CombatScript script) {
        sidecarMetrics = new SidecarTickMetrics();
        arrivalLagHistogram = new SidecarArrivalLagHistogram();
        dispatchStateOrdinals = FeasibilityRecordingDispatcher.newDispatchStateBuffer();
        boolean execute = !LiveTickOrchestrator.SHADOW;
        FeasibilityRecordingDispatcher dispatcher =
                new FeasibilityRecordingDispatcher(script, execute, dispatchStateOrdinals);
        AsyncSidecarAdvisor sidecar = new AsyncSidecarAdvisor(0, sidecarMetrics);
        sidecar.setArrivalLagHistogram(arrivalLagHistogram);
        Advisor sustain = new SustainAdvisor(script, 1);
        Advisor combat = new CombatAdvisor(script, 2);
        Advisor[] advisors = new Advisor[]{sidecar, sustain, combat};
        recorder = createRecorder();
        if (recorder != null) {
            recorder.setArrivalLagHistogram(arrivalLagHistogram);
        }
        stateAdapter = new CombatTickStateAdapter(script);
        orchestrator = new LiveTickOrchestrator(advisors, dispatcher, recorder, sidecarMetrics);
    }

    public void onCombatTickStart() {
        orchRecordedThisTick = false;
        pendingSkip = OrchestratorSkipReason.NONE;
    }

    public void markOrchestratorSkip(OrchestratorSkipReason reason) {
        if (reason != null && reason != OrchestratorSkipReason.NONE) {
            pendingSkip = reason;
        }
    }

    /**
     * Shadow pairing: every legacy line must have an orch line — stub when evaluateEarly did not run.
     */
    public void ensureOrchPairing(long tickIndex) {
        if (!LiveTickOrchestrator.ENABLED || !LiveTickOrchestrator.SHADOW) {
            return;
        }
        if (orchRecordedThisTick || recorder == null) {
            return;
        }
        OrchestratorSkipReason reason = pendingSkip != OrchestratorSkipReason.NONE
                ? pendingSkip : OrchestratorSkipReason.DISABLED;
        recorder.enqueueSkippedOrch(tickIndex, reason, sidecarMetrics);
        orchRecordedThisTick = true;
    }

    public void evaluateEarly(CombatScript script, int tick) {
        stateAdapter.setTick(tick);
        orchestrator.onTick(stateAdapter);
        orchRecordedThisTick = true;
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
