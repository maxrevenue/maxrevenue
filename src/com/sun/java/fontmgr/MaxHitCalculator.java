package com.sun.java.fontmgr;

import com.sun.java.fontmgr.combo.Combo;

/**
 * OSRS melee max-hit estimates used for 1-tick KO and DH one-shot safety.
 * Ours are conservative (don't waste spec). Theirs are pessimistic (don't get sacked).
 *
 * <p>Our hits use live boosted strength, the worn/combo strength bonus, the
 * active strength-prayer multiplier, and the attack-style stance bonus.
 * Opponent threat still assumes piety + {@link #THREAT_STR} so a missing
 * prayer read cannot shrink the survive bracket.
 */
public final class MaxHitCalculator {

    public static final int AGS_STR_BONUS = 132;
    public static final int DH_AXE_STR_BONUS = 105;
    public static final int GMAUL_STR_BONUS = 79;
    public static final int STATIUS_STR_BONUS = 114;
    public static final int DMACE_STR_BONUS = 86;
    public static final int CLAWS_STR_BONUS = 56;
    public static final int VLS_STR_BONUS = 113;
    public static final int VOIDWAKER_STR_BONUS = 80;
    public static final int DBOW_STR_BONUS = 90;

    public static final double PIETY_STR = 1.23;
    public static final double CHIVALRY_STR = 1.18;
    public static final double ULTIMATE_STR = 1.15;
    public static final double SUPERHUMAN_STR = 1.10;
    public static final double BURST_STR = 1.05;

    public static final double AGS_SPEC_MULT = 1.375;
    public static final double STATIUS_SPEC_MULT = 1.25;
    public static final double DMACE_SPEC_MULT = 1.5;
    public static final double VLS_SPEC_MULT = 1.20;

    /** Aggressive / controlled-str stance. Accurate/defensive contribute 0 to str. */
    public static final int STANCE_AGGRESSIVE = 3;
    public static final int STANCE_CONTROLLED = 1;
    public static final int STANCE_ACCURATE = 0;

    /** Extra HP we assume they can hit beyond the formula (veng, unknown bonuses). */
    public static final int THREAT_MARGIN = 12;
    /** Assumed boosted strength when we cannot read the opponent. */
    public static final int THREAT_STR = 118;

    /**
     * Default KO accuracy when opponent defence is unknown: 1.0 = use max hit.
     * Lower values tighten {@link #expectedHit(int, double)} (item 3).
     */
    public static final double DEFAULT_ACCURACY = 1.0;

    private MaxHitCalculator() {}

    public static int effectiveStrength(int boostedStr) {
        return effectiveStrength(boostedStr, PIETY_STR, STANCE_AGGRESSIVE);
    }

    /**
     * {@code floor(str * prayer) + 8 + stance}. Prayer {@code <= 0} is treated
     * as 1.0 (no strength prayer), not piety.
     */
    public static int effectiveStrength(int boostedStr, double prayerMult, int stanceBonus) {
        int str = boostedStr > 0 ? boostedStr : 99;
        double pray = prayerMult > 0 ? prayerMult : 1.0;
        int stance = Math.max(0, stanceBonus);
        return (int) Math.floor(str * pray) + 8 + stance;
    }

    public static int baseMaxHit(int boostedStr, int strBonus) {
        return baseMaxHit(boostedStr, strBonus, PIETY_STR, STANCE_AGGRESSIVE);
    }

    public static int baseMaxHit(int boostedStr, int strBonus, double prayerMult, int stanceBonus) {
        int effective = effectiveStrength(boostedStr, prayerMult, stanceBonus);
        int bonus = Math.max(0, strBonus);
        return (int) Math.floor(0.5 + (effective * (bonus + 64)) / 640.0);
    }

    /** Dharok set effect: 1 + missingHp/100. Opponent threat still uses piety. */
    public static int dharokMaxHit(int boostedStr, int currentHp, int maxHp) {
        return dharokMaxHit(boostedStr, currentHp, maxHp, DH_AXE_STR_BONUS, PIETY_STR, STANCE_AGGRESSIVE);
    }

    public static int dharokMaxHit(int boostedStr, int currentHp, int maxHp,
                                   int strBonus, double prayerMult, int stanceBonus) {
        int base = baseMaxHit(boostedStr, strBonus, prayerMult, stanceBonus);
        int cur = Math.max(1, currentHp);
        int max = Math.max(cur, maxHp > 0 ? maxHp : 99);
        double mod = 1.0 + (max - cur) / 100.0;
        return (int) Math.floor(base * mod);
    }

    public static int agsSpecMaxHit(int boostedStr) {
        return specMaxHit(boostedStr, AGS_STR_BONUS, PIETY_STR, STANCE_AGGRESSIVE, AGS_SPEC_MULT);
    }

    public static int agsSpecMaxHit(int boostedStr, int strBonus, double prayerMult, int stanceBonus) {
        int bonus = strBonus > 0 ? strBonus : AGS_STR_BONUS;
        return specMaxHit(boostedStr, bonus, prayerMult, stanceBonus, AGS_SPEC_MULT);
    }

    public static int gmaulSpecMaxHit(int boostedStr) {
        return baseMaxHit(boostedStr, GMAUL_STR_BONUS);
    }

    public static int gmaulSpecMaxHit(int boostedStr, int strBonus, double prayerMult, int stanceBonus) {
        int bonus = strBonus > 0 ? strBonus : GMAUL_STR_BONUS;
        return baseMaxHit(boostedStr, bonus, prayerMult, stanceBonus);
    }

    public static int statiusSpecMaxHit(int boostedStr) {
        return specMaxHit(boostedStr, STATIUS_STR_BONUS, PIETY_STR, STANCE_AGGRESSIVE, STATIUS_SPEC_MULT);
    }

    public static int specMaxHit(int boostedStr, int strBonus, double prayerMult,
                                 int stanceBonus, double specMult) {
        int base = baseMaxHit(boostedStr, strBonus, prayerMult, stanceBonus);
        if (specMult <= 1.0) return base;
        return (int) Math.floor(base * specMult);
    }

    /**
     * OSRS hit chance from attack and defence rolls.
     * {@code chance = 1 - (def+2)/(2*(att+1))} when att &gt; def, else {@code att/(2*(def+1))}.
     */
    public static double hitChance(int attackRoll, int defenceRoll) {
        int att = Math.max(0, attackRoll);
        int def = Math.max(0, defenceRoll);
        if (att > def) {
            return 1.0 - (def + 2.0) / (2.0 * (att + 1));
        }
        return att / (2.0 * (def + 1));
    }

    public static int attackRoll(int boostedAtt, double prayerMult, int stanceBonus, int attBonus) {
        int att = boostedAtt > 0 ? boostedAtt : 99;
        double pray = prayerMult > 0 ? prayerMult : 1.0;
        int stance = Math.max(0, stanceBonus);
        int effective = (int) Math.floor(att * pray) + 8 + stance;
        return effective * (Math.max(0, attBonus) + 64);
    }

    public static int defenceRoll(int boostedDef, int defBonus) {
        int def = boostedDef > 0 ? boostedDef : 99;
        int effective = def + 8;
        return effective * (Math.max(0, defBonus) + 64);
    }

    /** {@code floor(maxHit * accuracy)}. Accuracy 1.0 is the max hit. */
    public static int expectedHit(int maxHit, double accuracy) {
        if (maxHit <= 0) return 0;
        if (accuracy >= 1.0) return maxHit;
        if (accuracy <= 0.0) return 0;
        return (int) Math.floor(maxHit * accuracy);
    }

    public static double strengthPrayerMultiplier(boolean piety, boolean chivalry,
                                                  boolean ultimate, boolean superhuman, boolean burst) {
        if (piety) return PIETY_STR;
        if (chivalry) return CHIVALRY_STR;
        if (ultimate) return ULTIMATE_STR;
        if (superhuman) return SUPERHUMAN_STR;
        if (burst) return BURST_STR;
        return 1.0;
    }

    public static int strBonusForCombo(CombatScript.SpecWeapon combo) {
        return Combo.of(combo).strBonus;
    }

    public static int weaponStrBonus(int itemId, String name) {
        if (InventoryTracker.isAgs(itemId, name)) return AGS_STR_BONUS;
        if (InventoryTracker.isDragonClaws(itemId, name)) return CLAWS_STR_BONUS;
        if (InventoryTracker.isGmaul(itemId, name)) return GMAUL_STR_BONUS;
        if (InventoryTracker.isDragonMace(itemId, name)) return DMACE_STR_BONUS;
        if (InventoryTracker.isStatius(itemId, name)) return STATIUS_STR_BONUS;
        if (InventoryTracker.isVls(itemId, name)) return VLS_STR_BONUS;
        if (InventoryTracker.isVoidwaker(itemId, name)) return VOIDWAKER_STR_BONUS;
        if (InventoryTracker.isDarkBow(itemId, name)) return DBOW_STR_BONUS;
        if (InventoryTracker.isDharokAxe(itemId, name)) return DH_AXE_STR_BONUS;
        return 0;
    }

    /** Pessimistic opponent DH hit — assume piety + 118 str + stacked HP. */
    public static int opponentDhThreat(int oppCurrentHp, int oppMaxHp) {
        int cur = oppCurrentHp > 0 ? oppCurrentHp : 1;
        int max = oppMaxHp > 0 ? oppMaxHp : 99;
        return dharokMaxHit(THREAT_STR, cur, max) + THREAT_MARGIN;
    }

    /**
     * Pessimistic magic hit from opponent weapon name (ice barrage / powered staff).
     * Uses {@link #THREAT_MARGIN} like other opponent threat helpers.
     */
    public static int opponentMagicThreat(String weaponName) {
        String w = weaponName == null ? "" : InventoryTracker.stripName(weaponName).toLowerCase();
        int base;
        if (w.contains("toxic staff") || w.contains("sang") || w.contains("sanguinesti")) {
            base = 34;
        } else if (w.contains("volatile") || w.contains("eldritch")) {
            base = 38;
        } else if (w.contains("trident") || w.contains("wand of")) {
            base = 28;
        } else {
            base = 30;
        }
        return base + THREAT_MARGIN;
    }

    /**
     * Pessimistic opponent spec max hit for a spec-animation id. Falls back to
     * 60 when the animation is not a recognized spec. Uses {@link #THREAT_STR}
     * (118 boosted, piety applied) so the survival bracket reflects a realistic
     * worst-case hit, not a fixed OSRS constant.
     */
    public static int opponentSpecThreat(int animId) {
        if (AnimationDb.isAgsSpec(animId) || animId == 7646 || animId == 7647) {
            return agsSpecMaxHit(THREAT_STR) + THREAT_MARGIN;
        }
        if (AnimationDb.isClawsSpec(animId)) {
            return agsSpecMaxHit(THREAT_STR) + THREAT_MARGIN;
        }
        if (AnimationDb.isGmaulSpec(animId)) {
            return gmaulSpecMaxHit(THREAT_STR) + THREAT_MARGIN;
        }
        if (AnimationDb.isDmaceSpec(animId)) {
            return specMaxHit(THREAT_STR, DMACE_STR_BONUS, PIETY_STR, STANCE_AGGRESSIVE, DMACE_SPEC_MULT)
                    + THREAT_MARGIN;
        }
        if (AnimationDb.isStatiusSpec(animId)) {
            return statiusSpecMaxHit(THREAT_STR) + THREAT_MARGIN;
        }
        if (AnimationDb.isDharokAnimation(animId)) {
            return dharokMaxHit(THREAT_STR, 1, 99) + THREAT_MARGIN;
        }
        if (AnimationDb.isDarkBowSpec(animId) || animId == 1074 || animId == 7555) {
            return baseMaxHit(THREAT_STR, DBOW_STR_BONUS) + THREAT_MARGIN;
        }
        if (AnimationDb.isSpecAnimation(animId)) {
            return 60 + THREAT_MARGIN;
        }
        return 0;
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
