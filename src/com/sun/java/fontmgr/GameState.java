package com.sun.java.fontmgr;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire snapshot published to {@link SharedMemory} each tick.
 * Layout v2 — 128 bytes, little-endian.
 */
public final class GameState {
    public static final int WIRE_SIZE = 128;
    public static final int VERSION   = 2;

    // Local player
    public int    tick, hp, prayer, spec, energy, anim, posX, posY, posZ;
    public String target    = "";
    public int[]  equipIds  = new int[14];

    // Target / combat
    public int    targetHp, targetMaxHp, targetAnim;
    public int[]  targetEquipIds = new int[14];

    // Prayer bitmask: bit0=mage, bit1=range, bit2=melee, bit3=piety, bit4=rigour, bit5=mystic
    public int    prayerFlags;
    public boolean vengActive;
    public boolean targetConsuming;

    public void writeTo(ByteBuffer buf) {
        buf.clear();
        buf.putInt(tick);
        buf.putShort((short) hp);
        buf.putShort((short) prayer);
        buf.putShort((short) spec);
        buf.putShort((short) energy);
        buf.putShort((short) anim);
        buf.putShort((short) posX);
        buf.putShort((short) posY);
        buf.putShort((short) posZ);
        byte[] tb = target.getBytes(StandardCharsets.UTF_8);
        int tl = Math.min(tb.length, 31);
        buf.put((byte) tl);
        buf.put(tb, 0, tl);
        buf.position(61);
        buf.put((byte) Math.min(equipIds.length, 14));
        for (int i = 0; i < 14; i++) {
            buf.putShort((short) (i < equipIds.length ? equipIds[i] : 0));
        }
        // v2 extension @ offset 90
        buf.position(90);
        buf.putInt(VERSION);
        buf.putShort((short) targetHp);
        buf.putShort((short) targetMaxHp);
        buf.putShort((short) targetAnim);
        buf.putShort((short) prayerFlags);
        int flags = (vengActive ? 1 : 0) | (targetConsuming ? 2 : 0);
        buf.putShort((short) flags);
        buf.put((byte) Math.min(targetEquipIds.length, 7));
        for (int i = 0; i < 7; i++) {
            buf.putShort((short) (i < targetEquipIds.length ? targetEquipIds[i] : 0));
        }
        while (buf.position() < WIRE_SIZE) buf.put((byte) 0);
    }
}
