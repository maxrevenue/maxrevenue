package com.bot.overlay;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.orchestrator.SuppressionTable;
import com.bot.core.sidecar.SidecarTickMetrics;

/**
 * Tick-thread writer / paint-thread reader handoff (double-buffered, volatile publish).
 */
public final class OverlayPublisher {

    private static final OverlayState[] BUFFERS = new OverlayState[]{new OverlayState(), new OverlayState()};
    private static volatile OverlayState visible;
    private static int writeIndex;

    private OverlayPublisher() {
    }

    public static OverlayState current() {
        return visible;
    }

    public static void publish(CombatTickState state,
                               Intent[] winners,
                               int[] dispatchStateOrdinals,
                               EliminationReason[] eliminationReasons,
                               SuppressionTable suppression,
                               SidecarTickMetrics sidecarMetrics) {
        if (!OverlayConfig.enabled()) {
            return;
        }
        OverlayState target = BUFFERS[writeIndex];
        writeIndex = 1 - writeIndex;
        target.reset();
        target.tickIndex = state.tickIndex();
        target.hp = state.localHp();
        target.specEnergy = state.specEnergyPercent();
        if (sidecarMetrics != null) {
            target.sidecarHealthy = sidecarMetrics.sidecarHealthy();
            target.sidecarAckLag = sidecarMetrics.sidecarAckLag();
            target.masklessIntents = sidecarMetrics.masklessIntents();
        }
        if (suppression != null) {
            long tick = state.tickIndex();
            target.attackLeaseTicksRemaining = ticksRemaining(suppression.deadlineFor(ActionKind.ATTACK), tick);
            target.eatLeaseTicksRemaining = ticksRemaining(suppression.deadlineFor(ActionKind.EAT), tick);
        }
        for (int ch = 0; ch < 3; ch++) {
            ChannelWinner slot = target.winners[ch];
            slot.channel = ch;
            Intent winner = winners != null && ch < winners.length ? winners[ch] : null;
            if (winner != null) {
                slot.active = true;
                slot.kindOrdinal = winner.kind().ordinal();
                slot.npcIndex = winner.npcIndex();
                slot.itemId = winner.itemId();
            }
            if (dispatchStateOrdinals != null && ch < dispatchStateOrdinals.length) {
                slot.dispatchStateOrdinal = dispatchStateOrdinals[ch];
            }
            if (eliminationReasons != null && ch < eliminationReasons.length
                    && eliminationReasons[ch] != EliminationReason.NONE) {
                slot.dropReasonOrdinal = eliminationReasons[ch].ordinal();
            }
        }
        visible = target;
    }

    private static int ticksRemaining(long deadlineTick, long currentTick) {
        if (deadlineTick <= currentTick) {
            return 0;
        }
        long delta = deadlineTick - currentTick;
        if (delta > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) delta;
    }
}
