package com.sun.java.fontmgr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Would fail if the tick hook were not the first instruction or if a missing
 * cycle method were treated as success.
 */
public class ClassFilePatcherTickHookTest {

    private static final String HOOKS = "com/sun/java/fontmgr/ClientHooks";

    @Test
    public void prependsOnClientTickAsFirstInstructionAndIsIdempotent() throws Exception {
        byte[] orig = syntheticEngine(true);
        assertTrue(ClassFilePatcher.hasMethod(orig, "clientTick", "()V"));
        assertFalse(ClassFilePatcher.containsUtf8(orig, "onClientTick"));

        byte[] patched = ClassFilePatcher.prependInvokeStatic(
                orig, "clientTick", "()V", HOOKS, "onClientTick", "()V");
        assertTrue(ClassFilePatcher.containsUtf8(patched, "onClientTick"));
        assertTrue(startsWithInvokeStatic(patched, "clientTick", "()V",
                HOOKS, "onClientTick", "()V"));

        byte[] again = ClassFilePatcher.prependInvokeStatic(
                patched, "clientTick", "()V", HOOKS, "onClientTick", "()V");
        assertTrue(again == patched || java.util.Arrays.equals(again, patched));
    }

    @Test
    public void hasMethodIsFalseForMissingCycleAlias() {
        byte[] orig = syntheticEngine(true);
        assertFalse(ClassFilePatcher.hasMethod(orig, "processGameLoop", "()V"));
        assertTrue(ClassFilePatcher.voidMethodNames(orig).contains("clientTick"));
    }

    @Test
    public void transformerInstallsTickHookOnGameEngine() {
        byte[] orig = syntheticEngine(true);
        byte[] out = HardcodedCombatAgent.TRANSFORMER.transform(
                null, "com/roatpkz/client/game/engine/GameEngine", null, null, orig);
        assertTrue(out != null && out.length > 0);
        assertTrue(startsWithInvokeStatic(out, "clientTick", "()V",
                HOOKS, "onClientTick", "()V"));
        assertTrue(HardcodedCombatAgent.TRANSFORMER.transform(
                null, "com/roatpkz/client/game/engine/GameEngine", null, null, out) == null);
    }

    @Test
    public void transformerLeavesUntouchedWhenNoCycleMethodExists() {
        byte[] orig = syntheticEngine(false);
        byte[] out = HardcodedCombatAgent.TRANSFORMER.transform(
                null, "com/roatpkz/client/game/engine/GameEngine", null, null, orig);
        assertTrue(out == null || java.util.Arrays.equals(out, orig));
    }

    /**
     * The regression that motivated stopping at the first alias: a class with
     * both {@code clientTick} and {@code graphicsTick} must be hooked exactly
     * once. Hooking both pumps the guard twice per cycle and, when the two
     * methods run on different threads, makes {@code markClientThread()}
     * flip-flop so {@code assertClientThread()} stops meaning anything.
     */
    @Test
    public void hooksOnlyTheFirstCycleAlias() {
        byte[] out = HardcodedCombatAgent.TRANSFORMER.transform(
                null, "com/roatpkz/client/game/engine/GameEngine", null, null,
                syntheticEngineWithTwoAliases());
        assertTrue(out != null && out.length > 0);
        assertTrue(startsWithInvokeStatic(out, "clientTick", "()V",
                        HOOKS, "onClientTick", "()V"),
                "game-logic cycle must be hooked");
        assertFalse(startsWithInvokeStatic(out, "graphicsTick", "()V",
                        HOOKS, "onClientTick", "()V"),
                "second alias must NOT be hooked (double pump / thread flip-flop)");
    }

    /** Engine exposing two cycle aliases; only the first must be hooked. */
    private static byte[] syntheticEngineWithTwoAliases() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/roatpkz/client/game/engine/GameEngine", null, "java/lang/Object", null);
        for (String name : new String[] {"clientTick", "graphicsTick"}) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 1);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Minimal classfile with a branched {@code clientTick} (or only {@code sentinel}).
     */
    private static byte[] syntheticEngine(boolean withClientTick) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/roatpkz/client/game/engine/GameEngine", null, "java/lang/Object", null);
        if (withClientTick) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "clientTick", "()V", null, null);
            mv.visitCode();
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.ICONST_0);
            Label skip = new Label();
            mv.visitJumpInsn(Opcodes.IFEQ, skip);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(skip);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(end);
            mv.visitLabel(handler);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/RuntimeException"});
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
        }
        MethodVisitor sentinel = cw.visitMethod(Opcodes.ACC_PUBLIC, "sentinel", "()V", null, null);
        sentinel.visitCode();
        sentinel.visitInsn(Opcodes.RETURN);
        sentinel.visitMaxs(0, 1);
        sentinel.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean startsWithInvokeStatic(byte[] classFile, String method, String desc,
                                                  String owner, String hook, String hookDesc) {
        final boolean[] hit = {false};
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!method.equals(name) || !desc.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    private boolean first = true;

                    @Override
                    public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                        if (first && opcode == Opcodes.INVOKESTATIC && owner.equals(o)
                                && hook.equals(n) && hookDesc.equals(d)) {
                            hit[0] = true;
                        }
                        first = false;
                    }

                    @Override public void visitInsn(int opcode) { first = false; }
                    @Override public void visitIntInsn(int opcode, int operand) { first = false; }
                    @Override public void visitVarInsn(int opcode, int varIndex) { first = false; }
                    @Override public void visitJumpInsn(int opcode, Label label) { first = false; }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hit[0];
    }
}
