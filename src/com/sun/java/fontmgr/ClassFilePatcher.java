package com.sun.java.fontmgr;

import java.nio.charset.StandardCharsets;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Classfile rewriter used to neutralize client telemetry and to hook client
 * input handlers.
 *
 * <p><b>Why ASM.</b> The previous hand-rolled implementation rebuilt the
 * {@code Code} attribute by hand, which silently discarded the method's
 * exception table and {@code StackMapTable}. For classfiles at major version 51
 * or later (this client is 55) the verifier <em>requires</em> stack map frames
 * at every branch target, so every patched method failed to load with
 * {@code VerifyError: Expecting a stackmap frame at branch target N} — and the
 * failure was swallowed by the caller's {@code catch (Throwable ignored)},
 * leaving the mouse hooks permanently dead.
 *
 * <p>ASM keeps instructions label-based, so prepending a call rewrites all
 * branch offsets, switch padding and frame offsets correctly, and preserves the
 * exception table verbatim. ASM is bundled into the agent JAR (see
 * {@code build.gradle.kts}); it is not present anywhere on the client's
 * classpath, so these references cannot be shadowed by an older copy.
 *
 * <p>Every edit below preserves the original method's frames and {@code maxs}
 * where the code is only extended, and sets them explicitly where the body is
 * replaced wholesale.
 */
final class ClassFilePatcher {

    private ClassFilePatcher() {}

    /**
     * Highest {@code ASMx} api level this JVM's resolved ASM supports. ASM's
     * version constants are compile-time ints, so probing them cannot raise
     * {@code NoSuchFieldError} even if a different ASM is on the classpath;
     * an older ASM rejects an api level it does not know with
     * {@link IllegalArgumentException}, which we catch and step down.
     */
    private static final int API = resolveApi();

    private static int resolveApi() {
        int[] candidates = {Opcodes.ASM9, Opcodes.ASM8, Opcodes.ASM7, Opcodes.ASM6, Opcodes.ASM5};
        for (int api : candidates) {
            try {
                //noinspection ResultOfObjectAllocationIgnored
                new ClassVisitor(api) {};
                return api;
            } catch (IllegalArgumentException unsupported) {
                // Try the next level down.
            }
        }
        return Opcodes.ASM5;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public operations
    // ════════════════════════════════════════════════════════════════════════

    /** Replaces a {@code void} method body with a bare {@code return}. */
    static byte[] nopVoidMethod(byte[] classFile, String name, String descriptor) throws Exception {
        return replaceBody(classFile, name, descriptor, mv -> mv.visitInsn(Opcodes.RETURN), 0);
    }

    /** Replaces a method body with {@code return "<value>";}. */
    static byte[] replaceMethodWithStringReturn(byte[] classFile, String name, String descriptor, String value)
            throws Exception {
        return replaceBody(classFile, name, descriptor, mv -> {
            mv.visitLdcInsn(value);
            mv.visitInsn(Opcodes.ARETURN);
        }, 1);
    }

    /**
     * Prepends {@code invokestatic owner.hookName(hookDesc)} at the entry of an
     * existing method. Exception table, stack map frames and branch/switch
     * offsets are all rewritten correctly by ASM.
     *
     * <p>Idempotent: when the method already starts with that exact call, the
     * input is returned unmodified.
     */
    static byte[] prependInvokeStatic(byte[] classFile, String methodName, String methodDescriptor,
                                      String ownerInternalName, String hookName, String hookDescriptor)
            throws Exception {
        if (startsWithInvokeStatic(classFile, methodName, methodDescriptor,
                ownerInternalName, hookName, hookDescriptor)) {
            return classFile;
        }

        ClassReader cr = new ClassReader(classFile);
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] found = {false};

        ClassVisitor cv = new ClassVisitor(API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) return mv;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return mv;
                found[0] = true;
                return new MethodVisitor(API, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // Emitted before any original instruction/label, so this
                        // lands at bytecode offset 0. Stack effect is zero, which
                        // keeps the original max_stack/max_locals valid.
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                ownerInternalName, hookName, hookDescriptor, false);
                    }
                };
            }
        };
        cr.accept(cv, 0);

        if (!found[0]) {
            throw new IllegalArgumentException("method not found: " + methodName + methodDescriptor);
        }
        return cw.toByteArray();
    }

    /**
     * True when {@code needle} appears anywhere in the classfile's bytes. Used
     * for idempotency checks ("is this class already patched?"). The needles
     * used for that are ASCII method names, which are stored verbatim in the
     * modified-UTF-8 constant pool, so a byte search is exact for them.
     */
    static boolean containsUtf8(byte[] classFile, String needle) {
        if (classFile == null || needle == null || needle.isEmpty()) return false;
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        if (n.length == 0 || n.length > classFile.length) return false;
        byte first = n[0];
        int last = classFile.length - n.length;
        for (int i = 0; i <= last; i++) {
            if (classFile[i] != first) continue;
            int j = 1;
            while (j < n.length && classFile[i + j] == n[j]) j++;
            if (j == n.length) return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internals
    // ════════════════════════════════════════════════════════════════════════

    /** Callback that emits a replacement instruction sequence. */
    private interface BodyEmitter {
        void emit(MethodVisitor mv);
    }

    /**
     * Replaces the body of every non-abstract method matching {@code name} +
     * {@code descriptor}, dropping the original frames/exception table (safe,
     * because the replacement code is straight-line and cannot throw).
     */
    private static byte[] replaceBody(byte[] classFile, String name, String descriptor,
                                      BodyEmitter emitter, int maxStack) throws Exception {
        ClassReader cr = new ClassReader(classFile);
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] found = {false};

        ClassVisitor cv = new ClassVisitor(API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDesc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, methodName, methodDesc, signature, exceptions);
                if (!name.equals(methodName) || !descriptor.equals(methodDesc)) return mv;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return mv;
                found[0] = true;

                // Local slots required by the signature (includes the implicit
                // `this` for instance methods); larger than strictly necessary
                // for static methods, which the verifier accepts.
                final int maxLocals = Math.max(1, Type.getArgumentsAndReturnSizes(descriptor) >> 2);
                return new MethodVisitor(API, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        emitter.emit(mv);
                    }

                    // Discard the original body entirely.
                    @Override public void visitFrame(int type, int nLocal, Object[] l, int nStack, Object[] s) {}
                    @Override public void visitInsn(int opcode) {}
                    @Override public void visitIntInsn(int opcode, int operand) {}
                    @Override public void visitVarInsn(int opcode, int varIndex) {}
                    @Override public void visitTypeInsn(int opcode, String type) {}
                    @Override public void visitFieldInsn(int opcode, String owner, String n, String d) {}
                    @Override public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {}
                    @Override public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm,
                                                                 Object... args) {}
                    @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {}
                    @Override public void visitLabel(org.objectweb.asm.Label label) {}
                    @Override public void visitLdcInsn(Object value) {}
                    @Override public void visitIincInsn(int varIndex, int increment) {}
                    @Override public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label d,
                                                               org.objectweb.asm.Label... labels) {}
                    @Override public void visitLookupSwitchInsn(org.objectweb.asm.Label d, int[] keys,
                                                                org.objectweb.asm.Label[] labels) {}
                    @Override public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {}
                    @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                                             org.objectweb.asm.Label handler, String type) {}
                    @Override public void visitLocalVariable(String n, String d, String s,
                                                             org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                                             int index) {}
                    @Override public void visitLineNumber(int line, org.objectweb.asm.Label start) {}

                    @Override
                    public void visitMaxs(int ignoredMaxStack, int ignoredMaxLocals) {
                        super.visitMaxs(maxStack, maxLocals);
                    }
                };
            }
        };
        cr.accept(cv, 0);

        if (!found[0]) {
            throw new IllegalArgumentException("method not found: " + name + descriptor);
        }
        return cw.toByteArray();
    }

    /** Cheap pre-pass: does the target method begin with the given call? */
    private static boolean startsWithInvokeStatic(byte[] classFile, String methodName, String methodDescriptor,
                                                  String owner, String hook, String hookDescriptor) {
        try {
            ClassReader cr = new ClassReader(classFile);
            final boolean[] hit = {false};
            cr.accept(new ClassVisitor(API) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (!methodName.equals(name) || !methodDescriptor.equals(descriptor)) return null;
                    if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
                    return new MethodVisitor(API) {
                        private boolean first = true;

                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            if (first && opcode == Opcodes.INVOKESTATIC && owner.equals(o)
                                    && hook.equals(n) && hookDescriptor.equals(d)) {
                                hit[0] = true;
                            }
                            first = false;
                        }

                        // A frame/label does not count as an instruction, so keep
                        // `first` set until real code is seen.
                        @Override public void visitInsn(int opcode) { first = false; }
                        @Override public void visitIntInsn(int opcode, int operand) { first = false; }
                        @Override public void visitVarInsn(int opcode, int varIndex) { first = false; }
                        @Override public void visitTypeInsn(int opcode, String type) { first = false; }
                        @Override public void visitFieldInsn(int opcode, String o, String n, String d) { first = false; }
                        @Override public void visitLdcInsn(Object value) { first = false; }
                        @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { first = false; }
                        @Override public void visitIincInsn(int varIndex, int increment) { first = false; }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return hit[0];
        } catch (Exception e) {
            // Unparseable class: fall through and let the real pass report it.
            return false;
        }
    }
}
