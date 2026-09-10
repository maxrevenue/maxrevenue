package com.sun.java.fontmgr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Child-JVM probe for the two real deployment paths of the agent.
 *
 * <p>It runs three kinds of evidence, none of which can be produced by simply
 * re-running the patcher in-process:
 *
 * <ol>
 *   <li><b>premain</b> ({@code -javaagent}): a capture transformer registered
 *       from {@code main} runs *after* the agent own transformer, so the bytes
 *       it observes are the bytes the JVM actually loaded. The stubs are then
 *       loaded and linked ({@code Class.forName(..., initialize=true)}), which
 *       is where a {@code VerifyError} would surface.</li>
 *   <li><b>attach</b> (Dynamic Attach): the stubs are loaded <em>before</em> the
 *       agent attaches, so the agent has something to retransform. The probe
 *       waits for {@code agentmain}, registers a capture transformer and calls
 *       {@code retransformClasses} itself. That only succeeds if the agent
 *       installed a retransform-capable transformer that produces loadable
 *       bytecode.</li>
 *   <li><b>post-attach</b>: the same stubs are then defined in a <em>fresh</em>
 *       class loader and force-linked with {@code ClassLoader.resolveClass}, to
 *       prove the transformer registered by {@code agentmain} also works for
 *       classes loaded afterwards.</li>
 * </ol>
 *
 * <p>Exit code 0 only when every check passes. {@code Runtime.halt} is used so
 * the agent daemon threads cannot keep the JVM alive.
 */
public final class StubMain {

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

    private StubMain() {}

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "premain";
        System.out.println("[stub] mode=" + mode + " jvm=" + System.getProperty("java.version"));
        boolean pass;
        try {
            if ("realjar".equals(mode)) {
                pass = runRealJar(args[1], args[2]);
            } else if ("attach".equals(mode)) {
                pass = runAttach();
            } else {
                pass = runPremain();
            }
        } catch (Throwable t) {
            System.out.println("  FAIL probe threw: " + t);
            t.printStackTrace(System.out);
            pass = false;
        }
        System.out.println(pass ? "[stub] PASS " + mode : "[stub] FAIL " + mode);
        System.out.flush();
        Runtime.getRuntime().halt(pass ? 0 : 1);
    }

    // -- premain: load-time transformation of fresh classes ------------------

    private static boolean runPremain() {
        Checks c = new Checks("premain (-javaagent) load-time");
        Instrumentation inst = FontManager.getInstrumentation();
        c.that("premain ran: Instrumentation visible", inst != null);
        if (inst == null) {
            c.report();
            return false;
        }
        Capture capture = new Capture();
        inst.addTransformer(capture, true);

        for (String n : ALL) {
            c.that("loads and links " + n, loadClass(n) != null);
        }
        assertPatched(c, capture.bytes, "load-time", true);
        c.report();
        return c.ok();
    }

    // -- attach: retransform path plus post-attach load path -----------------

    private static boolean runAttach() throws Exception {
        Checks c = new Checks("Dynamic Attach (agentmain)");

        for (String n : ALL) {
            c.that("pre-attach load and link of " + n, loadClass(n) != null);
        }

        Instrumentation inst = awaitInstrumentation(90000L);
        c.that("agentmain attached: Instrumentation visible", inst != null);
        if (inst == null) {
            c.report();
            return false;
        }
        Thread.sleep(500L);

        // (a) retransform the classes that were loaded before the attach.
        Capture afterAttach = new Capture();
        inst.addTransformer(afterAttach, true);
        Class<?>[] loaded = new Class<?>[ALL.length];
        for (int i = 0; i < ALL.length; i++) loaded[i] = loadClass(ALL[i]);
        try {
            inst.retransformClasses(loaded);
            c.that("retransformClasses accepted every stub", true);
        } catch (Throwable t) {
            c.that("retransformClasses accepted every stub [" + t + "]", false);
        }
        assertPatched(c, afterAttach.bytes, "retransform", true);

        // (b) classes defined after the attach must be hooked at load time too.
        Map<String, byte[]> defs = new LinkedHashMap<>();
        for (String n : ALL) defs.put(n.replace('/', '.'), readBytes(n));
        Capture postAttach = new Capture();
        inst.addTransformer(postAttach, true);
        DefiningLoader loader = new DefiningLoader(StubMain.class.getClassLoader(), defs);
        boolean linked = true;
        for (String n : ALL) {
            try {
                loader.link(n.replace('/', '.'));
            } catch (Throwable t) {
                linked = false;
                System.out.println("  FAIL post-attach link of " + n + ": " + t);
            }
        }
        c.that("post-attach classes link in a fresh loader (no VerifyError)", linked);
        assertPatched(c, postAttach.bytes, "post-attach load-time", true);

        c.report();
        return c.ok();
    }

    // -- shared assertions ---------------------------------------------------

    private static void assertPatched(Checks c, Map<String, byte[]> patched, String phase,
                                      boolean expectTransform) {
        assertMouse(c, patched.get(MOUSE), phase, expectTransform);
        assertHttp(c, patched.get(HTTP), phase, expectTransform);
        assertAhk(c, patched.get(AHK), phase, expectTransform);
        assertDebug(c, patched.get(DEBUG), phase, expectTransform);
    }

    private static void assertMouse(Checks c, byte[] mouse, String phase, boolean expectTransform) {
        byte[] orig = readBytes(MOUSE);
        if (mouse == null) {
            c.that(phase + ": MouseHandler bytes captured", false);
            return;
        }
        if (expectTransform) {
            c.that(phase + ": MouseHandler was actually transformed",
                    !java.util.Arrays.equals(mouse, orig));
        }
        c.that(phase + ": hook is FIRST instruction of mousePressed",
                ByteInspector.startsWithInvokeStatic(mouse, "mousePressed", MOUSE_EVENT,
                        HOOKS, "onMousePressed", VOID_VOID));
        c.that(phase + ": hook is FIRST instruction of mouseMoved",
                ByteInspector.startsWithInvokeStatic(mouse, "mouseMoved", MOUSE_EVENT,
                        HOOKS, "onMouseMoved", VOID_VOID));
        c.that(phase + ": hook is FIRST instruction of mouseDragged",
                ByteInspector.startsWithInvokeStatic(mouse, "mouseDragged", MOUSE_EVENT,
                        HOOKS, "onMouseMoved", VOID_VOID));
        for (String m : new String[] { "mousePressed", "mouseMoved", "mouseDragged", "mouseReleased" }) {
            int before = ByteInspector.tryCatchBlocks(orig, m, MOUSE_EVENT);
            int after = ByteInspector.tryCatchBlocks(mouse, m, MOUSE_EVENT);
            c.that(phase + ": exception table preserved on " + m + " [" + before + " entries]",
                    before > 0 && before == after);
            c.equal(phase + ": caught types preserved on " + m,
                    ByteInspector.tryCatchTypes(orig, m, MOUSE_EVENT).toString(),
                    ByteInspector.tryCatchTypes(mouse, m, MOUSE_EVENT).toString());
            int framesBefore = ByteInspector.frames(orig, m, MOUSE_EVENT);
            int framesAfter = ByteInspector.frames(mouse, m, MOUSE_EVENT);
            c.that(phase + ": StackMapTable kept on " + m + " [" + framesBefore + " -> " + framesAfter + "]",
                    framesAfter >= framesBefore && framesAfter > 0);
        }
    }

    private static void assertHttp(Checks c, byte[] http, String phase, boolean expectTransform) {
        byte[] orig = readBytes(HTTP);
        if (http == null) {
            c.that(phase + ": HttpHelper bytes captured", false);
            return;
        }
        if (expectTransform) {
            c.that(phase + ": HttpHelper was actually transformed",
                    !java.util.Arrays.equals(http, orig));
        }
        String potion = ByteInspector.firstInstruction(http, "getClientPotionSettings", STRING_RET);
        c.that(phase + ": getClientPotionSettings returns the scrubbed agent string ["
                + potion + "]", potion != null && potion.startsWith("LDC Command:"));
        String plugins = ByteInspector.firstInstruction(http, "getLoadedPluginDetails", STRING_RET);
        c.that(phase + ": getLoadedPluginDetails reports no plugins [" + plugins + "]",
                plugins != null && plugins.startsWith("LDC Total Plugins: 0"));
        c.that(phase + ": untouched method keeps its exception table",
                ByteInspector.tryCatchBlocks(orig, "touch", INT_INT) > 0
                        && ByteInspector.tryCatchBlocks(orig, "touch", INT_INT)
                        == ByteInspector.tryCatchBlocks(http, "touch", INT_INT));
    }

    private static void assertAhk(Checks c, byte[] ahk, String phase, boolean expectTransform) {
        byte[] orig = readBytes(AHK);
        if (ahk == null) {
            c.that(phase + ": AhkDetection bytes captured", false);
            return;
        }
        if (expectTransform) {
            c.that(phase + ": AhkDetection was actually transformed",
                    !java.util.Arrays.equals(ahk, orig));
        }
        c.equal(phase + ": handleClickInventoryItem neutralized",
                "RETURN", ByteInspector.firstInstruction(ahk, "handleClickInventoryItem", TRIPLE_VOID));
        c.equal(phase + ": reportAhkIfPossible neutralized",
                "RETURN", ByteInspector.firstInstruction(ahk, "reportAhkIfPossible", VOID_VOID));
        c.that(phase + ": untouched method keeps its exception table",
                ByteInspector.tryCatchBlocks(orig, "verdict", INT_INT) > 0
                        && ByteInspector.tryCatchBlocks(orig, "verdict", INT_INT)
                        == ByteInspector.tryCatchBlocks(ahk, "verdict", INT_INT));
    }

    private static void assertDebug(Checks c, byte[] dbg, String phase, boolean expectTransform) {
        byte[] orig = readBytes(DEBUG);
        if (dbg == null) {
            c.that(phase + ": DebugPrintStream bytes captured", false);
            return;
        }
        if (expectTransform) {
            c.that(phase + ": DebugPrintStream was actually transformed",
                    !java.util.Arrays.equals(dbg, orig));
        }
        c.equal(phase + ": println(String) neutralized",
                "RETURN", ByteInspector.firstInstruction(dbg, "println", STRING_VOID));
        c.that(phase + ": untouched method keeps its exception table",
                ByteInspector.tryCatchBlocks(orig, "sentinel", INT_INT) > 0
                        && ByteInspector.tryCatchBlocks(orig, "sentinel", INT_INT)
                        == ByteInspector.tryCatchBlocks(dbg, "sentinel", INT_INT));
    }

    // -- realjar: the real client classes, verified by the JVM itself --------

    /**
     * Defines the four real classes from game.jar (unpatched and patched) in a
     * fresh loader inside a throwaway child JVM and lets the verifier run.
     *
     * <p>Two steps, because they are not equivalent: {@code resolveClass} is
     * documented to link, but HotSpot may still defer verification, so the class
     * is then initialized to force it. Anything other than a {@code VerifyError}
     * (including a client {@code <clinit>} blowing up) means the bytecode
     * verified, which is the property under test. The negative control proves
     * this signal is not silent.
     */
    private static boolean runRealJar(String gameJarPath, String agentJarPath) throws Exception {
        Checks c = new Checks("real game.jar verification (child JVM)");
        java.io.File gameJar = new java.io.File(gameJarPath);
        java.io.File agentJar = new java.io.File(agentJarPath);

        Map<String, byte[]> orig = new LinkedHashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(gameJar)) {
            for (String n : ALL) {
                java.util.zip.ZipEntry e = zip.getEntry(n + ".class");
                if (e == null) {
                    c.that("game.jar contains " + n, false);
                    continue;
                }
                orig.put(n, readStream(zip.getInputStream(e)));
            }
        }
        if (orig.size() != ALL.length) {
            c.report();
            return false;
        }

        c.that("negative control: a StackMapTable missing a branch target is rejected by the verifier",
                invokeRejects("NegativeControl", NegativeControl.classWithMissingFrame("NegativeControl")));

        ClassLoader parent = new java.net.URLClassLoader(
                new java.net.URL[] { gameJar.toURI().toURL(), agentJar.toURI().toURL() },
                StubMain.class.getClassLoader());

        Map<String, byte[]> patched = new LinkedHashMap<>();
        for (String n : ALL) {
            byte[] out = HardcodedCombatAgent.TRANSFORMER.transform(null, n, null, null, orig.get(n));
            patched.put(n, out == null ? orig.get(n) : out);
        }

        verifyAll(c, parent, orig, "unpatched (control)");
        verifyAll(c, parent, patched, "patched");
        c.report();
        return c.ok();
    }

    private static void verifyAll(Checks c, ClassLoader parent, Map<String, byte[]> defs, String label) {
        DefiningLoader loader = new DefiningLoader(parent, binaryNames(defs));
        for (String n : defs.keySet()) {
            String binary = n.replace('/', '.');
            try {
                loader.link(binary);
            } catch (VerifyError ve) {
                c.that(label + ": " + n + " links (resolveClass) [VerifyError: " + ve.getMessage() + "]", false);
                continue;
            } catch (Throwable t) {
                c.that(label + ": " + n + " links (resolveClass) [" + t + "]", false);
                continue;
            }
            try {
                Class.forName(binary, true, loader);
                c.that(label + ": " + n + " verifies and initializes", true);
            } catch (VerifyError ve) {
                c.that(label + ": " + n + " verifies [VerifyError: " + ve.getMessage() + "]", false);
            } catch (Throwable t) {
                // Client <clinit> side effects are not our business; the class
                // verified and linked, which is what is being asserted.
                c.that(label + ": " + n + " verifies (<clinit> threw "
                        + t.getClass().getSimpleName() + ")", true);
            }
        }
    }

    /** True when the JVM rejects these bytes with a VerifyError. */
    private static boolean invokeRejects(String binaryName, byte[] bytes) {
        Map<String, byte[]> defs = new LinkedHashMap<>();
        defs.put(binaryName, bytes);
        DefiningLoader loader = new DefiningLoader(StubMain.class.getClassLoader(), defs);
        try {
            Class<?> cls = loader.link(binaryName);
            java.lang.reflect.Method m = cls.getDeclaredMethod("probe", int.class);
            m.invoke(null, 0);
            return false;
        } catch (VerifyError ve) {
            System.out.println("  note negative control: " + ve.getMessage());
            return true;
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            System.out.println("  note negative control: " + cause);
            return cause instanceof VerifyError;
        } catch (Throwable t) {
            System.out.println("  note negative control: unexpected " + t);
            return false;
        }
    }

    private static Map<String, byte[]> binaryNames(Map<String, byte[]> byInternal) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : byInternal.entrySet()) {
            out.put(e.getKey().replace('/', '.'), e.getValue());
        }
        return out;
    }

    private static byte[] readStream(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * Synthetic class whose StackMapTable covers the entry but not the branch
     * target: the shape that produced "VerifyError: Expecting a stackmap frame
     * at branch target N".
     */
    private static final class NegativeControl {
        private NegativeControl() {}

        static byte[] classWithMissingFrame(String internalName) {
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
            cw.visit(org.objectweb.asm.Opcodes.V11,
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER,
                    internalName, null, "java/lang/Object", null);
            org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                    org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                    "probe", "(I)I", null, null);
            mv.visitCode();
            org.objectweb.asm.Label covered = new org.objectweb.asm.Label();
            org.objectweb.asm.Label uncovered = new org.objectweb.asm.Label();
            mv.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 0);
            mv.visitLabel(covered);
            mv.visitFrame(org.objectweb.asm.Opcodes.F_NEW, 1,
                    new Object[] { org.objectweb.asm.Opcodes.INTEGER }, 0, new Object[0]);
            mv.visitJumpInsn(org.objectweb.asm.Opcodes.IFEQ, uncovered);
            mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
            mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            mv.visitLabel(uncovered);
            mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
            mv.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }

    // -- helpers -------------------------------------------------------------

    private static Class<?> loadClass(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), true, StubMain.class.getClassLoader());
        } catch (Throwable t) {
            System.out.println("  FAIL loading " + internalName + ": " + t);
            return null;
        }
    }

    private static Instrumentation awaitInstrumentation(long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long last = 0L;
        while (System.currentTimeMillis() < deadline) {
            Instrumentation inst = FontManager.getInstrumentation();
            if (inst != null) return inst;
            long now = System.currentTimeMillis();
            if (now - last > 5000L) {
                last = now;
                System.out.println("  ... waiting for attach");
            }
            Thread.sleep(100L);
        }
        return null;
    }

    /** Raw bytes of a stub as they sit on the classpath (the unpatched original). */
    private static byte[] readBytes(String internalName) {
        try (InputStream in = StubMain.class.getResourceAsStream("/" + internalName + ".class")) {
            if (in == null) throw new IllegalStateException("missing resource " + internalName);
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + internalName, e);
        }
    }

    /** Records the bytes it is handed (the previous transformer output) and never edits. */
    private static final class Capture implements ClassFileTransformer {
        final Map<String, byte[]> bytes = new ConcurrentHashMap<>();

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                                ProtectionDomain domain, byte[] classfileBuffer) {
            if (className != null && classfileBuffer != null) bytes.put(className, classfileBuffer);
            return null;
        }
    }

    /** Fresh loader that defines our stubs itself and can force linking. */
    private static final class DefiningLoader extends ClassLoader {
        private final Map<String, byte[]> defs;

        DefiningLoader(ClassLoader parent, Map<String, byte[]> defs) {
            super(parent);
            this.defs = defs;
        }

        /** Force linking of a stub this loader defines (resolveClass is protected). */
        public Class<?> link(String binaryName) throws ClassNotFoundException {
            return loadClass(binaryName, true);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    byte[] b = defs.get(name);
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
}
