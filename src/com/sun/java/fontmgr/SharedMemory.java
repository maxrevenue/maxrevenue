package com.sun.java.fontmgr;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;

public class SharedMemory {
    private static RandomAccessFile shmFile;
    private static FileChannel      shmChannel;
    private static MappedByteBuffer shmBuffer;
    private static String           shmPath;
    private static final int        PUBLISH_OFFSET = 0;
    private static volatile int     publishSeq;

    public static void init() throws Exception {
        Path cacheDir = Stealth.cacheDir();
        shmPath = Stealth.cacheFile("shm").toString();
        shmFile = new RandomAccessFile(shmPath, "rw");
        shmFile.setLength(65536);
        shmChannel = shmFile.getChannel();
        shmBuffer  = shmChannel.map(FileChannel.MapMode.READ_WRITE, 0, 65536);
        shmBuffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public static String getShmPath()                    { return shmPath; }
    public static void   writeInt(int offset, int value) { shmBuffer.putInt(offset, value); }
    public static int    readInt(int offset)             { return shmBuffer.getInt(offset); }
    public static int    getPublishSeq()                 { return publishSeq; }

    /** Atomically write a {@link GameState} snapshot at offset 0. */
    public static void publish(GameState state) {
        if (shmBuffer == null || state == null) return;
        ByteBuffer slice = shmBuffer.duplicate();
        slice.order(ByteOrder.LITTLE_ENDIAN);
        slice.position(PUBLISH_OFFSET);
        state.writeTo(slice);
        publishSeq++;
        writeInt(GameState.WIRE_SIZE, publishSeq);
    }
}
