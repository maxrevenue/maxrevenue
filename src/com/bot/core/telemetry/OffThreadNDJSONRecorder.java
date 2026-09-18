package com.bot.core.telemetry;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.bus.TickBus;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.orchestrator.DispatchStateSource;
import com.bot.core.orchestrator.ReflectionDispatcher;
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
 */
public final class OffThreadNDJSONRecorder {

    public static final int RECORD_ORCH = 0;
    public static final int RECORD_LEGACY = 1;

    private static final int RING_SIZE = 64;
    private static final int QUEUE_CAPACITY = 256;

    private final TickRecord[] ring;
    private int ringCursor;
    private final ArrayBlockingQueue<TickRecord> queue;
    private final AtomicBoolean running;
    private final Thread writerThread;
    private final Path outputPath;
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
    }

    public void enqueue(CombatTickState state,
                        TickBus bus,
                        Intent[] winners,
                        EliminationReason[] eliminationReasons,
                        SidecarTickMetrics sidecarMetrics,
                        ReflectionDispatcher dispatcher) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.recordKind = RECORD_ORCH;
        slot.tickIndex = state.tickIndex();
        slot.busSize = bus.size();
        slot.droppedPublishes = bus.droppedPublishes();
        slot.maxRankDropped = bus.maxRankDropped();
        slot.stateFingerprint = state.getFingerprint();
        slot.localHp = state.localHp();
        slot.specEnergyPercent = state.specEnergyPercent();
        slot.specAvailableFromTick = state.specAvailableFromTick();
        fillBusIntents(slot, bus);
        if (sidecarMetrics != null) {
            slot.masklessIntents = sidecarMetrics.masklessIntents();
            slot.sidecarAckLag = sidecarMetrics.sidecarAckLag();
            slot.sidecarHealthy = sidecarMetrics.sidecarHealthy();
            slot.sidecarWireFrameRejects = sidecarMetrics.wireFrameRejects();
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

    /** Shadow mode: legacy {@code lastAction} after monolith runs (end of tick). */
    public void enqueueLegacyTail(long tickIndex, String legacyAction) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.recordKind = RECORD_LEGACY;
        slot.tickIndex = tickIndex;
        slot.legacyAction = legacyAction == null ? "" : legacyAction;
        slot.legacyUncomparableSubtype = LegacyComparability.classify(slot.legacyAction);
        offer(slot);
    }

    private static void fillBusIntents(TickRecord slot, TickBus bus) {
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
        }
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
        sb.append(",\"recordKind\":").append(rec.recordKind == RECORD_LEGACY ? "\"legacy\"" : "\"orch\"");
        sb.append(",\"tickIndex\":").append(rec.tickIndex);
        if (rec.recordKind == RECORD_LEGACY) {
            sb.append(",\"legacyAction\":\"").append(escapeJson(rec.legacyAction)).append('"');
            String subtype = LegacyComparability.subtypeJson(rec.legacyUncomparableSubtype);
            if (subtype != null) {
                sb.append(",\"uncomparableSubtype\":\"").append(subtype).append('"');
            }
            sb.append('}');
            return sb.toString();
        }
        sb.append(",\"replayProjection\":{");
        sb.append("\"fingerprint\":").append(rec.stateFingerprint);
        sb.append(",\"localHp\":").append(rec.localHp);
        sb.append(",\"specEnergy\":").append(rec.specEnergyPercent);
        sb.append(",\"specAvailableFromTick\":").append(rec.specAvailableFromTick);
        sb.append('}');
        sb.append(",\"busSize\":").append(rec.busSize);
        sb.append(",\"droppedPublishes\":").append(rec.droppedPublishes);
        sb.append(",\"maxRankDropped\":").append(rec.maxRankDropped);
        sb.append(",\"masklessIntents\":").append(rec.masklessIntents);
        sb.append(",\"sidecarHealthy\":").append(rec.sidecarHealthy);
        sb.append(",\"sidecarAckLag\":").append(rec.sidecarAckLag);
        sb.append(",\"sidecarWireFrameRejects\":").append(rec.sidecarWireFrameRejects);
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
            sb.append('}');
        }
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
