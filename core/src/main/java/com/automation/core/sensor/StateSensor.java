package com.automation.core.sensor;

import com.automation.core.model.GameState;

/**
 * PHASE 1 — SENSE.
 *
 * <p>Reads the raw client memory (via reflection, in the real agent module) and
 * assembles an immutable {@link GameState}. This is the <em>only</em> layer that
 * is allowed to know how the client stores its data; everything downstream works
 * purely against the model.
 */
public interface StateSensor {

    /** Build a fresh, immutable snapshot of the world for the current tick. */
    GameState readGameState();
}
