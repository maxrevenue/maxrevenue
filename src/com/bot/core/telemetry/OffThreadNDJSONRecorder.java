package com.bot.core.telemetry;

import com.bot.core.bus.Intent;
import com.bot.core.bus.TickBus;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.EliminationReason;
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

    /**
     * Tick thread: copy primitives from resolved winners (no bus snapshot, no Strings).
     */
    public void enqueue(CombatTickState state,
                        TickBus bus,
                        Intent[] winners,
                        EliminationReason[] eliminationReasons,
                        SidecarTickMetrics sidecarMetrics) {
        TickRecord slot = ring[ringCursor];
        ringCursor = (ringCursor + 1) % RING_SIZE;
        slot.reset();
        slot.tickIndex = state.tickIndex();
        slot.busSize = bus.size();
        slot.droppedPublishes = bus.droppedPublishes();
        if (sidecarMetrics != null) {
            slot.masklessIntents = sidecarMetrics.masklessIntents();
            slot.sidecarAckLag = sidecarMetrics.sidecarAckLag();
            slot.sidecarHealthy = sidecarMetrics.sidecarHealthy();
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
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"tickIndex\":").append(rec.tickIndex);
        sb.append(",\"busSize\":").append(rec.busSize);
        sb.append(",\"droppedPublishes\":").append(rec.droppedPublishes);
        sb.append(",\"masklessIntents\":").append(rec.masklessIntents);
        sb.append(",\"sidecarHealthy\":").append(rec.sidecarHealthy);
        sb.append(",\"sidecarAckLag\":").append(rec.sidecarAckLag);
        sb.append(",\"winners\":[");
        appendWinners(sb, rec);
        sb.append("],\"channelDrops\":[");
        appendDrops(sb, rec);
        sb.append("]}");
        return sb.toString();
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
