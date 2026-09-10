package com.sun.java.fontmgr;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;

/**
 * Tick-snapshot IPC over a small mapped file in the cache directory.
 *
 * <p>Wire layout is unchanged: {@link GameState} payload at offset 0 for
 * {@link GameState#WIRE_SIZE} bytes, then a 4-byte little-endian sequence word
 * at {@link #SEQ_OFFSET}.
 *
 * <h3>Reader protocol (seqlock)</h3>
 * <pre>
 *   do {
 *       s1 = readInt(SEQ_OFFSET);
 *       if ((s1 &amp; 1) != 0) continue;      // a write is in progress
 *       read the payload;
 *       s2 = readInt(SEQ_OFFSET);
 *   } while (s1 != s2 || (s2 &amp; 1) != 0);
 * </pre>
 * The publisher marks the sequence odd before touching the payload and even
 * again once the snapshot is complete, so a reader can always detect a torn
 * read. {@code MappedByteBuffer} writes are not guaranteed to be visible to
 * another process without {@link ByteBuffer#force()}, so the publisher forces
 * around the sequence transitions.
 */
public class SharedMemory {
    private static RandomAccessFile shmFile;
    private static FileChannel      shmChannel;
    private static MappedByteBuffer shmBuffer;
    private static String           shmPath;
    private static final int        PUBLISH_OFFSET = 0;
    /** Sequence word sits directly after the payload (unchanged layout). */
    public  static final int        SEQ_OFFSET = GameState.WIRE_SIZE;
    private static volatile int     publishSeq;              // always even when idle
    private static final Object     PUBLISH_LOCK = new Object();

    public static void init() throws Exception {
        Path cacheDir = Stealth.cacheDir();
        shmPath = Stealth.cacheFile("shm").toString();
        shmFile = new RandomAccessFile(shmPath, "rw");
        shmFile.setLength(65536);
        shmChannel = shmFile.getChannel();
        shmBuffer  = shmChannel.map(MapMode.READ_WRITE, 0, 65536);
        shmBuffer.order(ByteOrder.LITTLE_ENDIAN);
        // Clear any stale "write in progress" marker from a previous session.
        shmBuffer.putInt(SEQ_OFFSET, 0);
        shmBuffer.force();
    }

    public static String getShmPath()                    { return shmPath; }
    public static void   writeInt(int offset, int value) { shmBuffer.putInt(offset, value); }
    public static int    readInt(int offset)             { return shmBuffer.getInt(offset); }
    public static int    getPublishSeq()                 { return publishSeq; }

    /**
     * Atomically publishes a {@link GameState} snapshot, using a seqlock so
     * another process never observes a half-written frame.
     */
    public static void publish(GameState state) {
        MappedByteBuffer buf = shmBuffer;
        if (buf == null || state == null) return;
        synchronized (PUBLISH_LOCK) {
            int complete = publishSeq;          // even
            int writing = complete + 1;         // odd => "write in progress"
            buf.putInt(SEQ_OFFSET, writing);
            buf.force();

            ByteBuffer slice = buf.duplicate();
            slice.order(ByteOrder.LITTLE_ENDIAN);
            slice.position(PUBLISH_OFFSET);
            state.writeTo(slice);

            buf.force();                        // payload visible to other processes
            buf.putInt(SEQ_OFFSET, writing + 1); // even => snapshot complete
            buf.force();
            publishSeq = writing + 1;
        }
    }
}
