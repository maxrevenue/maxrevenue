package com.roatpkz.client.game.security;

/**
 * Test stand-in for the client anti-macro detector. The two reporting entry
 * points get their bodies replaced with a bare return; verdict(int) is the
 * untouched control.
 */
@SuppressWarnings("unused")
public class AhkDetection {

    private int flagged;

    public void handleClickInventoryItem(int slot, int itemId, int tick) {
        try {
            if (slot < 0) {
                flagged++;
            } else if (slot > 27) {
                flagged += 2;
            } else {
                switch (itemId & 3) {
                    case 0: flagged += 0; break;
                    case 1: flagged++; break;
                    default: flagged += 3; break;
                }
            }
        } catch (RuntimeException ex) {
            flagged = 0;
        }
    }

    public void reportAhkIfPossible() {
        try {
            if (flagged > 3) {
                throw new IllegalStateException("ahk flagged");
            }
            flagged = 0;
        } catch (RuntimeException ex) {
            flagged = -1;
        }
    }

    public int verdict(int x) {
        try {
            switch (x & 3) {
                case 0: return 10;
                case 1: return 11;
                case 2: return 12;
                default: return 13;
            }
        } catch (RuntimeException ex) {
            return -1;
        }
    }
}
