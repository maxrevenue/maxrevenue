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
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead per-tick combat recorder for EchoForge golden-master replays.
 *
 * <p>Writes one NDJSON object per completed {@code onTick()} so the Java 17
 * EchoForge harness can feed the same snapshots into
 * {@code BehaviorTreeDecisionEngine} offline.
 *
 * <p><b>Off by default.</b> Flip {@link #ENABLED} to {@code true}, or set
 * {@code -Droatz.rec=true} / {@code -Droatz.rec=/path/session.ndjson}. The tick
 * thread never does file I/O: rows enter a bounded queue drained by a daemon
 * writer. On overflow, rows are dropped and counted instead of stalling a tick.
 *
 * <p>Default output: {@code logs/replays/tick_session_<timestamp>.ndjson}.
 */
public final class TickRecorder {

    /**
     * Master toggle. When {@code true}, recording starts with the default
     * NDJSON path even if {@code -Droatz.rec} is unset. Always safe to leave
     * {@code false} in production attaches.
     */
    public static volatile boolean ENABLED = false;

    private static final int QUEUE_CAPACITY = 4096;
    private static final long MAX_ROWS = 500_000L;
    private static final long FLUSH_INTERVAL_MS = 1_000L;

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
     * Builds the recorder described by {@link #ENABLED} and {@code -Droatz.rec}.
     *
     * <ul>
     *   <li>{@link #ENABLED}{@code false} and unset / blank / {@code false}
     *       property — disabled (the default)</li>
     *   <li>{@link #ENABLED}{@code true}, or property {@code true}/{@code 1}/
     *       {@code on}/{@code yes} — default file under {@code logs/replays/}</li>
     *   <li>anything else in the property — treated as an explicit output path</li>
     * </ul>
     */
    public static TickRecorder fromProperty() {
        String v = System.getProperty("roatz.rec", "").trim();
        boolean propOff = v.isEmpty() || "false".equalsIgnoreCase(v)
                || "off".equalsIgnoreCase(v) || "0".equals(v);
        if (!ENABLED && propOff) {
            return DISABLED;
        }
        boolean auto = ENABLED && propOff
                || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v) || "1".equals(v);
        Path out;
        try {
            out = auto ? defaultPath() : Paths.get(v).toAbsolutePath();
        } catch (Exception e) {
            FontManager.log("[rec] bad path from -Droatz.rec=" + v + ": " + e.getMessage());
            return DISABLED;
        }
        try {
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
        } catch (Exception e) {
            FontManager.log("[rec] cannot create " + out.getParent() + ": " + e.getMessage());
            return DISABLED;
        }
        TickRecorder rec = new TickRecorder(out);
        return rec.start() ? rec : DISABLED;
    }

    /**
     * Leaves a breadcrumb when an explicit {@code -Droatz.rec=<path>} was asked
     * for but the agent aborts before the recorder starts (license refused,
     * client missing, …). Without this the user is left staring at a 0-byte
     * file with no explanation.
     *
     * <p>Writes a single {@code #} comment line, which the EchoForge loader
     * already skips, so the file stays parseable and is visibly non-empty.
     * Auto tokens ({@code true}/{@code on}/{@code 1}) are ignored because the
     * default path was never named by the user.
     */
    public static void noteAbortedRecording(String reason) {
        String v = System.getProperty("roatz.rec", "").trim();
        if (v.isEmpty() || isAutoToken(v)) return;
        try {
            Path p = Paths.get(v).toAbsolutePath();
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            String line = "# EchoForge recording DID NOT start: " + reason
                    + "  (" + Product.NAME + " " + Product.VERSION + ")\n";
            Files.write(p, line.getBytes(StandardCharsets.UTF_8));
            FontManager.warn("[rec] " + reason + " — wrote abort note to " + p);
        } catch (Exception ignored) {
            // Never let a diagnostic break the abort path.
        }
    }

    private static boolean isAutoToken(String v) {
        return "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v)
                || "yes".equalsIgnoreCase(v) || "1".equals(v)
                || "false".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v)
                || "0".equals(v);
    }

    private static Path defaultPath() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Paths.get("logs", "replays", "tick_session_" + stamp + ".ndjson").toAbsolutePath();
    }

    private boolean start() {
        final BufferedWriter w;
        try {
            w = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            FontManager.log("[rec] cannot open " + path + ": " + e.getMessage());
            return false;
        }
        running = true;
        writer = new Thread(() -> drain(w), "echoforge-tick-recorder");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "echoforge-tick-recorder-stop"));
        FontManager.log("[rec] EchoForge recording ticks to " + path);
        return true;
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

    /** Rows dropped because the writer could not keep up. */
    public long rowsDropped() {
        return dropped.get();
    }

    /**
     * Queues one NDJSON combat snapshot. Never blocks and never throws: on
     * overflow the row is dropped and counted, because a recorder must never
     * cost a game tick.
     *
     * @param tickCount      game tick counter
     * @param playerHp       local current HP
     * @param playerMaxHp    local max HP
     * @param prayer         local prayer points
     * @param specEnergy     special-attack energy 0..100
     * @param equipment      worn slot index → item id (may be null/empty)
     * @param inventory      inventory slot index → item id (may be null/empty)
     * @param targetHp       target current HP, or {@code -1}
     * @param targetMaxHp    target max HP, or {@code -1}
     * @param weaponId       target weapon id, or {@code 0}/{@code -1} unknown
     * @param animationId    target animation id, or {@code -1}
     * @param distance       chebyshev tile distance, or {@code -1} unknown
     * @param attackStyle    opponent style: {@code MELEE}/{@code RANGED}/{@code MAGIC}/{@code UNKNOWN}
     * @param prayers        active local prayer names, e.g. {@code ["PIETY"]} (may be null)
     * @param executedAction raw agent action label, e.g. {@code "ARB_EAT_dh-axe@123"}.
     *                       Written as compact {@code executedAction} plus the raw
     *                       {@code actionLabel}; see {@link #compactExecutedAction(String)}
     */
    public void recordTick(long tickCount,
                           int playerHp, int playerMaxHp, int prayer, int specEnergy,
                           Map<Integer, Integer> equipment,
                           Map<Integer, Integer> inventory,
                           int targetHp, int targetMaxHp,
                           int weaponId, int animationId, int distance,
                           String attackStyle,
                           java.util.List<String> prayers,
                           String executedAction) {
        if (queue == null) {
            return;
        }
        if (!running || written.get() >= MAX_ROWS) {
            return;
        }
        String line = formatNdjson(tickCount, playerHp, playerMaxHp, prayer, specEnergy,
                equipment, inventory, targetHp, targetMaxHp, weaponId, animationId, distance,
                attackStyle, prayers, executedAction);
        if (!queue.offer(line)) {
            dropped.incrementAndGet();
            if (!warnedDropped) {
                warnedDropped = true;
                FontManager.log("[rec] queue full — dropping rows (disk too slow?)");
            }
        }
    }

    /**
     * Compatibility path: records whatever {@link CombatState} already carries.
     * Prefer {@link #recordTick} when player vitals / inventory are available.
     */
    public void record(CombatState s) {
        if (s == null) {
            return;
        }
        AnimationDb.AttackStyle style = s.opponentWeaponStyle();
        recordTick(s.tick,
                -1, -1, -1, s.specEnergy,
                null, null,
                s.targetHp, s.targetMaxHp,
                s.opponentWeaponId(), s.lastTargetAnim, -1,
                style == null ? "UNKNOWN" : style.name(),
                java.util.Collections.<String>emptyList(),
                s.actionLabel());
    }

    /** Builds one valid single-line NDJSON object (Java 11 string builder, no JSON lib). */
    static String formatNdjson(long tickCount,
                               int playerHp, int playerMaxHp, int prayer, int specEnergy,
                               Map<Integer, Integer> equipment,
                               Map<Integer, Integer> inventory,
                               int targetHp, int targetMaxHp,
                               int weaponId, int animationId, int distance,
                               String attackStyle,
                               java.util.List<String> prayers,
                               String executedAction) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendLong(sb, "tickCount", tickCount).append(',');
        sb.append("\"player\":{");
        appendInt(sb, "hp", playerHp).append(',');
        appendInt(sb, "maxHp", playerMaxHp).append(',');
        appendInt(sb, "prayer", prayer).append(',');
        appendInt(sb, "specEnergy", specEnergy).append(',');
        appendIntMap(sb, "equipment", equipment).append(',');
        appendIntMap(sb, "inventory", inventory).append(',');
        appendStringArray(sb, "prayers", prayers);
        sb.append("},");
        sb.append("\"target\":{");
        appendInt(sb, "hp", targetHp).append(',');
        appendInt(sb, "maxHp", targetMaxHp).append(',');
        appendInt(sb, "weaponId", weaponId).append(',');
        appendInt(sb, "animationId", animationId).append(',');
        appendInt(sb, "distance", distance).append(',');
        appendString(sb, "attackStyle", normalizeStyle(attackStyle));
        sb.append("},");
        appendString(sb, "executedAction", compactExecutedAction(executedAction));
        sb.append(',');
        appendString(sb, "actionLabel", executedAction == null ? "" : executedAction);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Collapses any style token to {@code MELEE}/{@code RANGED}/{@code MAGIC}/
     * {@code UNKNOWN} so the EchoForge {@code CombatStyle} parse never silently
     * drops the field.
     */
    static String normalizeStyle(String style) {
        if (style == null) return "UNKNOWN";
        String u = style.trim().toUpperCase(java.util.Locale.ROOT);
        if ("MELEE".equals(u) || "RANGED".equals(u) || "MAGIC".equals(u)) return u;
        return "UNKNOWN";
    }

    /** JSON string array; null/blank entries and a null list produce {@code []}. */
    private static StringBuilder appendStringArray(StringBuilder sb, String key, java.util.List<String> values) {
        sb.append('"').append(key).append("\":[");
        if (values != null) {
            boolean first = true;
            for (String v : values) {
                if (v == null || v.isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                escapeJson(sb, v);
                sb.append('"');
            }
        }
        return sb.append(']');
    }

    /**
     * Maps an agent action label to the compact vocabulary the EchoForge fixture
     * loader understands: {@code "EAT:"}, {@code "SPEC:"}, {@code "ATTACK"},
     * {@code "EQUIP:<itemId>"}, {@code "PRAYER:<name>"}, or {@code ""} for
     * observational / failed / unknown ticks.
     *
     * <p>The agent's {@code lastAction} labels are rich ({@code "BIGHIT_SPEC@123"},
     * {@code "ARB_EAT_dh-axe@123"}, {@code "GMAUL_NOENERGY@123"}). The loader's
     * {@code ActionExpectation} only recognizes {@code EAT:}/{@code SPEC:}/{@code ATTACK}
     * and silently skips anything else, so raw labels made every live recording
     * assert nothing. This converts the common completed actions and maps failed
     * attempts to {@code ""} (skipped) rather than guessing.
     *
     * <p>Trailing detail (inventory slot) is intentionally omitted: {@code "EAT:"}
     * matches any {@code EatAction}, whereas a guessed slot would false-fail.
     */
    public static String compactExecutedAction(String label) {
        if (label == null) {
            return "";
        }
        String u = label.trim().toUpperCase(java.util.Locale.ROOT);
        if (u.isEmpty() || "NONE".equals(u) || "IDLE".equals(u)) {
            return "";
        }
        // Already-compact tokens built by the caller (CombatScript records
        // EQUIP:<itemId> / PRAYER:<enumName> when a swap or flick was actually
        // sent). Pass the detail through so the harness can match on it.
        if (u.startsWith("EQUIP:")) {
            return "EQUIP:" + label.trim().substring("EQUIP:".length()).trim();
        }
        if (u.startsWith("PRAYER:")) {
            return "PRAYER:" + label.trim().substring("PRAYER:".length()).trim();
        }
        if (isFailedAttempt(u)) {
            return "";
        }
        if (isEatLabel(u)) {
            return "EAT:";
        }
        if (isSpecLabel(u)) {
            return "SPEC:";
        }
        if (isAttackLabel(u)) {
            return "ATTACK";
        }
        return "";
    }

    /** A failed/aborted attempt is never an executed action. */
    private static boolean isFailedAttempt(String u) {
        return u.contains("MISS")
                || u.contains("NOFOOD")
                || u.contains("NOENERGY")
                || u.contains("NO_WIELD")
                || u.contains("NO_")
                || u.contains("NOSPLAT")
                || u.contains("STALL")
                || u.contains("HOLD")
                || u.contains("SKIP")
                || u.contains("FAIL")
                || u.contains("ERR");
    }

    private static boolean isEatLabel(String u) {
        return u.contains("EAT")
                || u.contains("MARLIN")
                || u.contains("BREW")
                || u.startsWith("PK_SINGLE")
                || u.startsWith("PK_DOUBLE")
                || u.startsWith("PK_TRIPLE");
    }

    private static boolean isSpecLabel(String u) {
        return u.contains("SPEC")
                || u.contains("GMAUL")
                || u.contains("VOIDWAKER")
                || u.contains("SW_WACK")
                || u.contains("Q_CLAWS");
    }

    private static boolean isAttackLabel(String u) {
        return u.contains("ATTACK")
                || u.contains("_ATK")
                || u.contains("DH_MANUAL_AXE");
    }

    private static StringBuilder appendLong(StringBuilder sb, String key, long value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static StringBuilder appendInt(StringBuilder sb, String key, int value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static StringBuilder appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":\"");
        escapeJson(sb, value);
        return sb.append('"');
    }

    private static StringBuilder appendIntMap(StringBuilder sb, String key, Map<Integer, Integer> map) {
        sb.append('"').append(key).append("\":{");
        if (map != null && !map.isEmpty()) {
            boolean first = true;
            for (Map.Entry<Integer, Integer> e : map.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey().intValue()).append("\":").append(e.getValue().intValue());
            }
        }
        return sb.append('}');
    }

    /** Escapes a string for inclusion inside a JSON string literal. */
    private static void escapeJson(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
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
                    // Trailing meta as a JSON comment is invalid NDJSON; keep a
                    // plain log line only — consumers stop at the last object.
                    FontManager.log("[rec] dropped " + d + " row(s); wrote " + written.get());
                }
                w.flush();
                w.close();
            } catch (IOException ignored) {
                // Best-effort cleanup on shutdown.
            }
        }
    }

    /**
     * Flushes remaining queued rows and closes the writer. Safe to call more
     * than once, from any thread.
     */
    public void flushAndClose() {
        close();
    }

    /** Stops the writer and flushes. Safe to call more than once, from any thread. */
    public void close() {
        if (queue == null || !running) {
            return;
        }
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
