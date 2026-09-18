package com.bot.core.model;

/**
 * Cross-JVM fingerprint bit registry (agent + EchoForge sidecar).
 *
 * <p>Rule: any new {@link GameState} field consulted by a sidecar intent precondition must be
 * assigned bits here (or explicitly documented as waived / not fingerprinted).
 */
public final class FingerprintLayout {

    /** Local HP 0–255 in low byte ({@link CombatTickState#localHp()}). */
    public static final int HP_MASK = 0x0000_00FF;
    public static final int HP_SHIFT = 0;

    /** Special energy 0–100 in bits 16–23 ({@link CombatTickState#specEnergyPercent()}). */
    public static final int SPEC_MASK = 0x00FF_0000;
    public static final int SPEC_SHIFT = 16;

    private FingerprintLayout() {
    }

    public static int build(int localHp, int specEnergyPercent) {
        int fp = 0;
        if (localHp >= 0) {
            fp |= (localHp & 0xFF) << HP_SHIFT;
        }
        if (specEnergyPercent >= 0) {
            fp |= (specEnergyPercent & 0xFF) << SPEC_SHIFT;
        }
        return fp;
    }
}
