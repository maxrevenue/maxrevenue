package com.sun.java.fontmgr;

/**
 * Implement this interface and register with TickEngine to receive a callback
 * once per game server tick (~600ms).
 */
public interface TickListener {
    void onTick(int tick);
}
