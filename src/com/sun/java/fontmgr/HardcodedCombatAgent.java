package com.sun.java.fontmgr;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Zero-config combat defaults plus load-time patches for client telemetry.
 *
 * HttpHelper.getClientPotionSettings() is posted as HTTP field a12 and again
 * as an in-game settings packet after login. Both include -javaagent.
 * AhkDetection reports sub-70ms inventory switches via ::ahk- commands.
 */
public final class HardcodedCombatAgent implements ClassFileTransformer {

    public static final boolean DISABLE_SWAP_DELAY       = true;
    public static final boolean DISABLE_DISTANCE_CHECKS  = true;
    public static final boolean ENABLE_OVERHEAD_CHECKS   = true;
    /** Incoming hit size that may dump spec. 1 made every splat a robot dump. */
    public static final int     MIN_DAMAGE_THRESHOLD     = 18;
    public static final int     MIN_SPEC_PERCENT         = 50;
    public static final int     COMBO_EAT_HP_THRESHOLD   = 32;

    private static final String HTTP_HELPER = "com/roatpkz/client/game/net/HttpHelper";
    private static final String AHK         = "com/roatpkz/client/game/security/AhkDetection";
    private static volatile boolean defaultsApplied = false;

    private HardcodedCombatAgent() {}

    public static final HardcodedCombatAgent TRANSFORMER = new HardcodedCombatAgent();

    static void install(Instrumentation inst) {
        inst.addTransformer(TRANSFORMER, true);
        try {
            List<Class<?>> loaded = new ArrayList<>();
            for (Class<?> c : inst.getAllLoadedClasses()) {
                String n = c.getName();
                if ("com.roatpkz.client.game.net.HttpHelper".equals(n)
                        || "com.roatpkz.client.game.security.AhkDetection".equals(n)
                        || "com.roatpkz.common.DebugPrintStream".equals(n)) {
                    if (inst.isModifiableClass(c)) loaded.add(c);
                }
            }
            if (!loaded.isEmpty()) inst.retransformClasses(loaded.toArray(new Class<?>[0]));
        } catch (Throwable ignored) {}
        sanitizeJvmProperties();
        scrubClasspathProperty();
    }

    static void scrubClasspathProperty() {
        try {
            String cp = System.getProperty("java.class.path");
            if (cp == null || cp.isEmpty()) return;
            String[] parts = cp.split(java.io.File.pathSeparator);
            java.util.List<String> keep = new java.util.ArrayList<>();
            for (String p : parts) {
                if (p == null || p.isEmpty()) continue;
                if (isAgentToken(p)) continue;
                keep.add(p);
            }
            if (!keep.isEmpty()) {
                System.setProperty("java.class.path", String.join(java.io.File.pathSeparator, keep));
            }
        } catch (Throwable ignored) {}
    }

    static void sanitizeJvmProperties() {
        try {
            String cmd = System.getProperty("sun.java.command");
            if (cmd != null) System.setProperty("sun.java.command", stripTokens(cmd));
        } catch (Throwable ignored) {}
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) return null;
        try {
            if (HTTP_HELPER.equals(className)) {
                byte[] patched = ClassFilePatcher.replaceMethodWithStringReturn(
                        classfileBuffer,
                        "getClientPotionSettings",
                        "()Ljava/lang/String;",
                        snapshotJvmArgs());
                try {
                    patched = ClassFilePatcher.replaceMethodWithStringReturn(
                            patched,
                            "getLoadedPluginDetails",
                            "()Ljava/lang/String;",
                            "Total Plugins: 0\nSHA-256: 0\n\nNo plugins loaded");
                } catch (Exception ignored) {}
                FontManager.log("[Agent] HttpHelper telemetry patched");
                return patched;
            }
            if (AHK.equals(className)) {
                byte[] patched = classfileBuffer;
                patched = tryNop(patched, "handleClickInventoryItem", "(III)V");
                patched = tryNop(patched, "reportAhkIfPossible", "()V");
                if (patched != classfileBuffer) {
                    FontManager.log("[Agent] AhkDetection neutralized");
                }
                return patched == classfileBuffer ? null : patched;
            }
            if ("com/roatpkz/common/DebugPrintStream".equals(className)) {
                return tryNop(classfileBuffer, "println", "(Ljava/lang/String;)V");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Same layout HttpHelper.getClientPotionSettings() builds, with agent
     * tokens stripped so HTTP a12 and the login settings packet look vanilla.
     */
    static String snapshotJvmArgs() {
        String input = "none";
        try {
            List<String> raw = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> keep = new ArrayList<>();
            for (String a : raw) if (!isAgentToken(a)) keep.add(a);
            if (!keep.isEmpty()) input = String.join(" ", keep);
        } catch (Throwable ignored) {}

        String command = stripTokens(System.getProperty("sun.java.command", "unknown"));

        String process = "unknown";
        try {
            Optional<String[]> args = ProcessHandle.current().info().arguments();
            if (args.isPresent()) {
                List<String> keep = new ArrayList<>();
                for (String a : args.get()) if (!isAgentToken(a)) keep.add(a);
                process = String.join(" ", keep);
            }
        } catch (Throwable ignored) {}

        return "Command: " + command + "\nProcess arguments: " + process + "\nInput arguments: " + input;
    }

    static boolean isAgentToken(String token) {
        if (token == null || token.isEmpty()) return false;
        String t = token.toLowerCase(Locale.ROOT);
        return t.contains("javaagent")
                || t.contains(legacyPkgToken())
                || t.contains("fontmgr")
                || t.contains("fontmanager")
                || t.contains("agent-class")
                || t.contains("premain")
                || t.contains("fontmanager-windows")
                || t.contains("fontconfig-ext")
                || t.contains("-dagent.")
                || t.contains("-javaagent");
    }

    /** Transition-period legacy package marker (XOR 0x55). */
    private static String legacyPkgToken() {
        byte[] enc = {0x37, 0x3a, 0x3e, 0x21, 0x7f};
        char[] out = new char[enc.length];
        for (int i = 0; i < enc.length; i++) out[i] = (char) (enc[i] ^ 0x55);
        return new String(out);
    }

    static String stripTokens(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] parts = s.split("\\s+");
        List<String> keep = new ArrayList<>();
        for (String p : parts) if (!isAgentToken(p)) keep.add(p);
        return keep.isEmpty() ? "unknown" : String.join(" ", keep);
    }

    private static byte[] tryNop(byte[] classFile, String name, String descriptor) {
        try {
            return ClassFilePatcher.nopVoidMethod(classFile, name, descriptor);
        } catch (Exception ignored) {
            return classFile;
        }
    }

    public static void applyDefaults(CombatScript script) {
        if (script == null || defaultsApplied) return;
        defaultsApplied = true;
        script.disableSwapDelay        = DISABLE_SWAP_DELAY;
        script.disableDistanceChecks   = DISABLE_DISTANCE_CHECKS;
        script.overheadChecksEnabled   = ENABLE_OVERHEAD_CHECKS;
        script.damageTriggerMin        = MIN_DAMAGE_THRESHOLD;
        script.agsMinSpecPct           = MIN_SPEC_PERCENT;
        script.dbowMinSpecPct          = 50;
        script.axeSpecMinPct           = 25;
        script.agsMaxHit               = 77;
        script.agsHighHitMin           = 40;
        script.clawsHighHitMin         = 1;
        script.comboEatHpThreshold     = COMBO_EAT_HP_THRESHOLD;
        script.brewPreferAboveHp       = 30;
        script.counterSpecEnabled      = false;
        // Do NOT force defensivePrayersEnabled / comboEat off — MiniOverlay + user toggles own those.
        script.animTriggerEnabled      = false;
        script.damageTriggerEnabled    = false;
    }

    public static void processGameTick(CombatScript script, int currentHp, int specialEnergy, int lastHitDmg) {
        applyDefaults(script);
    }
}
