package com.sun.java.fontmgr;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verification harness for the ASM class-file patcher and for both ways the
 * agent is deployed.
 *
 * <p><b>Why this exists.</b> The previous hand-rolled rewriter dropped each
 * method's exception table and StackMapTable, so every patched method failed to
 * load with {@code VerifyError: Expecting a stackmap frame at branch target N}.
 * The failure was swallowed by {@code catch (Throwable ignored)} in the
 * transformer, so the MouseHandler hooks silently never installed. Nothing in
 * the old workflow would have noticed: the patcher "succeeded", the bytes
 * changed, and only the JVM's linker knew better.
 *
 * <p>So the harness asserts at the level the JVM actually cares about:
 *
 * <ol>
 *   <li><b>Real client classes.</b> The four classes {@link HardcodedCombatAgent}
 *       patches are read straight out of {@code game.jar}, patched, then defined
 *       in a fresh class loader whose parent sees {@code game.jar} and the agent
 *       JAR, and force-linked with {@code ClassLoader.resolveClass}. A
 *       {@code VerifyError} fails the link, i.e. it fails this harness. The
 *       unpatched classes are linked first as a control, so a failure is
 *       attributable to the patch. Bytecode-level checks then assert the hook is
 *       the FIRST instruction and that exception tables and StackMapTables
 *       survived - in the patched bytes themselves.</li>
 *   <li><b>{@code -javaagent} (premain).</b> A child JVM loads stub classes
 *       bearing the four real names (each with branches, a switch and
 *       try/catch) and a capture transformer registered after the agent's own
 *       reports the bytes the JVM really loaded. Those bytes are checked the
 *       same way.</li>
 *   <li><b>Dynamic Attach.</b> A child JVM loads the stubs <em>first</em>, the
 *       harness attaches the agent with {@code tools/AttachLoader}, and the
 *       probe then retransforms and re-links the stubs itself, and defines a
 *       second copy in a fresh loader, so both the retransform path and the
 *       transformer registered by {@code agentmain} are exercised.</li>
 * </ol>
 *
 * <p>Run with {@code .\gradlew.bat verify}.
 */
public final class VerifyHarness {

    private static final String MOUSE = "com/roatpkz/client/game/engine/impl/MouseHandler";
    private static final String HTTP  = "com/roatpkz/client/game/net/HttpHelper";
    private static final String AHK   = "com/roatpkz/client/game/security/AhkDetection";
    private static final String DEBUG = "com/roatpkz/common/DebugPrintStream";
    private static final String[] ALL = { MOUSE, HTTP, AHK, DEBUG };

    private static final String MOUSE_EVENT = "(Ljava/awt/event/MouseEvent;)V";
    private static final String STRING_VOID = "(Ljava/lang/String;)V";
    private static final String STRING_RET  = "()Ljava/lang/String;";
    private static final String TRIPLE_VOID = "(III)V";
    private static final String VOID_VOID   = "()V";
    private static final String INT_INT     = "(I)I";
    private static final String HOOKS       = "com/sun/java/fontmgr/ClientHooks";

    private VerifyHarness() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("usage: VerifyHarness <game.jar> <agent.jar> <stub-classes> "
                    + "<harness-classes> <attach-classes>");
            System.exit(2);
        }
        File gameJar = new File(args[0]);
        File agentJar = new File(args[1]);
        File stubClasses = new File(args[2]);
        File harnessClasses = new File(args[3]);
        File attachClasses = new File(args[4]);

        System.out.println("== phase 1: real client classes out of " + gameJar.getName()
                + " - patch, define, force link ==");
        Checks c = new Checks("game.jar patch + link");
        phaseRealJar(c, gameJar, agentJar);
        c.report();

        System.out.println("== phase 1b: same classes, verified by the JVM in a throwaway child ==");
        boolean realJar = runRealJarChild(gameJar, agentJar, stubClasses, harnessClasses);

        System.out.println("== phase 2: -javaagent premain run against stubs with branches/switch/try-catch ==");
        boolean premain = runPremain(stubClasses, harnessClasses, agentJar);

        System.out.println("== phase 3: Dynamic Attach run against the same stubs ==");
        boolean attach = runAttach(stubClasses, harnessClasses, attachClasses, agentJar);

        boolean ok = c.ok() && realJar && premain && attach;
        System.out.println(ok ? "HARNESS: PASS" : "HARNESS: FAIL");
        System.exit(ok ? 0 : 1);
    }

    // -- phase 1 -------------------------------------------------------------

    private static void phaseRealJar(Checks c, File gameJar, File agentJar) throws Exception {
        final Map<String, byte[]> orig = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(gameJar)) {
            for (String n : ALL) {
                ZipEntry e = zip.getEntry(n + ".class");
                c.that("game.jar contains " + n, e != null);
                if (e != null) orig.put(n, readEntry(zip, e));
            }
        }
        if (orig.size() != ALL.length) return;

        for (String n : ALL) {
            c.equal("major version of " + n + " (55 = stack map frames are mandatory)",
                    55, ByteInspector.majorVersion(orig.get(n)));
        }

        ClassLoader parent = new URLClassLoader(
                new URL[] { gameJar.toURI().toURL(), agentJar.toURI().toURL() },
                VerifyHarness.class.getClassLoader());

        // Control first: the unpatched classes must link with this loader setup,
        // otherwise a failure below would say nothing about the patch.
        link(c, new GameLoader(parent, orig), orig, "control (unpatched)");



        Map<String, byte[]> patched = new LinkedHashMap<>();
        for (String n : ALL) {
            byte[] out = HardcodedCombatAgent.TRANSFORMER.transform(null, n, null, null, orig.get(n));
            patched.put(n, out == null ? orig.get(n) : out);
        }

        byte[] m0 = orig.get(MOUSE);
        byte[] m1 = patched.get(MOUSE);
        c.that("MouseHandler was patched", !Arrays.equals(m0, m1));
        c.that("hook is the FIRST instruction of mousePressed",
                ByteInspector.startsWithInvokeStatic(m1, "mousePressed", MOUSE_EVENT,
                        HOOKS, "onMousePressed", VOID_VOID));
        c.that("hook is the FIRST instruction of mouseMoved",
                ByteInspector.startsWithInvokeStatic(m1, "mouseMoved", MOUSE_EVENT,
                        HOOKS, "onMouseMoved", VOID_VOID));
        c.that("hook is the FIRST instruction of mouseDragged",
                ByteInspector.startsWithInvokeStatic(m1, "mouseDragged", MOUSE_EVENT,
                        HOOKS, "onMouseMoved", VOID_VOID));
        for (String m : new String[] { "mousePressed", "mouseMoved", "mouseDragged", "mouseReleased" }) {
            int tb = ByteInspector.tryCatchBlocks(m0, m, MOUSE_EVENT);
            int ta = ByteInspector.tryCatchBlocks(m1, m, MOUSE_EVENT);
            c.that("exception table survives on " + m + " [" + tb + " entries]", tb > 0 && tb == ta);
            c.equal("caught types survive on " + m,
                    ByteInspector.tryCatchTypes(m0, m, MOUSE_EVENT).toString(),
                    ByteInspector.tryCatchTypes(m1, m, MOUSE_EVENT).toString());
            int fb = ByteInspector.frames(m0, m, MOUSE_EVENT);
            int fa = ByteInspector.frames(m1, m, MOUSE_EVENT);
            c.that("StackMapTable survives on " + m + " [" + fb + " -> " + fa + "]", fa >= fb && fa > 0);
        }
        assertOtherMethodsUntouched(c, "MouseHandler", m0, m1, new String[] {
                "mousePressed(Ljava/awt/event/MouseEvent;)V",
                "mouseMoved(Ljava/awt/event/MouseEvent;)V",
                "mouseDragged(Ljava/awt/event/MouseEvent;)V" });
        c.that("re-patching MouseHandler is a no-op (idempotent)",
                HardcodedCombatAgent.TRANSFORMER.transform(null, MOUSE, null, null, m1) == null);

        byte[] h0 = orig.get(HTTP);
        byte[] h1 = patched.get(HTTP);
        c.that("HttpHelper was patched", !Arrays.equals(h0, h1));
        String potion = ByteInspector.firstInstruction(h1, "getClientPotionSettings", STRING_RET);
        c.that("getClientPotionSettings returns the scrubbed agent string [" + potion + "]",
                potion != null && potion.startsWith("LDC Command:"));
        assertOtherMethodsUntouched(c, "HttpHelper", h0, h1, new String[] {
                "getClientPotionSettings()Ljava/lang/String;",
                "getLoadedPluginDetails()Ljava/lang/String;" });

        byte[] a0 = orig.get(AHK);
        byte[] a1 = patched.get(AHK);
        c.that("AhkDetection was patched", !Arrays.equals(a0, a1));
        c.equal("handleClickInventoryItem neutralized",
                "RETURN", ByteInspector.firstInstruction(a1, "handleClickInventoryItem", TRIPLE_VOID));
        c.equal("reportAhkIfPossible neutralized",
                "RETURN", ByteInspector.firstInstruction(a1, "reportAhkIfPossible", VOID_VOID));
        assertOtherMethodsUntouched(c, "AhkDetection", a0, a1, new String[] {
                "handleClickInventoryItem(III)V",
                "reportAhkIfPossible()V" });

        byte[] d0 = orig.get(DEBUG);
        byte[] d1 = patched.get(DEBUG);
        c.that("DebugPrintStream was patched", !Arrays.equals(d0, d1));
        c.equal("println(String) neutralized",
                "RETURN", ByteInspector.firstInstruction(d1, "println", STRING_VOID));
        assertOtherMethodsUntouched(c, "DebugPrintStream", d0, d1, new String[] {
                "println(Ljava/lang/String;)V" });

        link(c, new GameLoader(parent, patched), patched, "patched");
    }

    /**
     * Every method the agent did NOT rewrite must come out of the patcher
     * byte-for-byte equivalent as far as the linker is concerned: same method
     * set, same exception tables, same StackMapTable. This is the check that
     * catches collateral damage in the real client classes.
     */
    private static void assertOtherMethodsUntouched(Checks c, String label, byte[] orig,
                                                    byte[] patched, String[] rewritten) {
        List<String> before = ByteInspector.methods(orig);
        List<String> after = ByteInspector.methods(patched);
        c.equal(label + ": method set unchanged", before.toString(), after.toString());
        if (!before.equals(after)) return;
        for (String sig : before) {
            int p = sig.indexOf('(');
            String name = sig.substring(0, p);
            String desc = sig.substring(p);
            if (isRewritten(rewritten, sig)) continue;
            c.equal(label + " " + sig + ": exception table untouched",
                    ByteInspector.tryCatchTypes(orig, name, desc).toString(),
                    ByteInspector.tryCatchTypes(patched, name, desc).toString());
            c.equal(label + " " + sig + ": stack map frames untouched",
                    ByteInspector.frames(orig, name, desc),
                    ByteInspector.frames(patched, name, desc));
        }
    }

    private static boolean isRewritten(String[] rewritten, String sig) {
        for (String r : rewritten) {
            if (r.equals(sig)) return true;
        }
        return false;
    }
    private static void link(Checks c, GameLoader loader, Map<String, byte[]> defs, String label) {
        for (String n : defs.keySet()) {
            try {
                Class<?> cls = loader.link(n.replace('/', '.'));
                c.that(label + ": " + n + " links (ClassLoader.resolveClass)", cls != null);
            } catch (VerifyError ve) {
                c.that(label + ": " + n + " links [VerifyError: " + ve.getMessage() + "]", false);
            } catch (Throwable t) {
                c.that(label + ": " + n + " links [" + t + "]", false);
            }
        }
    }

    private static byte[] readEntry(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** Defines the given classes itself and force-links them on request. */
    private static final class GameLoader extends ClassLoader {
        private final Map<String, byte[]> defs;

        GameLoader(ClassLoader parent, Map<String, byte[]> defs) {
            super(parent);
            this.defs = defs;
        }

        /** resolveClass is protected, so linking has to come from in here. */
        public Class<?> link(String binaryName) throws ClassNotFoundException {
            return loadClass(binaryName, true);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    byte[] b = defs.get(name.replace('.', '/'));
                    if (b != null) {
                        c = defineClass(name, b, 0, b.length);
                    } else {
                        c = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    // -- phases 2 and 3: child JVMs -----------------------------------------

    /**
     * The real classes are also verified by the JVM in a throwaway child: real
     * linkage is what the old silent failure was about, and a child keeps client
     * static initializers out of the Gradle daemon.
     */
    private static boolean runRealJarChild(File gameJar, File agentJar, File stubs, File harness)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe());
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(childClasspath(stubs, harness, agentJar));
        cmd.add("com.sun.java.fontmgr.StubMain");
        cmd.add("realjar");
        cmd.add(gameJar.getAbsolutePath());
        cmd.add(agentJar.getAbsolutePath());
        return runAndWait(cmd, "realjar");
    }

    private static boolean runPremain(File stubs, File harness, File agentJar) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe());
        cmd.add("-Dfontmgr.license.bypass=true");
        cmd.add("-javaagent:" + agentJar.getAbsolutePath());
        cmd.add("-cp");
        cmd.add(childClasspath(stubs, harness, agentJar));
        cmd.add("com.sun.java.fontmgr.StubMain");
        cmd.add("premain");
        return runAndWait(cmd, "premain");
    }

    private static boolean runAttach(File stubs, File harness, File attachClasses, File agentJar)
            throws Exception {
        List<String> child = new ArrayList<>();
        child.add(javaExe());
        child.add("-Dfontmgr.license.bypass=true");
        child.add("-cp");
        child.add(childClasspath(stubs, harness, agentJar));
        child.add("com.sun.java.fontmgr.StubMain");
        child.add("attach");

        ProcessBuilder pb = new ProcessBuilder(child);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread pump = pump(p, "attach-target");
        Thread.sleep(700L);

        List<String> attachCmd = new ArrayList<>();
        attachCmd.add(javaExe());
        attachCmd.add("-Dfontmgr.attach.verbose=true");
        attachCmd.add("--add-modules");
        attachCmd.add("jdk.attach");
        attachCmd.add("-cp");
        attachCmd.add(attachClasses.getAbsolutePath());
        attachCmd.add("AttachLoader");
        attachCmd.add(String.valueOf(p.pid()));
        attachCmd.add(agentJar.getAbsolutePath());
        boolean attached = runAndWait(attachCmd, "AttachLoader");

        boolean finished = p.waitFor(180L, TimeUnit.SECONDS);
        if (!finished) p.destroyForcibly();
        pump.join(3000L);
        int code = finished ? p.exitValue() : -1;
        System.out.println("  attach-target: exit=" + code);
        return attached && finished && code == 0;
    }

    private static String childClasspath(File stubs, File harness, File agentJar) {
        return harness.getAbsolutePath() + File.pathSeparator + stubs.getAbsolutePath()
                + File.pathSeparator + agentJar.getAbsolutePath();
    }

    private static boolean runAndWait(List<String> cmd, String label) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread pump = pump(p, label);
        boolean finished = p.waitFor(180L, TimeUnit.SECONDS);
        if (!finished) p.destroyForcibly();
        pump.join(3000L);
        int code = finished ? p.exitValue() : -1;
        System.out.println("  " + label + ": exit=" + code);
        return finished && code == 0;
    }

    private static Thread pump(final Process p, final String label) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("  [" + label + "] " + line);
                }
            } catch (Exception ignored) {
            }
        }, "pump-" + label);
        t.setDaemon(true);
        t.start();
        return t;
    }


    private static String javaExe() {
        String home = System.getProperty("java.home");
        File plain = new File(home, "bin" + File.separator + "java");
        if (plain.exists()) return plain.getAbsolutePath();
        File exe = new File(home, "bin" + File.separator + "java.exe");
        return exe.exists() ? exe.getAbsolutePath() : "java";
    }
}
