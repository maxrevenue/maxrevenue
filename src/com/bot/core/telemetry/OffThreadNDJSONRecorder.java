package com.bot.core.telemetry;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.bus.IntentBylines;
import com.bot.core.bus.TickBus;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.orchestrator.DispatchStateSource;
import com.bot.core.orchestrator.ReflectionDispatcher;
import com.bot.core.sidecar.SidecarArrivalLagHistogram;
import com.bot.core.sidecar.SidecarTickMetrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tick thread enqueues {@link TickRecord} snapshots; a daemon writer serializes NDJSON.
 *
 * <p>Replay must read, never recompute. Any state an advisor or rule consults must appear in
 * {@code replayProjection}.
 */
public final class OffThreadNDJSONRecorder {

    private static final int RING_SIZE = 64;
    private static final int QUEUE_CAPACITY = 256;

    private final TickRecord[] ring;
    private int ringCursor;
    private final ArrayBlockingQueue<TickRecord> queue;
    private final AtomicBoolean running;
    private final Thread writerThread;
    private final Path outputPath;
    private final SessionOutcomeTracker sessionOutcomes;
    private SidecarArrivalLagHistogram arrivalLagHistogram;
    public int recordsDropped;

    public OffThreadNDJSONRecorder(Path outputPath) {
        this.outputPath = outputPath;
        ring = new TickRecord[RING_SIZE];
        for (int i = 0; i < RING_SIZE; i++) {
            ring[i] = new TickRecord();
        }
        queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        running = new AtomicBoolean(true);
        writerThread = new Thread(this::writerLoop, "tickbus-ndjson-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        sessionOutcomes = new SessionOutcomeTracker();
    }

    public void setArrivalLagHistogram(SidecarArrivalLagHistogram histogram) {
        this.arrivalLagHistogram = histogram;
    }

    public void enqueue(CombatTickState state,
                        ReplayProjection projection,
                        TickBus bus,
                        Intent[] winners,
                        EliminationReason[] eliminationReasons,
                        SidecarTickMetrics sidecarMetrics,
                        ReflectionDispatcher dispatcher) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.recordKind = TickRecord.RECORD_ORCH;
        slot.tickIndex = state.tickIndex();
        slot.busSize = bus.size();
        slot.droppedPublishes = bus.droppedPublishes();
        slot.maxRankDropped = bus.maxRankDropped();
        slot.recordsDropped = recordsDropped;
        copyProjection(slot, projection);
        sessionOutcomes.onProjection(state.tickIndex(), projection);
        fillBusIntents(slot, bus, winners, state.tickIndex());
        if (sidecarMetrics != null) {
            slot.masklessIntents = sidecarMetrics.masklessIntents();
            slot.sidecarAckLag = sidecarMetrics.sidecarAckLag();
            slot.sidecarHealthy = sidecarMetrics.sidecarHealthy();
            slot.sidecarStaleTickDrops = sidecarMetrics.staleTickDrops();
            slot.sidecarStaleStateDrops = sidecarMetrics.staleStateDrops();
            slot.wireRejects = sidecarMetrics.wireRejects();
        }
        if (dispatcher instanceof DispatchStateSource) {
            int[] ordinals = ((DispatchStateSource) dispatcher).dispatchStateOrdinals();
            for (int i = 0; i < 3; i++) {
                slot.dispatchStateOrdinal[i] = ordinals[i];
            }
        }
        for (int i = 0; i < 3; i++) {
            slot.channelDropReason[i] = eliminationReasons[i];
            Intent w = winners[i];
            if (w != null) {
                slot.winnerKind[i] = w.kind();
                slot.winnerRank[i] = w.rank();
                slot.winnerAdvisor[i] = w.advisorOrdinal();
            }
        }
        offer(slot);
    }

    /** Every 50 ticks and at session close (best-effort flush). */
    public void enqueuePeriodic(long tickIndex) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.recordKind = TickRecord.RECORD_PERIODIC;
        slot.tickIndex = tickIndex;
        sessionOutcomes.copyTo(slot);
        if (arrivalLagHistogram != null) {
            long[] pct = new long[2];
            arrivalLagHistogram.snapshotPercentiles(pct);
            slot.sidecarArrivalLagP50 = pct[0];
            slot.sidecarArrivalLagP99 = pct[1];
        }
        offer(slot);
    }

    public void flushPeriodicBestEffort(long tickIndex) {
        enqueuePeriodic(tickIndex);
    }

    private static void copyProjection(TickRecord slot, ReplayProjection projection) {
        if (projection == null) {
            return;
        }
        slot.stateFingerprint = projection.fingerprint;
        slot.localHp = projection.localHp;
        slot.specEnergyPercent = projection.specEnergy;
        slot.specAvailableFromTick = projection.specAvailableFromTick;
        slot.eatThreshold = projection.eatThreshold;
        slot.protectPrayerMask = projection.protectPrayerMask;
        slot.foodSlotIndex = projection.foodSlotIndex;
        slot.damageTaken = projection.damageTaken;
        slot.targetNpcIndex = projection.targetNpcIndex;
    }

    /**
     * Stub orch when {@code evaluateEarly} did not run — pairs with legacy for shadow diff.
     * Empty winners; {@code skipped} enum names the early-return path.
     */
    public void enqueueSkippedOrch(long tickIndex,
                                   OrchestratorSkipReason reason,
                                   SidecarTickMetrics sidecarMetrics) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.recordKind = TickRecord.RECORD_ORCH;
        slot.tickIndex = tickIndex;
        slot.orchestratorSkipReason = reason == null
                ? OrchestratorSkipReason.DISABLED.ordinal()
                : reason.ordinal();
        slot.busSize = 0;
        slot.maxRankDropped = -1;
        slot.recordsDropped = recordsDropped;
        if (sidecarMetrics != null) {
            slot.masklessIntents = sidecarMetrics.masklessIntents();
            slot.sidecarAckLag = sidecarMetrics.sidecarAckLag();
            slot.sidecarHealthy = sidecarMetrics.sidecarHealthy();
            slot.sidecarStaleTickDrops = sidecarMetrics.staleTickDrops();
            slot.sidecarStaleStateDrops = sidecarMetrics.staleStateDrops();
            slot.wireRejects = sidecarMetrics.wireRejects();
        }
        offer(slot);
    }

    /** Shadow mode: legacy {@code lastAction} after monolith runs (end of tick). */
    public void enqueueLegacyTail(long tickIndex, String legacyAction) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.recordKind = TickRecord.RECORD_LEGACY;
        slot.tickIndex = tickIndex;
        slot.legacyAction = legacyAction == null ? "" : legacyAction;
        slot.legacyUncomparableSubtype = LegacyComparability.classify(slot.legacyAction);
        offer(slot);
    }

    private static void fillBusIntents(TickRecord slot, TickBus bus, Intent[] winners, long tickIndex) {
        int n = Math.min(bus.size(), TickRecord.MAX_BUS_INTENTS);
        slot.busIntentCount = n;
        for (int i = 0; i < n; i++) {
            Intent intent = bus.get(i);
            if (intent == null) {
                continue;
            }
            slot.intentKindOrd[i] = (byte) intent.kind().ordinal();
            slot.intentPriorityOrd[i] = (byte) intent.priority().ordinal();
            slot.intentAdvisorOrd[i] = (byte) intent.advisorOrdinal();
            slot.intentRank[i] = intent.rank();
            slot.intentItemId[i] = intent.itemId();
            slot.intentNpcIndex[i] = intent.npcIndex();
            slot.intentSlotIndex[i] = intent.slotIndex();
            slot.intentBornTick[i] = intent.bornTick();
            slot.intentTtlTicks[i] = intent.ttlTicks();
            slot.intentBylineId[i] = (byte) intent.bylineId();
            slot.intentEliminationOrd[i] = (byte) loserReason(intent, winners, tickIndex).ordinal();
        }
    }

    private static EliminationReason loserReason(Intent intent, Intent[] winners, long tickIndex) {
        if (intent == null) {
            return EliminationReason.NONE;
        }
        for (int ch = 0; ch < winners.length; ch++) {
            if (winners[ch] == intent) {
                return EliminationReason.NONE;
            }
        }
        if (!intent.isValidAt(tickIndex)) {
            return EliminationReason.STALE_TICK;
        }
        return EliminationReason.RANK;
    }

    private void offer(TickRecord slot) {
        if (!queue.offer(slot)) {
            recordsDropped++;
        }
    }

    public void shutdown() {
        running.set(false);
        writerThread.interrupt();
    }

    private void writerLoop() {
        long lastFlushMs = System.currentTimeMillis();
        int linesSinceFlush = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (running.get() || !queue.isEmpty()) {
                TickRecord rec = queue.poll();
                if (rec == null) {
                    Thread.sleep(5L);
                    continue;
                }
                writer.write(serializeLine(rec));
                writer.newLine();
                linesSinceFlush++;
                long now = System.currentTimeMillis();
                if (linesSinceFlush >= 32 || now - lastFlushMs >= 250L) {
                    writer.flush();
                    linesSinceFlush = 0;
                    lastFlushMs = now;
                }
            }
            writer.flush();
        } catch (IOException | InterruptedException ex) {
            // Daemon writer: never propagate to tick thread.
        }
    }

    private static String serializeLine(TickRecord rec) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"schemaVersion\":").append(rec.schemaVersion);
        sb.append(",\"recordKind\":").append(recordKindJson(rec.recordKind));
        sb.append(",\"tickIndex\":").append(rec.tickIndex);
        if (rec.recordKind == TickRecord.RECORD_LEGACY) {
            sb.append(",\"legacyAction\":\"").append(escapeJson(rec.legacyAction)).append('"');
            String subtype = LegacyComparability.subtypeJson(rec.legacyUncomparableSubtype);
            if (subtype != null) {
                sb.append(",\"uncomparableSubtype\":\"").append(subtype).append('"');
            }
            sb.append('}');
            return sb.toString();
        }
        if (rec.recordKind == TickRecord.RECORD_PERIODIC) {
            sb.append(",\"outcomes\":{");
            sb.append("\"eatsUsed\":").append(rec.eatsUsed);
            sb.append(",\"specsUsed\":").append(rec.specsUsed);
            sb.append(",\"prayerUptimeTicks\":").append(rec.prayerUptimeTicks);
            sb.append(",\"damageTaken\":").append(rec.outcomeDamageTaken);
            sb.append('}');
            sb.append(",\"sidecarArrivalLagTicks\":{");
            sb.append("\"p50\":").append(rec.sidecarArrivalLagP50);
            sb.append(",\"p99\":").append(rec.sidecarArrivalLagP99);
            sb.append("}}");
            return sb.toString();
        }
        sb.append(",\"replayProjection\":{");
        sb.append("\"fingerprint\":").append(rec.stateFingerprint);
        sb.append(",\"localHp\":").append(rec.localHp);
        sb.append(",\"specEnergy\":").append(rec.specEnergyPercent);
        sb.append(",\"specAvailableFromTick\":").append(rec.specAvailableFromTick);
        sb.append(",\"eatThreshold\":").append(rec.eatThreshold);
        sb.append(",\"protectPrayerMask\":").append(rec.protectPrayerMask);
        sb.append(",\"foodSlotIndex\":").append(rec.foodSlotIndex);
        sb.append(",\"damageTaken\":").append(rec.damageTaken);
        sb.append(",\"targetNpcIndex\":").append(rec.targetNpcIndex);
        sb.append('}');
        if (rec.orchestratorSkipReason != OrchestratorSkipReason.NONE.ordinal()) {
            OrchestratorSkipReason skip = OrchestratorSkipReason.values()[rec.orchestratorSkipReason];
            sb.append(",\"skipped\":\"").append(skip.name()).append('"');
        }
        sb.append(",\"busSize\":").append(rec.busSize);
        sb.append(",\"droppedPublishes\":").append(rec.droppedPublishes);
        sb.append(",\"maxRankDropped\":").append(rec.maxRankDropped);
        sb.append(",\"recordsDropped\":").append(rec.recordsDropped);
        sb.append(",\"masklessIntents\":").append(rec.masklessIntents);
        sb.append(",\"sidecarHealthy\":").append(rec.sidecarHealthy);
        sb.append(",\"sidecarAckLag\":").append(rec.sidecarAckLag);
        sb.append(",\"staleTickDrops\":").append(rec.sidecarStaleTickDrops);
        sb.append(",\"staleStateDrops\":").append(rec.sidecarStaleStateDrops);
        sb.append(",\"wireRejects\":").append(rec.wireRejects);
        sb.append(",\"busIntents\":[");
        appendBusIntents(sb, rec);
        sb.append("],\"winners\":[");
        appendWinners(sb, rec);
        sb.append("],\"channelDrops\":[");
        appendDrops(sb, rec);
        sb.append("],\"dispatchState\":[");
        appendDispatchStates(sb, rec);
        sb.append("]}");
        return sb.toString();
    }

    private static void appendBusIntents(StringBuilder sb, TickRecord rec) {
        ActionKind[] kinds = ActionKind.values();
        for (int i = 0; i < rec.busIntentCount; i++) {
            if (i > 0) {
                sb.append(',');
            }
            int kindOrd = rec.intentKindOrd[i] & 0xFF;
            String kindName = kindOrd < kinds.length ? kinds[kindOrd].name() : "IDLE";
            sb.append("{\"kind\":\"").append(kindName).append('"');
            sb.append(",\"priority\":").append(rec.intentPriorityOrd[i] & 0xFF);
            sb.append(",\"advisor\":").append(rec.intentAdvisorOrd[i] & 0xFF);
            sb.append(",\"rank\":").append(rec.intentRank[i]);
            sb.append(",\"itemId\":").append(rec.intentItemId[i]);
            sb.append(",\"npcIndex\":").append(rec.intentNpcIndex[i]);
            sb.append(",\"slotIndex\":").append(rec.intentSlotIndex[i]);
            sb.append(",\"bornTick\":").append(rec.intentBornTick[i]);
            sb.append(",\"ttlTicks\":").append(rec.intentTtlTicks[i]);
            sb.append(",\"byline\":\"").append(IntentBylines.label(rec.intentBylineId[i] & 0xFF)).append('"');
            int erOrd = rec.intentEliminationOrd[i] & 0xFF;
            EliminationReason er = erOrd < EliminationReason.values().length
                    ? EliminationReason.values()[erOrd] : EliminationReason.NONE;
            if (er != EliminationReason.NONE) {
                sb.append(",\"elimination\":\"").append(er.name()).append('"');
            }
            sb.append('}');
        }
    }

    private static String recordKindJson(int kind) {
        if (kind == TickRecord.RECORD_LEGACY) {
            return "\"legacy\"";
        }
        if (kind == TickRecord.RECORD_PERIODIC) {
            return "\"periodic\"";
        }
        return "\"orch\"";
    }

    private static void appendDispatchStates(StringBuilder sb, TickRecord rec) {
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"ch\":").append(i);
            sb.append(",\"state\":\"").append(TickDispatchState.values()[rec.dispatchStateOrdinal[i]].name()).append("\"}");
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void appendWinners(StringBuilder sb, TickRecord rec) {
        boolean first = true;
        for (int i = 0; i < 3; i++) {
            if (rec.winnerKind[i] == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"ch\":").append(i);
            sb.append(",\"kind\":\"").append(rec.winnerKind[i].name()).append('"');
            sb.append(",\"rank\":").append(rec.winnerRank[i]);
            sb.append(",\"advisor\":").append(rec.winnerAdvisor[i]);
            sb.append('}');
        }
    }

    private static void appendDrops(StringBuilder sb, TickRecord rec) {
        boolean first = true;
        for (int i = 0; i < 3; i++) {
            if (rec.channelDropReason[i] == EliminationReason.NONE) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"ch\":").append(i);
            sb.append(",\"reason\":\"").append(rec.channelDropReason[i].name()).append("\"}");
        }
    }
}
