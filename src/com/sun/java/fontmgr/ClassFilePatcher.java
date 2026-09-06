package com.sun.java.fontmgr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tiny classfile rewriter. Used to neutralize client telemetry methods
 * without shipping ASM. Only understands enough of the format to replace
 * a method body and append constant-pool strings.
 */
final class ClassFilePatcher {

    private ClassFilePatcher() {}

    static byte[] nopVoidMethod(byte[] classFile, String name, String descriptor) throws Exception {
        return replaceCode(classFile, name, descriptor, new byte[]{(byte) 0xB1}, 0);
    }

    static byte[] replaceMethodWithStringReturn(byte[] classFile, String name, String descriptor, String value)
            throws Exception {
        Parsed cf = Parsed.parse(classFile);
        int stringIndex = cf.addString(value);
        byte[] code;
        if (stringIndex <= 255) {
            code = new byte[]{0x12, (byte) stringIndex, (byte) 0xB0}; // ldc, areturn
        } else {
            code = new byte[]{0x13, (byte) (stringIndex >> 8), (byte) stringIndex, (byte) 0xB0}; // ldc_w, areturn
        }
        cf.replaceMethodCode(name, descriptor, code, 1);
        return cf.write();
    }

    static byte[] replaceCode(byte[] classFile, String name, String descriptor, byte[] newCode, int maxStack)
            throws Exception {
        Parsed cf = Parsed.parse(classFile);
        cf.replaceMethodCode(name, descriptor, newCode, maxStack);
        return cf.write();
    }

    static boolean containsUtf8(byte[] classFile, String needle) {
        if (classFile == null || needle == null || needle.isEmpty()) return false;
        try {
            Parsed cf = Parsed.parse(classFile);
            return cf.anyUtf8Contains(needle);
        } catch (Exception e) {
            return false;
        }
    }

    /** Nop every method with this descriptor except &lt;init&gt; / &lt;clinit&gt;. */
    static byte[] nopMethodsByDescriptor(byte[] classFile, String descriptor) throws Exception {
        Parsed cf = Parsed.parse(classFile);
        cf.replaceMethodsByDescriptor(descriptor, new byte[]{(byte) 0xB1}, 0, true);
        return cf.write();
    }

    /** Replace the first method whose bytecode references this UTF-8 string. */
    static byte[] replaceMethodReferencingUtf8(byte[] classFile, String needle, byte[] newCode, int maxStack)
            throws Exception {
        Parsed cf = Parsed.parse(classFile);
        if (!cf.replaceMethodReferencingUtf8(needle, newCode, maxStack)) {
            throw new IllegalArgumentException("no method refs " + needle);
        }
        return cf.write();
    }

    static byte[] replaceStringReturnReferencingUtf8(byte[] classFile, String needle, String value)
            throws Exception {
        Parsed cf = Parsed.parse(classFile);
        int stringIndex = cf.addString(value);
        byte[] code;
        if (stringIndex <= 255) {
            code = new byte[]{0x12, (byte) stringIndex, (byte) 0xB0};
        } else {
            code = new byte[]{0x13, (byte) (stringIndex >> 8), (byte) stringIndex, (byte) 0xB0};
        }
        if (!cf.replaceMethodReferencingUtf8(needle, code, 1)) {
            throw new IllegalArgumentException("no method refs " + needle);
        }
        return cf.write();
    }

    private static final class Parsed {
        private int minor;
        private int major;
        private final List<byte[]> pool = new ArrayList<>();
        private byte[] afterPoolBeforeMethods;
        private final List<FieldOrMethod> methods = new ArrayList<>();
        private byte[] classAttributes;

        static Parsed parse(byte[] data) throws Exception {
            Cursor c = new Cursor(data);
            int magic = c.u4();
            if (magic != 0xCAFEBABE) throw new IllegalArgumentException("not a class file");
            Parsed p = new Parsed();
            p.minor = c.u2();
            p.major = c.u2();
            int cpCount = c.u2();
            p.pool.add(null); // index 0
            for (int i = 1; i < cpCount; i++) {
                int start = c.pos;
                int tag = c.u1();
                skipCpInfo(c, tag);
                p.pool.add(slice(data, start, c.pos));
                if (tag == 5 || tag == 6) {
                    p.pool.add(null);
                    i++;
                }
            }
            int afterCp = c.pos;
            c.u2(); // access
            c.u2(); // this
            c.u2(); // super
            int ifaceCount = c.u2();
            c.skip(ifaceCount * 2);
            int fieldCount = c.u2();
            for (int i = 0; i < fieldCount; i++) skipFieldOrMethod(c);
            int methodsStart = c.pos;
            p.afterPoolBeforeMethods = slice(data, afterCp, methodsStart);
            int methodCount = c.u2();
            for (int i = 0; i < methodCount; i++) {
                p.methods.add(readFieldOrMethod(c, data));
            }
            p.classAttributes = slice(data, c.pos, data.length);
            return p;
        }

        boolean anyUtf8Contains(String needle) {
            for (int i = 1; i < pool.size(); i++) {
                if (utf8(i).contains(needle)) return true;
            }
            return false;
        }

        void replaceMethodsByDescriptor(String descriptor, byte[] newCode, int maxStack, boolean skipInit)
                throws Exception {
            boolean found = false;
            for (FieldOrMethod m : methods) {
                String n = utf8(m.nameIndex);
                if (skipInit && ("<init>".equals(n) || "<clinit>".equals(n))) continue;
                if (!descriptor.equals(utf8(m.descIndex))) continue;
                found = true;
                writeMethodCode(m, newCode, maxStack);
            }
            if (!found) throw new IllegalArgumentException("no method desc " + descriptor);
        }

        boolean replaceMethodReferencingUtf8(String needle, byte[] newCode, int maxStack) throws Exception {
            for (FieldOrMethod m : methods) {
                String n = utf8(m.nameIndex);
                if ("<init>".equals(n) || "<clinit>".equals(n)) continue;
                if (!methodCodeRefsUtf8(m, needle)) continue;
                writeMethodCode(m, newCode, maxStack);
                return true;
            }
            return false;
        }

        private boolean methodCodeRefsUtf8(FieldOrMethod m, String needle) {
            for (Attr a : m.attributes) {
                if (!"Code".equals(utf8(a.nameIndex))) continue;
                byte[] info = a.info;
                if (info == null || info.length < 8) continue;
                int codeLen = ((info[4] & 0xFF) << 24) | ((info[5] & 0xFF) << 16)
                        | ((info[6] & 0xFF) << 8) | (info[7] & 0xFF);
                int start = 8;
                int end = Math.min(info.length, start + codeLen);
                for (int i = start; i < end; i++) {
                    int op = info[i] & 0xFF;
                    if (op == 0x12 && i + 1 < end) {
                        if (cpStringContains(info[i + 1] & 0xFF, needle)) return true;
                    } else if (op == 0x13 && i + 2 < end) {
                        int idx = ((info[i + 1] & 0xFF) << 8) | (info[i + 2] & 0xFF);
                        if (cpStringContains(idx, needle)) return true;
                    }
                }
            }
            return false;
        }

        private boolean cpStringContains(int index, String needle) {
            if (index <= 0 || index >= pool.size()) return false;
            byte[] e = pool.get(index);
            if (e == null || e[0] != 8 || e.length < 3) return false;
            int utf = ((e[1] & 0xFF) << 8) | (e[2] & 0xFF);
            return utf8(utf).contains(needle);
        }

        private void writeMethodCode(FieldOrMethod m, byte[] newCode, int maxStack) {
            int maxLocals = Math.max(1, inferMaxLocals(m));
            List<byte[]> kept = new ArrayList<>();
            for (Attr a : m.attributes) {
                if ("Code".equals(utf8(a.nameIndex))) {
                    kept.add(writeCodeAttribute(a.nameIndex, newCode, maxStack, maxLocals));
                } else {
                    kept.add(a.raw);
                }
            }
            m.attributesRaw = kept;
        }

        void replaceMethodCode(String name, String descriptor, byte[] newCode, int maxStack) throws Exception {
            boolean found = false;
            for (FieldOrMethod m : methods) {
                if (!name.equals(utf8(m.nameIndex)) || !descriptor.equals(utf8(m.descIndex))) continue;
                found = true;
                int maxLocals = Math.max(1, inferMaxLocals(m));
                List<byte[]> kept = new ArrayList<>();
                for (Attr a : m.attributes) {
                    if ("Code".equals(utf8(a.nameIndex))) {
                        kept.add(writeCodeAttribute(a.nameIndex, newCode, maxStack, maxLocals));
                    } else {
                        kept.add(a.raw);
                    }
                }
                m.attributesRaw = kept;
            }
            if (!found) throw new IllegalArgumentException("method not found: " + name + descriptor);
        }

        int addString(String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            byte[] utfInfo = new byte[3 + utf8.length];
            utfInfo[0] = 1;
            utfInfo[1] = (byte) (utf8.length >> 8);
            utfInfo[2] = (byte) utf8.length;
            System.arraycopy(utf8, 0, utfInfo, 3, utf8.length);
            pool.add(utfInfo);
            int utfIndex = pool.size() - 1;

            byte[] strInfo = new byte[3];
            strInfo[0] = 8;
            strInfo[1] = (byte) (utfIndex >> 8);
            strInfo[2] = (byte) utfIndex;
            pool.add(strInfo);
            return pool.size() - 1;
        }

        byte[] write() throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeU4(out, 0xCAFEBABE);
            writeU2(out, minor);
            writeU2(out, major);
            writeU2(out, pool.size());
            for (int i = 1; i < pool.size(); i++) {
                byte[] e = pool.get(i);
                if (e == null) continue;
                out.write(e);
            }
            out.write(afterPoolBeforeMethods);
            writeU2(out, methods.size());
            for (FieldOrMethod m : methods) {
                writeU2(out, m.access);
                writeU2(out, m.nameIndex);
                writeU2(out, m.descIndex);
                writeU2(out, m.attributesRaw.size());
                for (byte[] a : m.attributesRaw) out.write(a);
            }
            out.write(classAttributes);
            return out.toByteArray();
        }

        private String utf8(int index) {
            byte[] e = pool.get(index);
            if (e == null || e[0] != 1) return "";
            int len = ((e[1] & 0xFF) << 8) | (e[2] & 0xFF);
            return new String(e, 3, len, StandardCharsets.UTF_8);
        }

        private int inferMaxLocals(FieldOrMethod m) {
            for (Attr a : m.attributes) {
                if (!"Code".equals(utf8(a.nameIndex))) continue;
                if (a.info.length >= 4) {
                    return ((a.info[2] & 0xFF) << 8) | (a.info[3] & 0xFF);
                }
            }
            return 4;
        }

        private static byte[] writeCodeAttribute(int nameIndex, byte[] code, int maxStack, int maxLocals) {
            // Code attr info: max_stack u2, max_locals u2, code_length u4, code, ex_table_len u2, attrs u2
            int infoLen = 2 + 2 + 4 + code.length + 2 + 2;
            byte[] raw = new byte[6 + infoLen];
            raw[0] = (byte) (nameIndex >> 8);
            raw[1] = (byte) nameIndex;
            raw[2] = (byte) (infoLen >> 24);
            raw[3] = (byte) (infoLen >> 16);
            raw[4] = (byte) (infoLen >> 8);
            raw[5] = (byte) infoLen;
            int i = 6;
            raw[i++] = (byte) (maxStack >> 8);
            raw[i++] = (byte) maxStack;
            raw[i++] = (byte) (maxLocals >> 8);
            raw[i++] = (byte) maxLocals;
            raw[i++] = (byte) (code.length >> 24);
            raw[i++] = (byte) (code.length >> 16);
            raw[i++] = (byte) (code.length >> 8);
            raw[i++] = (byte) code.length;
            System.arraycopy(code, 0, raw, i, code.length);
            // exception_table_length and attributes_count already zero
            return raw;
        }

        private static void skipCpInfo(Cursor c, int tag) throws Exception {
            switch (tag) {
                case 1:
                    c.skip(c.u2());
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    c.skip(2);
                    break;
                case 15:
                    c.skip(3);
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    c.skip(4);
                    break;
                case 5:
                case 6:
                    c.skip(8);
                    break;
                default:
                    throw new IllegalArgumentException("unknown cp tag " + tag);
            }
        }

        private static void skipFieldOrMethod(Cursor c) {
            c.skip(6);
            int ac = c.u2();
            for (int i = 0; i < ac; i++) {
                c.skip(2);
                c.skip(c.u4());
            }
        }

        private static FieldOrMethod readFieldOrMethod(Cursor c, byte[] data) {
            FieldOrMethod m = new FieldOrMethod();
            m.access = c.u2();
            m.nameIndex = c.u2();
            m.descIndex = c.u2();
            int ac = c.u2();
            m.attributes = new ArrayList<>();
            m.attributesRaw = new ArrayList<>();
            for (int i = 0; i < ac; i++) {
                int start = c.pos;
                int name = c.u2();
                int len = c.u4();
                int infoStart = c.pos;
                c.skip(len);
                Attr a = new Attr();
                a.nameIndex = name;
                a.info = slice(data, infoStart, infoStart + len);
                a.raw = slice(data, start, c.pos);
                m.attributes.add(a);
                m.attributesRaw.add(a.raw);
            }
            return m;
        }

        private static byte[] slice(byte[] src, int from, int to) {
            byte[] out = new byte[to - from];
            System.arraycopy(src, from, out, 0, out.length);
            return out;
        }

        private static void writeU2(ByteArrayOutputStream out, int v) {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private static void writeU4(ByteArrayOutputStream out, int v) {
            out.write((v >> 24) & 0xFF);
            out.write((v >> 16) & 0xFF);
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
        }
    }

    private static final class FieldOrMethod {
        int access;
        int nameIndex;
        int descIndex;
        List<Attr> attributes;
        List<byte[]> attributesRaw;
    }

    private static final class Attr {
        int nameIndex;
        byte[] info;
        byte[] raw;
    }

    private static final class Cursor {
        final byte[] d;
        int pos;

        Cursor(byte[] d) { this.d = d; }

        int u1() { return d[pos++] & 0xFF; }

        int u2() {
            int v = ((d[pos] & 0xFF) << 8) | (d[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        int u4() {
            int v = ((d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16)
                    | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        void skip(int n) { pos += n; }
    }
}
