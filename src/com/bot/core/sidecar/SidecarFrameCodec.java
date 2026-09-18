package com.bot.core.sidecar;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WIRE_V2: 40-byte big-endian intent frame (see docs/tickbus-wire-contract.md).
 */
public final class SidecarFrameCodec {

    public static final int WIRE_V2_FRAME_BYTES = 40;
    public static final byte WIRE_V2 = 2;

    private final ByteBuffer buffer;

    public SidecarFrameCodec() {
        buffer = ByteBuffer.allocate(WIRE_V2_FRAME_BYTES).order(ByteOrder.BIG_ENDIAN);
    }

    public boolean decodeFrame(byte[] source, int offset, SidecarPayload target) {
        if (source == null || source.length - offset < WIRE_V2_FRAME_BYTES) {
            return false;
        }
        buffer.clear();
        buffer.put(source, offset, WIRE_V2_FRAME_BYTES);
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
