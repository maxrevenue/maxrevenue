package com.bot.overlay;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.orchestrator.SuppressionTable;
import com.bot.core.orchestrator.TickResolutionSnapshot;
import com.bot.core.sidecar.SidecarTickMetrics;
import com.bot.core.telemetry.TickDispatchState;

/**
 * Tick-thread writer / paint-thread reader handoff (double-buffered, volatile publish).
 *
 * <p>Fill target buffer completely, then {@code visible = target}. Never mutate a buffer after publish.
 * Alternates A/B each tick; one-frame tear possible if paint straddles flip — debug HUD only.
 */
public final class OverlayPublisher {

    private static final String[] CHANNEL_SHORT = {"OFF", "SUS", "DEF"};
    private static final OverlayState[] BUFFERS = new OverlayState[]{new OverlayState(), new OverlayState()};
    private static volatile OverlayState visible;
    private static int writeIndex;
    private static long publishSequence;

    private OverlayPublisher() {
    }

    public static OverlayState current() {
        return visible;
    }

    public static void publish(CombatTickState state,
                               TickResolutionSnapshot resolution,
                               SuppressionTable suppression,
                               SidecarTickMetrics sidecarMetrics) {
        if (!OverlayConfig.enabled() || resolution == null) {
            return;
        }
        Intent[] winners = resolution.winners;
        EliminationReason[] eliminationReasons = resolution.eliminationReasons;
        int[] dispatchStateOrdinals = resolution.dispatchStateOrdinals;

        OverlayState target = BUFFERS[writeIndex];
        writeIndex = 1 - writeIndex;
        target.reset();

        long tick = state.tickIndex();
        target.tickIndex = tick;
        target.publishSequence = ++publishSequence;
        target.hp = state.localHp();
        target.specEnergy = state.specEnergyPercent();
        if (sidecarMetrics != null) {
            target.sidecarHealthy = sidecarMetrics.sidecarHealthy();
            target.sidecarAckLag = sidecarMetrics.sidecarAckLag();
            target.masklessIntents = sidecarMetrics.masklessIntents();
        }
        if (suppression != null) {
            target.attackLeaseTicksRemaining = ticksRemaining(suppression.deadlineFor(ActionKind.ATTACK), tick);
            target.eatLeaseTicksRemaining = ticksRemaining(suppression.deadlineFor(ActionKind.EAT), tick);
        }

        for (int ch = 0; ch < 3; ch++) {
            ChannelWinner slot = target.winners[ch];
            slot.channel = ch;
            Intent winner = winners != null && ch < winners.length ? winners[ch] : null;
            EliminationReason drop = eliminationReasons != null && ch < eliminationReasons.length
                    ? eliminationReasons[ch] : EliminationReason.NONE;
            if (drop != EliminationReason.NONE) {
                slot.dropReasonOrdinal = drop.ordinal();
            }
            if (winner != null) {
                slot.active = true;
                slot.kindOrdinal = winner.kind().ordinal();
                slot.npcIndex = winner.npcIndex();
                slot.itemId = winner.itemId();
                slot.bornTick = winner.bornTick();
                slot.sidecarAgeStale = winner.bornTick() != tick;
            }
            if (dispatchStateOrdinals != null && ch < dispatchStateOrdinals.length) {
                slot.dispatchStateOrdinal = dispatchStateOrdinals[ch];
            }
            target.channelLines[ch] = formatChannelLine(ch, slot, drop);
        }

        target.headerLine = "TB tick=" + tick + " seq=" + target.publishSequence
                + " hp=" + target.hp + " spec=" + target.specEnergy;
        target.sidecarLine = "SC:" + (target.sidecarHealthy ? "OK" : "DOWN")
                + " lag=" + target.sidecarAckLag + " maskless=" + target.masklessIntents;
        if (target.eatLeaseTicksRemaining > 0 || target.attackLeaseTicksRemaining > 0) {
            target.leaseLine = "lease eat=" + target.eatLeaseTicksRemaining
                    + " atk=" + target.attackLeaseTicksRemaining;
        } else {
            target.leaseLine = "";
        }

        visible = target;
    }

    private static String formatChannelLine(int ch, ChannelWinner slot, EliminationReason drop) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(CHANNEL_SHORT[ch]).append(' ');
        if (slot.active && slot.kindOrdinal >= 0) {
            sb.append(ActionKind.values()[slot.kindOrdinal].name());
            if (slot.npcIndex >= 0) {
                sb.append(" npc=").append(slot.npcIndex);
            }
            TickDispatchState ds = TickDispatchState.values()[Math.min(slot.dispatchStateOrdinal,
                    TickDispatchState.values().length - 1)];
            sb.append(' ').append(ds.name());
            if (slot.sidecarAgeStale) {
                sb.append(" \u2020");
            }
        } else {
            sb.append('-');
        }
        if (drop != EliminationReason.NONE) {
            sb.append(" drop=").append(drop.name());
        }
        return sb.toString();
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
