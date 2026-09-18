package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Advisor;
import com.bot.core.bus.Channel;
import com.bot.core.bus.Intent;
import com.bot.core.bus.TickBus;
import com.bot.core.model.CombatTickState;
import com.bot.core.sidecar.SidecarTickMetrics;
import com.bot.core.telemetry.OffThreadNDJSONRecorder;

/**
 * Sole owner of tick combat arbitration when {@code -Droatz.tickbus=true}.
 */
public final class LiveTickOrchestrator {

    public static final boolean ENABLED = Boolean.getBoolean("roatz.tickbus");
    /** When true, orchestrator uses {@link LoggingReflectionDispatcher}; legacy still dispatches. */
    public static final boolean SHADOW = Boolean.getBoolean("roatz.tickbus.shadow");

    private static final int CH_OFFENSIVE = 0;
    private static final int CH_SUSTAIN = 1;
    private static final int CH_DEFENSIVE = 2;

    private final TickBus bus;
    private final Advisor[] advisors;
    private final SuppressionTable suppression;
    private final ReflectionDispatcher dispatcher;
    private final OffThreadNDJSONRecorder recorder;
    private final SidecarTickMetrics sidecarMetrics;

    private final Intent[] winners;
    private final EliminationReason[] eliminationReasons;

    public LiveTickOrchestrator(Advisor[] advisors,
                                  ReflectionDispatcher dispatcher,
                                  OffThreadNDJSONRecorder recorder,
                                  SidecarTickMetrics sidecarMetrics) {
        this.advisors = advisors;
        this.dispatcher = dispatcher;
        this.recorder = recorder;
        this.sidecarMetrics = sidecarMetrics;
        this.bus = new TickBus();
        this.suppression = new SuppressionTable();
        this.winners = new Intent[3];
        this.eliminationReasons = new EliminationReason[3];
    }

    public TickBus bus() {
        return bus;
    }

    public SuppressionTable suppression() {
        return suppression;
    }

    public void onTick(CombatTickState state) {
        bus.beginTick(state.tickIndex());
        bus.clear();
        if (sidecarMetrics != null) {
            sidecarMetrics.beginTick();
        }
        for (int i = 0; i < advisors.length; i++) {
            advisors[i].evaluate(state, bus);
        }
        winners[CH_OFFENSIVE] = resolveChannel(Channel.OFFENSIVE, state);
        winners[CH_SUSTAIN] = resolveChannel(Channel.SUSTAIN, state);
        winners[CH_DEFENSIVE] = resolveChannel(Channel.DEFENSIVE, state);
        eliminationReasons[0] = EliminationReason.NONE;
        eliminationReasons[1] = EliminationReason.NONE;
        eliminationReasons[2] = EliminationReason.NONE;
        ChannelRules.applyExclusiveRules(state, winners, eliminationReasons);

        // DECISION: DEFENSIVE → SUSTAIN → OFFENSIVE — prayer/gear before food before attack.
        dispatchWinner(winners[CH_DEFENSIVE], state, CH_DEFENSIVE);
        dispatchWinner(winners[CH_SUSTAIN], state, CH_SUSTAIN);
        dispatchWinner(winners[CH_OFFENSIVE], state, CH_OFFENSIVE);

        if (recorder != null) {
            recorder.enqueue(state, bus, winners, eliminationReasons, sidecarMetrics, dispatcher);
        }
    }

    private Intent resolveChannel(Channel channel, CombatTickState state) {
        Intent best = null;
        int bestRank = Integer.MIN_VALUE;
        long tick = state.tickIndex();
        for (int i = 0; i < bus.size(); i++) {
            Intent intent = bus.get(i);
            if (!intent.isValidAt(tick)) {
                continue;
            }
            if (!channel.contains(intent.kind())) {
                continue;
            }
            if (suppression.isSuppressed(intent.kind(), tick)) {
                continue;
            }
            int r = intent.rank();
            if (r > bestRank) {
                bestRank = r;
                best = intent;
            }
        }
        return best;
    }

    private void dispatchWinner(Intent intent, CombatTickState state, int channelIndex) {
        if (intent == null) {
            return;
        }
        dispatcher.dispatch(intent, state, channelIndex);
        applySuppressionLeases(intent, state);
    }

    public ReflectionDispatcher dispatcher() {
        return dispatcher;
    }

    private void applySuppressionLeases(Intent intent, CombatTickState state) {
        long tick = state.tickIndex();
        ActionKind kind = intent.kind();
        if (kind == ActionKind.EAT || kind == ActionKind.SIP) {
            suppression.lease(ActionKind.EAT, tick, 3);
            suppression.lease(ActionKind.SIP, tick, 3);
        } else if (kind == ActionKind.EQUIP) {
            suppression.lease(ActionKind.EQUIP, tick, 1);
        } else if (kind == ActionKind.PRAYER) {
            suppression.lease(ActionKind.PRAYER, tick, 1);
        } else if (kind == ActionKind.SPECIAL) {
            suppression.leaseUntil(ActionKind.SPECIAL, state.specAvailableFromTick());
        }
    }
}
