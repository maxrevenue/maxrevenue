package com.sun.java.fontmgr;

import java.io.*;
import java.lang.instrument.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;

/**
 * FontManager — Java agent entry point ({@link java.lang.instrument}).
 *
 * Supports both load-time ({@link #premain}) and runtime Dynamic Attach
 * ({@link #agentmain}) without interrupting host application threads.
 *
 * Agent arguments (comma- or semicolon-separated {@code key=value} tokens):
 * <ul>
 *   <li>{@code plugins=/path/a.jar;/path/b.jar} — JARs appended to the system
 *       class loader via {@link Instrumentation#appendToSystemClassLoaderSearch}</li>
 *   <li>{@code log=/path/to/agent.log} — file log path when {@code -Dagent.filelog=true}</li>
 *   <li>Bare {@code *.jar} path — treated as a plugin JAR</li>
 *   <li>Bare non-jar path — treated as log path (backward compatible)</li>
 * </ul>
 * Also honors {@code -Dfontmgr.plugins=} / {@code -Dagent.plugins=} (comma-separated).
 *
 * Bootstraps (in order):
 *   1. External plugin JARs into system class-loader search
 *   2. Load-time telemetry patches (JVM-arg report, AHK detector)
 *   3. Agnostic scan for the Client class
 *   4. Reflected field/method handles
 *   5. SharedMemory (temp file IPC)
 *   6. TickEngine   (tick polling)
 *   7. CombatScript (per-tick PK logic)
 *   8. StateReader  (HP/Prayer/Spec readers)
 *   9. OverlayUI    (transparent Swing control panel)
 *  10. Command socket on 127.0.0.1:9998 only if -Dagent.cmd=true
 */
public class FontManager {

    // ── Logging ───────────────────────────────────────────────────────────────
    static PrintWriter log;
    private static volatile java.util.function.Consumer<String> logConsumer = null;

    private static final java.time.format.DateTimeFormatter TS =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Always-on in-memory tail. File logging is opt-in (see
     * {@link #loggingEnabled()}), but warnings and the recent history must
     * survive without it — otherwise a dead reflection handle makes the agent
     * silently inert with no way to tell.
     */
    private static final int RING_SIZE = 200;
    private static final java.util.Deque<String> RING = new java.util.ArrayDeque<>(RING_SIZE + 1);
    private static volatile String lastWarning = "";

    /** Reflection handles that failed to resolve; reported once each. */
    private static final java.util.Set<String> MISSING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Prayer/reflection diagnostics (in-memory always; file only when enabled). */
    private static PrintWriter prayDiag;

    static synchronized void prayLog(String msg) {
        record("Prayer", msg, false);
        if (!prayLogEnabled()) return;
        try {
            if (prayDiag == null) {
                java.nio.file.Path dir = Stealth.cacheDir();
                prayDiag = new PrintWriter(new java.io.FileWriter(
                        dir.resolve("fontconfig-pray.dat").toFile(), true), true);
            }
            prayDiag.println(formatLine("Prayer", msg));
            if (prayDiag.checkError()) {
                try { prayDiag.close(); } catch (Exception ignored) {}
                prayDiag = null;
            }
        } catch (Exception ignored) {}
    }

    public static void setLogConsumer(java.util.function.Consumer<String> consumer) {
        logConsumer = consumer;
    }

    /**
     * File log is OFF by default so a normal session writes nothing identifying
     * to disk. Enable with {@code -Dagent.filelog=true} (or a debug flag) when
     * you actually need runtime tracing.
     */
    private static boolean loggingEnabled() {
        return Boolean.getBoolean("agent.filelog")
                || Boolean.getBoolean("agent.debug")
                || Boolean.getBoolean("fontmgr.debug");
    }

    /** Prayer diagnostics file is separate because it is high-volume. */
    private static boolean prayLogEnabled() {
        return loggingEnabled() || Boolean.getBoolean("fontmgr.praylog");
    }

    private static String normalizeBody(String msg) {
        if (msg == null || msg.isEmpty()) return "";
        String body = msg.trim();
        if (body.startsWith("[") && body.indexOf(']') > 0) {
            body = body.substring(body.indexOf(']') + 1).trim();
        }
        return body;
    }

    private static String formatLine(String tag, String msg) {
        // Never substring() a LocalTime: its toString() drops the seconds and
        // nanos when they are zero, which used to throw StringIndexOutOfBounds.
        return "[" + java.time.LocalTime.now().format(TS) + "] [" + tag + "] " + normalizeBody(msg);
    }

    /** Appends to the in-memory tail (and the file when enabled) and notifies the consumer. */
    private static void record(String tag, String msg, boolean toFile) {
        String line = formatLine(tag, msg);
        synchronized (RING) {
            RING.addLast(line);
            while (RING.size() > RING_SIZE) RING.removeFirst();
        }
        if (toFile && loggingEnabled()) {
            PrintWriter w = log;
            if (w != null) { w.println(line); w.flush(); }
        }
        java.util.function.Consumer<String> c = logConsumer;
        if (c != null) { try { c.accept(line); } catch (Exception ignored) {} }
    }

    public static void log(String msg) {
        if (!loggingEnabled()) return;
        record("Core", msg, true);
    }

    /** Verbose traces. Off unless -Dagent.debug=true or -Dfontmgr.debug=true */
    public static void debug(String msg) {
        if (!Boolean.getBoolean("agent.debug") && !Boolean.getBoolean("fontmgr.debug")) return;
        record("Worker", msg, true);
    }

    /**
     * A problem worth seeing in a normal session. Always kept in the in-memory
     * tail and mirrored to the console/file when logging is enabled, so it can
     * be read back with the {@code LOG} socket command or the overlay.
     */
    public static void warn(String msg) {
        lastWarning = normalizeBody(msg);
        record("WARN", msg, true);
    }

    public static void error(String msg) {
        lastWarning = normalizeBody(msg);
        record("ERROR", msg, true);
    }

    /** Reports an unresolved client reflection handle once per handle name. */
    public static void missing(String handle) {
        if (handle == null || !MISSING.add(handle)) return;
        warn("unresolved client handle: " + handle);
    }

    /** Most recent warning/error text, for the HUD and STATUS replies. */
    public static String lastWarning() { return lastWarning; }

    /** Last {@code n} log lines, oldest first (newline-free, socket safe). */
    public static java.util.List<String> recentLog(int n) {
        synchronized (RING) {
            int keep = Math.max(0, n);
            int skip = Math.max(0, RING.size() - keep);
            java.util.List<String> out = new java.util.ArrayList<>();
            int i = 0;
            for (String s : RING) { if (i++ >= skip) out.add(s); }
            return out;
        }
    }

    // ── Core ──────────────────────────────────────────────────────────────────
    static Instrumentation instrumentation() { return instrumentation; }
    private static Instrumentation  instrumentation;
    private static Object           clientInstance;
    private static Class<?>         clientClass;
    private static volatile boolean running    = true;
    private static volatile boolean agentReady = false;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final int CMD_PORT = 9998;

    /** Absolute paths of JARs successfully appended to the system class loader. */
    private static final List<String> LOADED_PLUGIN_JARS =
            Collections.synchronizedList(new ArrayList<>());

    // ── Reflected handles ────────────────────────────────────────────────────
    private static Field   currentSkillLevel;
    private static Field   playerSpecialEnergy;
    private static Method  getPlayerRealX, getPlayerRealY, getGameCycle, getEnergy, doActionMethod;
    private static Field   invField;

    // ── Subsystems ───────────────────────────────────────────────────────────
    private static TickEngine   tickEngine;
    private static CombatScript combatScript;
    private static StateReader  stateReader;
    private static LooterScript looter;
    private static String       shmPath;

    // ════════════════════════════════════════════════════════════════════════
    //  Entry points — java.lang.instrument
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Load-time instrumentation (-javaagent). Same init path as Dynamic Attach.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        init(agentArgs, inst, false);
    }

    /**
     * Runtime Dynamic Attach entry ({@code VirtualMachine.loadAgent}).
     * Must remain public/static with this signature for the Attach API.
     * Work is offloaded to a daemon thread so host threads keep running.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        init(agentArgs, inst, true);
    }

    /**
     * Shared attach bootstrap. First attach starts subsystems; later attaches
     * re-check the license and bring the HUD back if it was closed or missed.
     *
     * @param agentArgs agent options string (may be null)
     * @param inst      JVM instrumentation handle (required)
     * @param dynamic   true when entered via {@link #agentmain}
     */
    private static void init(String agentArgs, Instrumentation inst, boolean dynamic) {
        if (inst == null) {
            return;
        }

        AgentOptions opts = AgentOptions.parse(agentArgs);
        openFileLog(opts.logPath);

        // License before INITIALIZED — a refused key must not permanently
        // poison this JVM so later Attach now clicks are ignored.
        if (!LicenseGate.allow(opts.licenseToken)) {
            AttachStatus.write(AttachStatus.LICENSE_DENIED, "invalid or missing token");
            error("license invalid or missing; agent not starting");
            return;
        }

        if (!INITIALIZED.compareAndSet(false, true)) {
            log("[attach] already initialized; refreshing HUD (" + Product.VERSION + ")");
            AttachStatus.write(AttachStatus.REATTACH, "refreshing HUD");
            if (combatScript != null) {
                try {
                    OverlayUI.show(combatScript);
                    AttachStatus.write(AttachStatus.OK, "hud refreshed " + Product.VERSION);
                } catch (Throwable t) {
                    AttachStatus.write(AttachStatus.HUD_FAILED, t.getMessage());
                    error("[OverlayUI] refresh failed: " + t.getMessage());
                }
            } else if (agentReady) {
                AttachStatus.write(AttachStatus.NO_COMBAT,
                        "bootstrap finished without combat script — restart Roat via Play");
            } else {
                AttachStatus.write(AttachStatus.BOOTSTRAPPING, "still starting");
            }
            return;
        }

        instrumentation = inst;
        AttachStatus.write(AttachStatus.STARTING,
                dynamic ? "agentmain/dynamic-attach" : "premain");
        log("=== starting (" + (dynamic ? "agentmain/dynamic-attach" : "premain")
                + ") " + Product.VERSION + " ===");

        // 1) Append external plugin JARs to the system class-loader search path.
        //    Must run on this thread before any plugin classes are referenced.
        loadPluginPackages(inst, opts.pluginJars);

        // 2) Optional early transformers (best-effort; never fail attach).
        try {
            HardcodedCombatAgent.install(inst);
        } catch (Throwable t) {
            log("[attach] HardcodedCombatAgent.install failed: " + t.getMessage());
        }

        // 3) Application bootstrap on a daemon thread — do not block the
        //    Attach API caller or interrupt existing application threads.
        Thread bootstrap = new Thread(FontManager::bootstrap, threadName("bootstrap"));
        bootstrap.setDaemon(true);
        bootstrap.setContextClassLoader(ClassLoader.getSystemClassLoader());
        bootstrap.start();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Plugin JAR loading (appendToSystemClassLoaderSearch)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Appends each plugin JAR to the system class loader search path so its
     * classes become visible to the host application without replacing the
     * application class loader or stopping running threads.
     */
    private static void loadPluginPackages(Instrumentation inst, List<String> jarPaths) {
        if (jarPaths == null || jarPaths.isEmpty()) {
            log("[plugins] no external plugin JARs configured");
            return;
        }
        log("[plugins] loading " + jarPaths.size() + " package(s)");
        for (String raw : jarPaths) {
            appendPluginJar(inst, raw);
        }
        log("[plugins] successfully appended " + LOADED_PLUGIN_JARS.size()
                + "/" + jarPaths.size() + " JAR(s)");
    }

    /**
     * Single-JAR append with isolated failure handling. One bad path must not
     * abort the rest of the attach sequence.
     */
    private static void appendPluginJar(Instrumentation inst, String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) return;
        File jarFile = new File(rawPath.trim()).getAbsoluteFile();
        String abs = jarFile.getAbsolutePath();

        if (!jarFile.isFile()) {
            log("[plugins] SKIP (not a file): " + abs);
            return;
        }
        if (!abs.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            log("[plugins] SKIP (not a .jar): " + abs);
            return;
        }
        if (LOADED_PLUGIN_JARS.contains(abs)) {
            log("[plugins] SKIP (already loaded): " + abs);
            return;
        }

        JarFile jar = null;
        boolean appended = false;
        try {
            // Validate ZIP/JAR structure before handing to the Instrumentation API.
            jar = new JarFile(jarFile, true /* verify */);
            inst.appendToSystemClassLoaderSearch(jar);
            appended = true;
            // Per Instrumentation contract: do NOT close the JarFile after a
            // successful append — the JVM must keep reading from it for life.
            LOADED_PLUGIN_JARS.add(abs);
            log("[plugins] appended to system class loader: " + abs);
            // Hook: optional PluginBootstrap.onAttach(Instrumentation) in the
            // loaded package — fire-and-forget on a daemon thread.
            invokePluginAttachHook(abs, inst);
        } catch (UnsupportedOperationException e) {
            log("[plugins] FAIL appendToSystemClassLoaderSearch unsupported: " + abs
                    + " — " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown when the JAR is not well-formed or path is invalid, etc.
            log("[plugins] FAIL illegal argument for " + abs + " — " + e.getMessage());
        } catch (SecurityException e) {
            log("[plugins] FAIL security denied appending " + abs + " — " + e.getMessage());
        } catch (IOException e) {
            log("[plugins] FAIL I/O reading " + abs + " — " + e.getMessage());
        } catch (Throwable t) {
            log("[plugins] FAIL unexpected for " + abs + " — "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (!appended && jar != null) {
                try { jar.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Structural hook: if a loaded JAR exposes
     * {@code com.sun.java.fontmgr.plugin.PluginBootstrap#onAttach(Instrumentation)},
     * invoke it asynchronously so plugin init cannot stall Dynamic Attach.
     */
    private static void invokePluginAttachHook(String jarPath, Instrumentation inst) {
        Thread hook = new Thread(() -> {
            try {
                Class<?> hookClass = ClassLoader.getSystemClassLoader()
                        .loadClass("com.sun.java.fontmgr.plugin.PluginBootstrap");
                Method onAttach = hookClass.getMethod("onAttach", Instrumentation.class);
                onAttach.invoke(null, inst);
                log("[plugins] PluginBootstrap.onAttach OK (" + jarPath + ")");
            } catch (ClassNotFoundException e) {
                // Expected when the JAR is a plain library with no bootstrap hook.
                debug("[plugins] no PluginBootstrap in " + jarPath);
            } catch (Throwable t) {
                log("[plugins] PluginBootstrap.onAttach failed for " + jarPath
                        + ": " + t.getMessage());
            }
        }, threadName("plugin-hook"));
        hook.setDaemon(true);
        hook.start();
    }

    /** Absolute paths of plugin JARs currently on the system class-loader search path. */
    public static List<String> getLoadedPluginJars() {
        return Collections.unmodifiableList(new ArrayList<>(LOADED_PLUGIN_JARS));
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Agent argument parsing
    // ════════════════════════════════════════════════════════════════════════

    /** Parsed agent options. Immutable after construction. */
    private static final class AgentOptions {
        final String logPath;
        final String licenseToken;
        final List<String> pluginJars;

        AgentOptions(String logPath, String licenseToken, List<String> pluginJars) {
            this.logPath = logPath;
            this.licenseToken = licenseToken;
            this.pluginJars = Collections.unmodifiableList(new ArrayList<>(pluginJars));
        }

        static AgentOptions parse(String agentArgs) {
            List<String> plugins = new ArrayList<>();
            String logPath = null;
            String licenseToken = System.getProperty("fontmgr.license");

            // System properties first (survive empty attach args).
            addJarTokens(plugins, System.getProperty("fontmgr.plugins"));
            addJarTokens(plugins, System.getProperty("agent.plugins"));

            if (agentArgs != null && !agentArgs.trim().isEmpty()) {
                String trimmed = agentArgs.trim();
                // Backward compat: entire arg is a single .jar or a log file path.
                if (!trimmed.contains("=") && !trimmed.contains(",") && !trimmed.contains(";")) {
                    if (trimmed.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        plugins.add(trimmed);
                    } else {
                        logPath = trimmed;
                    }
                } else {
                    for (String token : trimmed.split("[,;]")) {
                        String t = token.trim();
                        if (t.isEmpty()) continue;
                        int eq = t.indexOf('=');
                        if (eq < 0) {
                            if (t.toLowerCase(Locale.ROOT).endsWith(".jar")) plugins.add(t);
                            else if (logPath == null) logPath = t;
                            continue;
                        }
                        String key = t.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                        String val = t.substring(eq + 1).trim();
                        if (val.isEmpty()) continue;
                        switch (key) {
                            case "plugins":
                            case "plugin":
                            case "jar":
                            case "jars":
                                addJarTokens(plugins, val);
                                break;
                            case "log":
                            case "logfile":
                            case "logpath":
                                logPath = val;
                                break;
                            case "license":
                            case "token":
                                licenseToken = val;
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
            return new AgentOptions(logPath, licenseToken, plugins);
        }

        private static void addJarTokens(List<String> out, String csv) {
            if (csv == null || csv.trim().isEmpty()) return;
            for (String p : csv.split("[,;]")) {
                String s = p.trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
    }

    private static void openFileLog(String configuredPath) {
        try {
            // Opt-in log (off by default) beside the agent JAR / temp copy.
            String lp = (configuredPath != null && !configuredPath.isEmpty())
                    ? configuredPath
                    : Stealth.cacheFile("log").toString();
            if (!loggingEnabled()) return;
            File parent = new File(lp).getParentFile();
            if (parent != null) parent.mkdirs();
            log = new PrintWriter(new FileWriter(lp, true), true);
        } catch (Exception e) {
            // File logging is optional; never fail attach for it.
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Bootstrap
    // ════════════════════════════════════════════════════════════════════════

    private static void bootstrap() {
        try {
            // 1. Find client
            for (int i = 0; i < 30 && clientInstance == null; i++) {
                clientInstance = scanForClient();
                if (clientInstance == null)
                    Thread.sleep(1000);
            }
            if (clientInstance == null) {
                AttachStatus.write(AttachStatus.CLIENT_MISSING,
                        "no Client class in this JVM — close Roat and press Play in Roatz");
                error("FATAL: client not found");
                return;
            }
            clientClass = clientInstance.getClass();
            log("Client: " + clientClass.getName());

            // 2. Reflected fields
            currentSkillLevel   = getField("currentSkillLevel");
            playerSpecialEnergy = getField("playerSpecialEnergy");
            getPlayerRealX      = getMethod("getPlayerRealX");
            getPlayerRealY      = getMethod("getPlayerRealY");
            getGameCycle        = getMethod("getGameCycle");
            getEnergy           = getMethod("getEnergy");

            // Report anything missing once, so a client update shows up as a
            // readable list instead of "the bot does nothing".
            if (currentSkillLevel   == null) missing("Client.currentSkillLevel");
            if (playerSpecialEnergy == null) missing("Client.playerSpecialEnergy");
            if (getPlayerRealX      == null) missing("Client.getPlayerRealX()");
            if (getPlayerRealY      == null) missing("Client.getPlayerRealY()");
            if (getGameCycle        == null) missing("Client.getGameCycle()");
            if (getEnergy           == null) missing("Client.getEnergy()");

            doActionMethod = RtLookup.doAction(clientClass);
            log("doAction=" + (doActionMethod != null));
            if (doActionMethod == null) missing("Client.doAction(...)");

            // 3. SharedMemory
            SharedMemory.init();
            shmPath = SharedMemory.getShmPath();

            // 4. TickEngine
            try {
                tickEngine = new TickEngine(clientClass, clientInstance);
                tickEngine.start();
            } catch (Exception e) {
                error("[TickEngine] Failed: " + e.getMessage()
                        + " — the agent will not tick (client fields may have been renamed)");
            }

            // 5. CombatScript
            if (doActionMethod != null) {
                try {
                    combatScript = new CombatScript(clientInstance, clientClass, doActionMethod);
                    if (tickEngine != null) tickEngine.addListener(combatScript);
                } catch (Exception e) {
                    log("[CombatScript] Failed: " + e.getMessage());
                }
            }

            // 6. StateReader
            try {
                stateReader = new StateReader(
                        clientInstance,
                        findPublicMethod("getLocalPlayer"),
                        findPublicMethod("getBoostedSkillLevel"),
                        getEnergy, getGameCycle,
                        findPublicMethod("getVarbit"),
                        findPublicMethod("getItemContainer"));
                if (combatScript != null) combatScript.stateReader = stateReader;
            } catch (Exception e) {
                log("[StateReader] Warning: " + e.getMessage());
            }

            // 6b. Wild looter (second tick listener; PK-point/ground-item farmer)
            if (clientInstance != null) {
                try {
                    looter = new LooterScript(clientInstance);
                    if (tickEngine != null) tickEngine.addListener(looter);
                    log("[Looter] ready: " + (looter.cfg.configured()
                            ? looter.cfg.describe() : "not configured (record zone+bank)"));
                } catch (Exception e) {
                    log("[Looter] Failed: " + e.getMessage());
                }
            }

            // 7. OverlayUI (EDT) — consolidated always-on-top HUD
            if (combatScript != null) {
                try {
                    HotkeyManager.get().init(combatScript);
                    PauseManager.get().init(combatScript);
                } catch (Exception e) {
                    log("[Hotkey] Failed: " + e.getMessage());
                }
                javax.swing.SwingUtilities.invokeLater(() -> {
                    try {
                        OverlayUI.show(combatScript);
                        AttachStatus.write(AttachStatus.OK, "hud shown " + Product.VERSION);
                    } catch (Exception e) {
                        AttachStatus.write(AttachStatus.HUD_FAILED, e.getMessage());
                        log("[OverlayUI] Failed: " + e.getMessage());
                    }
                });
            } else {
                AttachStatus.write(AttachStatus.NO_COMBAT,
                        "doAction/CombatScript unavailable — client may need an update");
            }

            // 8. Command socket — off unless explicitly enabled (port banner is a tell)
            if (Boolean.getBoolean("agent.cmd")) startCommandSocket();
            agentReady = true;
            log("ready");

        } catch (Throwable t) {
            AttachStatus.write(AttachStatus.HUD_FAILED,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            error("FATAL bootstrap: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static LooterScript looter() { return looter; }

    /** Live combat script — null before bootstrap finishes. */
    public static CombatScript combatScript() { return combatScript; }

    // ════════════════════════════════════════════════════════════════════════
    //  Command socket
    // ════════════════════════════════════════════════════════════════════════

    private static void startCommandSocket() {
        Thread t = new Thread(() -> {
            try (ServerSocket srv = new ServerSocket(CMD_PORT, 5, InetAddress.getByName("127.0.0.1"))) {
                while (running) {
                    try {
                        Socket s = srv.accept();
                        s.setTcpNoDelay(true);
                        startWorker(() -> handle(s)).start();
                    } catch (IOException ignored) {}
                }
            } catch (IOException e) {
                warn("[cmd] socket unavailable on 127.0.0.1:" + CMD_PORT + " — " + e.getMessage());
            }
        }, threadName("socket"));
        t.setDaemon(true);
        t.start();
    }

    /** Human-readable, stable thread names ("agent-tick", "agent-socket", ...). */
    static String threadName(String role) {
        return "agent-" + role;
    }

    private static Thread startWorker(Runnable task) {
        Thread t = new Thread(task, threadName("socket-worker"));
        t.setDaemon(true);
        return t;
    }

    private static final String REQUIRED_TOKEN = System.getProperty("agent.cmd.token");

    private static void handle(Socket sock) {
        try (BufferedReader in  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter    out = new PrintWriter(sock.getOutputStream(), true)) {
            out.println("READY|version=8|shm=" + shmPath
                    + (REQUIRED_TOKEN != null ? "|auth=required" : ""));
            boolean authed = REQUIRED_TOKEN == null;
            String line;
            while ((line = in.readLine()) != null) {
                String r;
                if (!authed) {
                    // Optional hardening: when -Dagent.cmd.token is set, every
                    // local process must authenticate before issuing commands.
                    String[] p = line.trim().split("\\|", 2);
                    if (p.length == 2 && "AUTH".equalsIgnoreCase(p[0]) && REQUIRED_TOKEN.equals(p[1])) {
                        authed = true;
                        r = "AUTH|ok";
                    } else {
                        r = "ERROR|auth required";
                    }
                } else {
                    r = process(line.trim());
                }
                out.println(r);
                if (r.startsWith("BYE")) break;
            }
        } catch (IOException ignored) {
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private static String process(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "ERROR|empty";
        String[] p = cmd.split("\\|", 3);
        switch (p[0].toUpperCase()) {
            case "PING":   return agentReady ? "PONG|ready=1" : "PONG|ready=0";
            case "STATE":  return buildState();
            case "TICK":   return "TICK|" + (tickEngine != null ? tickEngine.getLastTick() : -1);
            case "SCRIPT": return handleScript(p);
            case "LOOTER": return handleLooter(p);
            case "LOG":    return "LOG|" + String.join(" ;; ", recentLog(30));
            case "BYE":    return "BYE";
            default:       return "ERROR|unknown:" + p[0];
        }
    }

    private static String handleScript(String[] p) {
        if (combatScript == null) return "ERROR|no CombatScript";
        if (p.length < 2)        return "ERROR|need subcommand";
        switch (p[1].toUpperCase()) {
            case "ENABLE":  combatScript.actions().setMasterEnabled(true);  return "SCRIPT|enabled";
            case "DISABLE": combatScript.actions().setMasterEnabled(false); return "SCRIPT|disabled";
            case "SPEC":    combatScript.actions().fireSpec(); return "SCRIPT|spec_fired";
            case "STATUS":
                // One snapshot read: no combat field below can come from another tick.
                CombatState st = combatScript.state();
                CombatActions act = combatScript.actions();
                return "SCRIPT_STATUS|enabled=" + act.masterEnabled()
                        + "|tick=" + st.tick
                        + "|autoEat=" + act.autoEatEnabled()
                        + (Stealth.showOverlayDetail()
                                ? "|target=" + st.targetName
                                + "|anim=" + st.lastTargetAnim
                                + "|hit=" + st.lastHitsplatDmg
                                + "|spec=" + st.specEnergy
                                + "|lastAction=" + st.lastAction
                                + "|warn=" + FontManager.lastWarning()
                                : "");
            default: return "ERROR|unknown SCRIPT sub: " + p[1];
        }
    }

    private static String handleLooter(String[] p) {
        if (looter == null) return "ERROR|no LooterScript";
        if (p.length < 2) {
            return "LOOTER_STATUS|" + looter.status();
        }
        switch (p[1].toUpperCase()) {
            case "ENABLE":  looter.setEnabled(true);  return "LOOTER|enabled";
            case "DISABLE": looter.setEnabled(false); return "LOOTER|disabled";
            case "STATUS":  return "LOOTER_STATUS|" + looter.status();
            case "CONFIG":  return "LOOTER_CFG|" + looter.config();
            case "RECORD":
                if (p.length < 3) return "ERROR|RECORD needs ZONE|BANK";
                if ("ZONE".equalsIgnoreCase(p[2])) return "LOOTER|" + looter.recordZone();
                if ("BANK".equalsIgnoreCase(p[2])) return "LOOTER|" + looter.recordBank();
                return "ERROR|RECORD ZONE|BANK";
            case "SAVE":   return "LOOTER|" + looter.saveCfg();
            default: return "ERROR|unknown LOOTER sub: " + p[1];
        }
    }

    private static String buildState() {
        try {
            int hp     = readSkill(3);
            int pray   = readSkill(5);
            int spec   = playerSpecialEnergy != null ? playerSpecialEnergy.getInt(clientInstance) : -1;
            int tick   = getGameCycle        != null ? (int) getGameCycle.invoke(clientInstance)  : -1;
            int energy = getEnergy           != null ? (int) getEnergy.invoke(clientInstance)     : -1;
            int x      = getPlayerRealX      != null ? (int) getPlayerRealX.invoke(clientInstance): -1;
            int y      = getPlayerRealY      != null ? (int) getPlayerRealY.invoke(clientInstance): -1;
            return "STATE|hp=" + hp + "|pray=" + pray + "|spec=" + spec
                    + "|tick=" + tick + "|energy=" + energy + "|pos=" + x + "," + y;
        } catch (Exception e) {
            return "STATE|error=" + e.getMessage();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Client scan
    // ════════════════════════════════════════════════════════════════════════

    private static Object scanForClient() {
        Class<?> best = null; int bestScore = 0;
        for (Class<?> cls : instrumentation.getAllLoadedClasses()) {
            String n = cls.getName();
            if (n.startsWith("java.") || n.startsWith("javax.")
                    || n.startsWith("jdk.") || n.startsWith("sun.")
                    || n.startsWith("com.sun.")) continue;
            // Cheap pre-filter first: getName() is a field read, getSimpleName()
            // is not (it can trigger loading/allocating for the enclosing class).
            // This loop runs over every loaded class once a second while it hunts
            // for the client, and the client is ~1 class in thousands. Scoring
            // matches on the same keywords, so nothing that could score is
            // skipped here.
            String lower = n.toLowerCase(java.util.Locale.ROOT);
            if (lower.indexOf("client") < 0 && lower.indexOf("game") < 0
                    && lower.indexOf("roat") < 0) continue;
            try {
                int s = score(cls);
                if (s > bestScore) { bestScore = s; best = cls; }
            } catch (Throwable ignored) {}
        }
        if (best != null && bestScore >= 30) return resolveInstance(best);
        return null;
    }

    private static int score(Class<?> cls) {
        int s = 0;
        String n = cls.getSimpleName().toLowerCase();
        if (n.equals("client"))      s += 50;
        else if (n.contains("client")) s += 30;
        if (n.contains("game"))      s += 10;
        if (n.contains("roat"))      s += 20;
        if (cls.isEnum())            s /= 4;
        if (cls.isInterface())       s /= 2;
        return s;
    }

    private static Object resolveInstance(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && cls.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) return v;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Reflection helpers
    // ════════════════════════════════════════════════════════════════════════

    private static Field getField(String name) {
        try { Field f = clientClass.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (Exception e) { return null; }
    }

    private static Method getMethod(String name) {
        try { return clientClass.getMethod(name); } catch (Exception e) { return null; }
    }

    private static Method findPublicMethod(String name) {
        for (Method m : clientClass.getMethods())
            if (m.getName().equals(name)) return m;
        return null;
    }

    private static int readSkill(int idx) {
        try {
            if (currentSkillLevel != null) {
                int[] s = (int[]) currentSkillLevel.get(clientInstance);
                if (s != null && idx < s.length) return s[idx];
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
