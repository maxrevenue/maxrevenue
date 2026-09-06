package com.sun.java.fontmgr;

/**
 * OSRS melee max-hit estimates used for 1-tick KO and DH one-shot safety.
 * Ours are conservative (don't waste spec). Theirs are pessimistic (don't get sacked).
 */
public final class MaxHitCalculator {

    public static final int AGS_STR_BONUS = 132;
    public static final int DH_AXE_STR_BONUS = 105;
    public static final int GMAUL_STR_BONUS = 79;
    public static final int STATIUS_STR_BONUS = 114;
    public static final double PIETY_STR = 1.23;
    public static final double AGS_SPEC_MULT = 1.375;
    public static final double STATIUS_SPEC_MULT = 1.25;
    /** Extra HP we assume they can hit beyond the formula (veng, unknown bonuses). */
    public static final int THREAT_MARGIN = 12;
    /** Assumed boosted strength when we cannot read the opponent. */
    public static final int THREAT_STR = 118;

    private MaxHitCalculator() {}

    public static int effectiveStrength(int boostedStr) {
        int str = boostedStr > 0 ? boostedStr : 99;
        return (int) Math.floor(str * PIETY_STR) + 8 + 3;
    }

    public static int baseMaxHit(int boostedStr, int strBonus) {
        int effective = effectiveStrength(boostedStr);
        return (int) Math.floor(0.5 + (effective * (strBonus + 64)) / 640.0);
    }

    /** Dharok set effect: 1 + missingHp/100. */
    public static int dharokMaxHit(int boostedStr, int currentHp, int maxHp) {
        int base = baseMaxHit(boostedStr, DH_AXE_STR_BONUS);
        int cur = Math.max(1, currentHp);
        int max = Math.max(cur, maxHp > 0 ? maxHp : 99);
        double mod = 1.0 + (max - cur) / 100.0;
        return (int) Math.floor(base * mod);
    }

    public static int agsSpecMaxHit(int boostedStr) {
        return (int) Math.floor(baseMaxHit(boostedStr, AGS_STR_BONUS) * AGS_SPEC_MULT);
    }

    public static int gmaulSpecMaxHit(int boostedStr) {
        return baseMaxHit(boostedStr, GMAUL_STR_BONUS);
    }

    public static int statiusSpecMaxHit(int boostedStr) {
        return (int) Math.floor(baseMaxHit(boostedStr, STATIUS_STR_BONUS) * STATIUS_SPEC_MULT);
    }

    /** Pessimistic opponent DH hit — assume piety + 118 str + stacked HP. */
    public static int opponentDhThreat(int oppCurrentHp, int oppMaxHp) {
        int cur = oppCurrentHp > 0 ? oppCurrentHp : 1;
        int max = oppMaxHp > 0 ? oppMaxHp : 99;
        return dharokMaxHit(THREAT_STR, cur, max) + THREAT_MARGIN;
    }

    public static final int MARLIN_HEAL  = 22;
    public static final int HALIBUT_HEAL = 22;
    public static final int SHARK_HEAL   = 20;
    public static final int KARAM_HEAL   = 18;

    public static int brewHeal(int maxHp) {
        int base = maxHp > 0 ? maxHp : 99;
        return Math.max(16, (int) (base * 0.16));
    }

    public static int estimateComboEatHeal(int maxHp) {
        return MARLIN_HEAL + brewHeal(maxHp) + HALIBUT_HEAL;
    }
}
