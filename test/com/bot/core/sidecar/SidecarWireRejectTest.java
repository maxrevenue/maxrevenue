package com.bot.core.sidecar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SidecarWireRejectTest {

    @Test
    void versionMismatchIncrementsRejectCounter() {
        SidecarFrameCodec codec = new SidecarFrameCodec();
        SidecarTickMetrics metrics = new SidecarTickMetrics();
        metrics.beginTick();
        byte[] frame = new byte[] {99, 0, 0};
        assertFalse(codec.tryDecodeWireFrame(frame, 0, frame.length, new SidecarPayload(), metrics));
        assertEquals(1, metrics.wireRejects());
    }
}
