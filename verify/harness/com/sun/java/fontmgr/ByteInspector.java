package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Bytecode-level assertions over raw class file bytes.
 *
 * <p>These are the checks that the old hand-rolled patcher could never have
 * passed: "does the patched method still begin with the hook call", "did the
 * exception table survive", "did the StackMapTable survive". They read the
 * bytes the JVM actually loaded (see {@link StubMain} and
 * {@link VerifyHarness}), not a re-run of the patcher, so they cannot agree
 * with a broken patch by construction.
 */
public final class ByteInspector {

    private static final int API = Opcodes.ASM9;

    private ByteInspector() {}

    /** Class file major version (55 for the client's Java 11 class files). */
    public static int majorVersion(byte[] classFile) {
        return ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
    }

    public static boolean hasMethod(byte[] classFile, final String name, final String desc) {
        final boolean[] found = {false};
        new ClassReader(classFile).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                if (name.equals(n) && desc.equals(d)) found[0] = true;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /**
     * The method's first real instruction, as a readable token; labels, line
     * numbers and stack map frames do not count. Null when absent/abstract.
     */
    public static String firstInstruction(byte[] classFile, final String name, final String desc) {
        final String[] first = {null};
        new ClassReader(classFile).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                if (!name.equals(n) || !desc.equals(d)) return null;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
                return new FirstInstruction(first);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return first[0];
    }

    /** Number of exception-table entries ({@code visitTryCatchBlock}) in a method. */
    public static int tryCatchBlocks(byte[] classFile, final String name, final String desc) {
        final int[] n = {0};
        new ClassReader(classFile).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String m, String d, String sig, String[] ex) {
                if (!name.equals(m) || !desc.equals(d)) return null;
                return new MethodVisitor(API) {
                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        n[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return n[0];
    }

    /** Caught types of a method's exception table, sorted, {@code *} for finally. */
    public static List<String> tryCatchTypes(byte[] classFile, final String name, final String desc) {
        final List<String> types = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String m, String d, String sig, String[] ex) {
                if (!name.equals(m) || !desc.equals(d)) return null;
                return new MethodVisitor(API) {
                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        types.add(type == null ? "*finally*" : type);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        java.util.Collections.sort(types);
        return types;
    }

    /** Number of StackMapTable entries ({@code visitFrame}) in a method. */
    public static int frames(byte[] classFile, final String name, final String desc) {
        final int[] n = {0};
        new ClassReader(classFile).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String m, String d, String sig, String[] ex) {
                if (!name.equals(m) || !desc.equals(d)) return null;
                return new MethodVisitor(API) {
                    @Override
                    public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
                        n[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return n[0];
    }

    /** Declared methods as "name(desc)" strings, in class file order. */
    public static List<String> methods(byte[] classFile) {
        final List<String> out = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                out.add(n + d);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /** True when the method's very first instruction is the given static call. */
    public static boolean startsWithInvokeStatic(byte[] classFile, String method, String desc,
                                                 String owner, String hook, String hookDesc) {
        String expected = "INVOKESTATIC " + owner + "." + hook + hookDesc;
        return expected.equals(firstInstruction(classFile, method, desc));
    }

    /** Records the first instruction of the method under inspection. */
    private static final class FirstInstruction extends MethodVisitor {
        private final String[] out;
        private boolean first = true;

        FirstInstruction(String[] out) {
            super(API);
            this.out = out;
        }

        private void take(String token) {
            if (first) {
                out[0] = token;
                first = false;
            }
        }

        @Override
        public void visitInsn(int opcode) { take(opName(opcode)); }

        @Override
        public void visitIntInsn(int opcode, int operand) { take(opName(opcode)); }

        @Override
        public void visitVarInsn(int opcode, int var) { take(opName(opcode) + " " + var); }

        @Override
        public void visitTypeInsn(int opcode, String type) { take(opName(opcode) + " " + type); }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            take(opName(opcode) + " " + owner + "." + name);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            take(opName(opcode) + " " + owner + "." + name + desc);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            take("INVOKEDYNAMIC " + name);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) { take(opName(opcode)); }

        @Override
        public void visitLdcInsn(Object value) { take("LDC " + value); }

        @Override
        public void visitIincInsn(int var, int inc) { take("IINC " + var); }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            take("TABLESWITCH");
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            take("LOOKUPSWITCH");
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) { take("MULTIANEWARRAY"); }

        private static String opName(int opcode) {
            switch (opcode) {
                case Opcodes.NOP:       return "NOP";
                case Opcodes.ACONST_NULL: return "ACONST_NULL";
                case Opcodes.ICONST_0:  return "ICONST_0";
                case Opcodes.RETURN:    return "RETURN";
                case Opcodes.ARETURN:   return "ARETURN";
                case Opcodes.IRETURN:   return "IRETURN";
                case Opcodes.POP:       return "POP";
                case Opcodes.IFEQ:      return "IFEQ";
                case Opcodes.GOTO:      return "GOTO";
                case Opcodes.INVOKESTATIC:  return "INVOKESTATIC";
                case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
                case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
                case Opcodes.INVOKESPECIAL:   return "INVOKESPECIAL";
                case Opcodes.ATHROW:    return "ATHROW";
                case Opcodes.NEW:       return "NEW";
                case Opcodes.CHECKCAST: return "CHECKCAST";
                case Opcodes.GETFIELD:  return "GETFIELD";
                case Opcodes.PUTFIELD:  return "PUTFIELD";
                case Opcodes.GETSTATIC: return "GETSTATIC";
                case Opcodes.PUTSTATIC: return "PUTSTATIC";
                case Opcodes.ALOAD:     return "ALOAD";
                case Opcodes.ILOAD:     return "ILOAD";
                case Opcodes.ASTORE:    return "ASTORE";
                case Opcodes.ISTORE:    return "ISTORE";
                case Opcodes.DUP:       return "DUP";
                default:                return "op" + opcode;
            }
        }
    }
}
