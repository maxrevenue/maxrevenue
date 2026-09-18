package com.bot.core.telemetry;

import com.bot.core.bus.ActionKind;
import com.bot.core.orchestrator.EliminationReason;

/**
 * Mutable ring slot filled on the tick thread (primitives only).
 */
public final class TickRecord {

    public static final int SCHEMA_V2 = 2;
    public static final int MAX_BUS_INTENTS = 32;

    public int schemaVersion;
    public long tickIndex;
    public int busSize;
    public int droppedPublishes;
    public int maxRankDropped;
    public int masklessIntents;
    public long sidecarAckLag;
    public boolean sidecarHealthy;
    public int sidecarWireFrameRejects;
    public int recordsDropped;

    /** Replay projection — do not re-derive from client during replay. */
    public int stateFingerprint;
    public int localHp;
    public int specEnergyPercent;
    public long specAvailableFromTick;

    public final ActionKind[] winnerKind = new ActionKind[3];
    public final int[] winnerRank = new int[3];
    public final int[] winnerAdvisor = new int[3];
    public final EliminationReason[] channelDropReason = new EliminationReason[3];
    public final int[] dispatchStateOrdinal = new int[3];

    public int busIntentCount;
    public final byte[] intentKindOrd = new byte[MAX_BUS_INTENTS];
    public final byte[] intentPriorityOrd = new byte[MAX_BUS_INTENTS];
    public final byte[] intentAdvisorOrd = new byte[MAX_BUS_INTENTS];
    public final int[] intentRank = new int[MAX_BUS_INTENTS];
    public final int[] intentItemId = new int[MAX_BUS_INTENTS];
    public final int[] intentNpcIndex = new int[MAX_BUS_INTENTS];
    public final int[] intentSlotIndex = new int[MAX_BUS_INTENTS];
    public final long[] intentBornTick = new long[MAX_BUS_INTENTS];
    public final int[] intentTtlTicks = new int[MAX_BUS_INTENTS];

    /** {@code 0} = orchestrator snapshot, {@code 1} = legacy tail (shadow diff). */
    public int recordKind;
    public String legacyAction;

    public void reset() {
        schemaVersion = SCHEMA_V2;
        tickIndex = 0L;
        busSize = 0;
        droppedPublishes = 0;
        maxRankDropped = 0;
        masklessIntents = 0;
        sidecarAckLag = 0L;
        sidecarHealthy = true;
        sidecarWireFrameRejects = 0;
        recordsDropped = 0;
        stateFingerprint = 0;
        localHp = -1;
        specEnergyPercent = -1;
        specAvailableFromTick = 0L;
        recordKind = 0;
        legacyAction = null;
        busIntentCount = 0;
        for (int i = 0; i < 3; i++) {
            winnerKind[i] = null;
            winnerRank[i] = 0;
            winnerAdvisor[i] = -1;
            channelDropReason[i] = EliminationReason.NONE;
            dispatchStateOrdinal[i] = TickDispatchState.NO_DISPATCHER.ordinal();
        }
    }
}
