package com.sun.java.fontmgr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Java 11 runtime boundary of the agent.
 *
 * <p>The client runs Corretto 11. {@code sourceCompatibility}/{@code targetCompatibility}
 * only control the emitted <em>bytecode version</em> — they still compile against
 * the JDK that runs Gradle, so a build on JDK 21 links against JDK 21 signatures.
 * That shipped a live outage: {@code MappedByteBuffer.duplicate()} returns
 * {@code MappedByteBuffer} on JDK 21 but {@code ByteBuffer} on Java 11, so
 * {@code SharedMemory.publish} threw
 * {@code NoSuchMethodError: java.nio.MappedByteBuffer.duplicate()Ljava/nio/MappedByteBuffer;}
 * on every tick — 6,524 listener errors, and because it throws inside the
 * {@code finally} before {@code publishState()}, the NDJSON recorder wrote zero rows.
 *
 * <p>The compiler-level fix is {@code options.release.set(11)} ("--release"), which
 * resolves against the Java 11 API and makes this class of bug impossible. This
 * test is the belt-and-braces: it fails loudly if a covariant
 * {@code java.nio} override ever creeps back into the agent bytecode.
 */
public class Java11ApiBoundaryTest {

    private static final String MAPPED = "java/nio/MappedByteBuffer";
    private static final String MAPPED_RETURN = ")Ljava/nio/MappedByteBuffer;";

    /**
     * Only these two differ between the client's Java 11 and the build JDK.
     * Measured on Corretto 11.0.31 vs Temurin 21.0.11:
     *
     * <pre>
     *                  Java 11          JDK 21
     *   duplicate()    ByteBuffer       MappedByteBuffer   &lt;- diverges
     *   slice()        ByteBuffer       MappedByteBuffer   &lt;- diverges
     *   asReadOnlyBuffer() ByteBuffer   ByteBuffer
     *   force()/clear()/flip()  MappedByteBuffer (same on both)
     * </pre>
     */
    private static final Set<String> DIVERGENT_ACCESSORS = Set.of("duplicate", "slice");

    @Test
    public void agentBytecodeAvoidsPostJava11CovariantNioBufferOverrides() throws IOException {
        Path classes = Paths.get("build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classes),
                "compiled agent classes not found at " + classes.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            // Java 11: no Stream.toList() (that is Java 16).
            for (Path p : walk.filter(x -> x.toString().endsWith(".class"))
                    .collect(java.util.stream.Collectors.toList())) {
                scan(p, offenders);
            }
        }

        assertTrue(offenders.isEmpty(),
                "agent bytecode calls a method that does not exist on the client's Java 11:\n  "
                        + String.join("\n  ", offenders)
                        + "\nCompile the agent with options.release.set(11) (--release), not "
                        + "sourceCompatibility/targetCompatibility.");
    }

    /** Flags any call returning {@code MappedByteBuffer} (the Java 9+ covariant shape). */
    private static void scan(Path classFile, List<String> offenders) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (MAPPED.equals(owner)
                                && DIVERGENT_ACCESSORS.contains(methodName)
                                && methodDesc != null
                                && methodDesc.endsWith(MAPPED_RETURN)) {
                            offenders.add(classFile.getFileName() + ": "
                                    + methodName + methodDesc);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
