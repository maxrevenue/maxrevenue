package com.sun.java.fontmgr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional per-tick combat recorder, so thresholds can be tuned from data
 * instead of from feel (#15).
 *
 * <p><b>Off by default.</b> Enable with {@code -Droatz.rec=true} for the default
 * file, or {@code -Droatz.rec=D:\path\session.tsv} for an explicit one. Writing
 * a file on the game client's behalf is a deliberate, visible choice: this never
 * turns itself on, and it always logs where it is writing.
 *
 * <p>One TSV row per published {@link CombatState}, under a self-describing
 * header, so a session can be grepped, diffed or replayed offline. The
 * {@code defpray} column carries the branch the defensive-prayer logic took that
 * tick, which is what makes an A/B of that logic possible after the fact.
 *
 * <p><b>The tick thread never does file I/O.</b> Rows go into a bounded queue
 * drained by a daemon writer. If the disk stalls or the queue fills, rows are
 * dropped and counted instead of blocking a game tick — a recorder that can cost
 * a tick is worse than no recorder.
 */
public final class TickRecorder {

    /** Queue depth. At ~600ms/tick this is over half an hour of backlog. */
    private static final int QUEUE_CAPACITY = 4096;
    /** Stop after this many rows so an unattended session cannot fill the disk. */
    private static final long MAX_ROWS = 500_000L;
    /** Flush at least this often while rows keep arriving. */
    private static final long FLUSH_INTERVAL_MS = 1_000L;

    private static final String[] COLUMNS = {
        "tick", "seq", "tgt", "thp", "tmax", "spec", "okill", "odh",
        "owpn", "ostyle", "oh", "anim", "tanim", "defpray", "action"
    };

    private static final TickRecorder DISABLED = new TickRecorder(null);

    private final BlockingQueue<String> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final Path path;
    private volatile boolean running;
    private volatile boolean warnedDropped;
    private Thread writer;

    private TickRecorder(Path path) {
        this.path = path;
        this.queue = path == null ? null : new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    }

    /** The no-op recorder, used when recording is off. */
    public static TickRecorder disabled() {
        return DISABLED;
    }

    /**
     * Builds the recorder described by {@code -Droatz.rec}.
     *
     * <ul>
     *   <li>unset / blank / {@code false} — disabled (the default)</li>
     *   <li>{@code true} / {@code 1} / {@code on} / {@code yes} — default file
     *       under {@code %APPDATA%\Roatz\ticks\}</li>
     *   <li>anything else — treated as an explicit output path</li>
     * </ul>
     */
    public static TickRecorder fromProperty() {
        String v = System.getProperty("roatz.rec", "").trim();
        if (v.isEmpty() || "false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v) || "0".equals(v)) {
            return DISABLED;
        }
        boolean auto = "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v) || "1".equals(v);
        Path out;
        try {
            out = auto ? defaultPath() : Paths.get(v).toAbsolutePath();
        } catch (Exception e) {
            FontManager.log("[rec] bad path from -Droatz.rec=" + v + ": " + e.getMessage());
            return DISABLED;
        }
        try {
            if (out.getParent() != null) Files.createDirectories(out.getParent());
        } catch (Exception e) {
            FontManager.log("[rec] cannot create " + out.getParent() + ": " + e.getMessage());
            return DISABLED;
        }
        TickRecorder rec = new TickRecorder(out);
        return rec.start() ? rec : DISABLED;
    }

    private static Path defaultPath() {
        String appdata = System.getenv("APPDATA");
        Path base = (appdata != null && !appdata.trim().isEmpty())
                ? Paths.get(appdata, Product.NAME, "ticks")
                : Paths.get(System.getProperty("java.io.tmpdir", "."), "roatz-ticks");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return base.resolve("combat-" + stamp + ".tsv");
    }

    private boolean start() {
        final BufferedWriter w;
        try {
            w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            writeHeader(w);
        } catch (Exception e) {
            FontManager.log("[rec] cannot open " + path + ": " + e.getMessage());
            return false;
        }
        running = true;
        writer = new Thread(() -> drain(w), "roatz-tick-recorder");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "roatz-tick-recorder-stop"));
        FontManager.log("[rec] recording ticks to " + path);
        return true;
    }

    private void writeHeader(BufferedWriter w) throws IOException {
        w.write("# " + Product.NAME + " " + Product.VERSION + " tick recording");
        w.newLine();
        w.write("# started " + LocalDateTime.now());
        w.newLine();
        w.write(String.join("\t", COLUMNS));
        w.newLine();
        w.flush();
    }

    /** True when rows are actually being written. */
    public boolean isEnabled() {
        return path != null;
    }

    /** Output file, or null when disabled. */
    public Path path() {
        return path;
    }

    /** Rows written so far. */
    public long rowsWritten() {
        return written.get();
    }

    /**
     * Queues one row. Never blocks and never throws: on overflow the row is
     * dropped and counted, because a recorder must never cost a game tick.
     */
    public void record(CombatState s) {
        if (queue == null || s == null) return;
        if (!running || written.get() >= MAX_ROWS) return;
        if (!queue.offer(format(s))) {
            dropped.incrementAndGet();
            if (!warnedDropped) {
                warnedDropped = true;
                FontManager.log("[rec] queue full — dropping rows (disk too slow?)");
            }
        }
    }

    /** Stable column order; must match {@link #COLUMNS}. */
    private String format(CombatState s) {
        StringBuilder sb = new StringBuilder(192);
        sb.append(s.tick).append('\t')
          .append(s.seq).append('\t')
          .append(oneLine(s.targetName)).append('\t')
          .append(s.targetHp).append('\t')
          .append(s.targetMaxHp).append('\t')
          .append(s.specEnergy).append('\t')
          .append(s.inKillRange ? 1 : 0).append('\t')
          .append(s.opponentIsDh ? 1 : 0).append('\t')
          .append(s.opponentWeaponId()).append('\t')
          .append(s.opponentWeaponStyle()).append('\t')
          .append(oneLine(s.ourOverhead)).append('\t')
          .append(s.localAnim).append('\t')
          .append(s.lastTargetAnim).append('\t')
          .append(oneLine(s.defPrayTrace)).append('\t')
          .append(oneLine(s.actionLabel()));
        return sb.toString();
    }

    /** Keeps one record on one line — a stray tab or newline would shred the TSV. */
    private static String oneLine(String v) {
        if (v == null) return "";
        String out = v.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return out.length() > 120 ? out.substring(0, 120) : out;
    }

    private void drain(BufferedWriter w) {
        long lastFlush = System.currentTimeMillis();
        try {
            while (running || !queue.isEmpty()) {
                String line = queue.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    w.write(line);
                    w.newLine();
                    written.incrementAndGet();
                }
                long now = System.currentTimeMillis();
                if (line == null || now - lastFlush >= FLUSH_INTERVAL_MS) {
                    w.flush();
                    lastFlush = now;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            FontManager.log("[rec] writer stopped: " + e.getMessage());
        } finally {
            try {
                long d = dropped.get();
                if (d > 0) {
                    w.write("# dropped " + d + " row(s)");
                    w.newLine();
                }
                w.write("# rows " + written.get());
                w.newLine();
                w.flush();
                w.close();
            } catch (Exception ignored) {}
        }
    }

    /** Stops the writer and flushes. Safe to call more than once, from any thread. */
    public void close() {
        if (queue == null || !running) return;
        running = false;
        Thread t = writer;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
