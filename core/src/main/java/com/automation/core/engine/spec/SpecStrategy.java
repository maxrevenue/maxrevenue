package com.automation.core.engine.spec;

import com.automation.core.model.GameState;

/**
 * Weapon-specific special attack policy for the headless decision engine.
 *
 * <p>Each implementation encodes when a spec is worth firing (energy, target
 * HP bracket, gear availability) without touching client reflection. The live
 * agent maps {@link #label()} back to its legacy spec executors.
 */
public interface SpecStrategy {

    /** Short label stored on {@link com.automation.core.model.SpecAction} (e.g. {@code "AGS"}). */
    String label();

    /** Minimum special-attack energy (0..100) required to attempt this spec. */
    int minSpecPct();

    /** {@code true} when {@code anim} is this weapon's spec animation id. */
    boolean isSpecAnim(int anim);

    /**
     * {@code true} when this strategy wants to fire on the given snapshot.
     * Implementations must tolerate missing targets and partial vitals.
     */
    boolean shouldExecute(GameState state);
}
