package com.bot.core.sidecar;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WIRE_V1 (32B) / WIRE_V2 (40B) intent frames — leading version byte (see docs/tickbus-wire-contract.md).
 */
public final class SidecarFrameCodec {

    public static final byte WIRE_V1 = 1;
    public static final byte WIRE_V2 = 2;

    public static final int VERSION_BYTE_LEN = 1;
    public static final int WIRE_V1_BODY_BYTES = 32;
    public static final int WIRE_V2_BODY_BYTES = 40;
    public static final int WIRE_V1_FRAME_BYTES = VERSION_BYTE_LEN + WIRE_V1_BODY_BYTES;
    public static final int WIRE_V2_FRAME_BYTES = VERSION_BYTE_LEN + WIRE_V2_BODY_BYTES;

    public enum RejectReason {
        TRUNCATED,
        VERSION_MISMATCH,
        V1_NOT_IMPLEMENTED
    }

    private final ByteBuffer buffer;

    public SidecarFrameCodec() {
        buffer = ByteBuffer.allocate(WIRE_V2_BODY_BYTES).order(ByteOrder.BIG_ENDIAN);
    }

    /**
     * Legacy body-only decode (40 bytes, no version prefix) — tests / transitional payloads.
     */
    public boolean decodeFrame(byte[] source, int offset, SidecarPayload target) {
        if (source == null || source.length - offset < WIRE_V2_BODY_BYTES) {
            return false;
        }
        return decodeV2Body(source, offset, target);
    }

    /**
     * Version-prefixed wire decode. On reject: log + {@link SidecarTickMetrics#noteWireFrameReject()}.
     */
    public boolean tryDecodeWireFrame(byte[] source,
                                      int offset,
                                      int length,
                                      SidecarPayload target,
                                      SidecarTickMetrics metrics) {
        if (source == null || length < VERSION_BYTE_LEN) {
            onReject(metrics, RejectReason.TRUNCATED, length, (byte) 0);
            return false;
        }
        byte version = source[offset];
        if (version == WIRE_V2) {
            if (length < WIRE_V2_FRAME_BYTES) {
                onReject(metrics, RejectReason.TRUNCATED, length, version);
                return false;
            }
            if (!decodeV2Body(source, offset + VERSION_BYTE_LEN, target)) {
                onReject(metrics, RejectReason.TRUNCATED, length, version);
                return false;
            }
            return true;
        }
        if (version == WIRE_V1) {
            onReject(metrics, RejectReason.V1_NOT_IMPLEMENTED, length, version);
            return false;
        }
        onReject(metrics, RejectReason.VERSION_MISMATCH, length, version);
        return false;
    }

    private static void onReject(SidecarTickMetrics metrics, RejectReason reason, int len, byte version) {
        SidecarWireRejectLog.reject(reason, len, version);
        if (metrics != null) {
            metrics.noteWireFrameReject();
        }
    }

    private boolean decodeV2Body(byte[] source, int offset, SidecarPayload target) {
        if (source.length - offset < WIRE_V2_BODY_BYTES) {
            return false;
        }
        buffer.clear();
        buffer.put(source, offset, WIRE_V2_BODY_BYTES);
        buffer.flip();
        target.evalTick = buffer.getLong();
        target.ackTick = buffer.getLong();
        target.kind = decodeKind(buffer.get());
        target.priority = decodePriority(buffer.get());
        target.ttlTicks = buffer.get() & 0xFF;
        target.advisorOrdinal = buffer.get() & 0xFF;
        target.precondMask = buffer.getInt();
        target.precondHash = buffer.getInt();
        target.itemId = buffer.getInt();
        target.npcIndex = buffer.getInt();
        target.slotIndex = buffer.getInt();
        return true;
    }

    private static ActionKind decodeKind(byte raw) {
        ActionKind[] values = ActionKind.values();
        int idx = raw & 0xFF;
        if (idx < 0 || idx >= values.length) {
            return ActionKind.IDLE;
        }
        return values[idx];
    }

    private static ActionPriority decodePriority(byte raw) {
        ActionPriority[] values = ActionPriority.values();
        int idx = raw & 0xFF;
        if (idx < 0 || idx >= values.length) {
            return ActionPriority.BACKGROUND;
        }
        return values[idx];
    }
}
