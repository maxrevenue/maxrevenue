package com.sun.java.fontmgr.combo;

import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.MaxHitCalculator;

/**
 * Data for one spec setup: energy, follow-up, labels, and the max-hit
 * contribution {@link com.sun.java.fontmgr.TickDecision} uses for the kill
 * window. {@link CombatScript#executeSpec()} dispatches through
 * {@link #executor} so estimate and dump cannot pick different weapons.
 *
 * <p>Claws still uses the AGS-equivalent finish ({@link Hit#CLAWS_AS_AGS})
 * until a measured 4-hit golden replaces it.
 */
public final class Combo {

    public enum Family {
        GMAUL, AGS, CLAWS, DMACE, STATIUS, VLS, VOIDWAKER, DBOW
    }

    /**
     * Which {@link CombatScript} executor {@link CombatScript#executeSpec()}
     * runs. {@link Family#STATIUS} is not on the HUD cycle and today falls
     * through to the gmaul executor — keep that mapping.
     */
    public enum Executor {
        GMAUL, AGS_SPEC, AGS_GMAUL, VLS, VOIDWAKER, VOIDWAKER_GMAUL, DBOW
    }

    public enum Hit {
        AGS_SPEC,
        /** 4-hit total is not {@code bonus * 1.375}; window matches AGS until measured. */
        CLAWS_AS_AGS,
        VOIDWAKER,
        DMACE,
        STATIUS,
        DBOW,
        VLS,
        GMAUL
    }

    public final CombatScript.SpecWeapon weapon;
    public final Family family;
    public final Executor executor;
    public final String setupName;
    public final String primaryLabel;
    public final int defaultEnergyPct;
    public final boolean gmaulFollow;
    public final int strBonus;
    public final Hit hit;

    private Combo(CombatScript.SpecWeapon weapon, Family family, Executor executor,
                  String setupName, String primaryLabel, int defaultEnergyPct,
                  boolean gmaulFollow, int strBonus, Hit hit) {
        this.weapon = weapon;
        this.family = family;
        this.executor = executor;
        this.setupName = setupName;
        this.primaryLabel = primaryLabel;
        this.defaultEnergyPct = defaultEnergyPct;
        this.gmaulFollow = gmaulFollow;
        this.strBonus = strBonus;
        this.hit = hit;
    }

    public static Combo of(CombatScript.SpecWeapon weapon) {
        if (weapon == null) return of(CombatScript.SpecWeapon.AGS_GMAUL);
        switch (weapon) {
            case GMAUL:
                return GMAUL;
            case AGS:
                return AGS;
            case AGS_GMAUL:
                return AGS_GMAUL;
            case STATIUS:
                return STATIUS;
            case STATIUS_GMAUL:
                return STATIUS_GMAUL;
            case DMACE:
                return DMACE;
            case DMACE_GMAUL:
                return DMACE_GMAUL;
            case VLS:
                return VLS;
            case VOIDWAKER:
                return VOIDWAKER;
            case VOIDWAKER_GMAUL:
                return VOIDWAKER_GMAUL;
            case DBOW_AXES:
                return DBOW_AXES;
            case CLAWS_GMAUL:
                return CLAWS_GMAUL;
            default:
                return AGS_GMAUL;
        }
    }

    /**
     * Weapon {@link CombatScript#comboSpec()} reports: singles collapse to the
     * HUD combo (AGS → AGS/Gmaul) except Voidwaker, which keeps single vs +gmaul.
     */
    public CombatScript.SpecWeapon canonicalWeapon() {
        switch (family) {
            case GMAUL:
                return CombatScript.SpecWeapon.GMAUL;
            case CLAWS:
                return CombatScript.SpecWeapon.CLAWS_GMAUL;
            case DBOW:
                return CombatScript.SpecWeapon.DBOW_AXES;
            case VLS:
                return CombatScript.SpecWeapon.VLS;
            case VOIDWAKER:
                return weapon;
            case DMACE:
                return CombatScript.SpecWeapon.DMACE_GMAUL;
            default:
                return CombatScript.SpecWeapon.AGS_GMAUL;
        }
    }

    /** R-key cycle. Matches {@link CombatScript#toggleComboSetup()} exactly. */
    public CombatScript.SpecWeapon nextHud() {
        switch (weapon) {
            case GMAUL:
                return CombatScript.SpecWeapon.CLAWS_GMAUL;
            case CLAWS_GMAUL:
                return CombatScript.SpecWeapon.AGS_GMAUL;
            case AGS_GMAUL:
                return CombatScript.SpecWeapon.DMACE_GMAUL;
            case DMACE:
            case DMACE_GMAUL:
                return CombatScript.SpecWeapon.VOIDWAKER;
            case VOIDWAKER:
                return CombatScript.SpecWeapon.VOIDWAKER_GMAUL;
            case VOIDWAKER_GMAUL:
                return CombatScript.SpecWeapon.VLS;
            case VLS:
                return CombatScript.SpecWeapon.DBOW_AXES;
            case DBOW_AXES:
                return CombatScript.SpecWeapon.GMAUL;
            default:
                return CombatScript.SpecWeapon.GMAUL;
        }
    }

    public int specMaxHit(int boostedStr, int wornOrComboBonus, double prayerMult, int stanceBonus) {
        int str = boostedStr > 0 ? boostedStr : 99;
        int bonus = wornOrComboBonus > 0 ? wornOrComboBonus : strBonus;
        double pray = prayerMult > 0 ? prayerMult : 1.0;
        int stance = stanceBonus >= 0 ? stanceBonus : MaxHitCalculator.STANCE_AGGRESSIVE;
        switch (hit) {
            case VOIDWAKER:
                return MaxHitCalculator.baseMaxHit(str, bonus, pray, stance) + 15;
            case DMACE:
                return MaxHitCalculator.specMaxHit(str, bonus, pray, stance,
                        MaxHitCalculator.DMACE_SPEC_MULT);
            case STATIUS:
                return MaxHitCalculator.specMaxHit(str, bonus, pray, stance,
                        MaxHitCalculator.STATIUS_SPEC_MULT);
            case DBOW:
                return MaxHitCalculator.baseMaxHit(str, bonus, pray, stance)
                        + MaxHitCalculator.THREAT_MARGIN;
            case VLS:
                return MaxHitCalculator.specMaxHit(str, bonus, pray, stance,
                        MaxHitCalculator.VLS_SPEC_MULT);
            case GMAUL:
                return MaxHitCalculator.gmaulSpecMaxHit(str, bonus, pray, stance);
            case CLAWS_AS_AGS:
            case AGS_SPEC:
            default:
                return MaxHitCalculator.agsSpecMaxHit(str, bonus, pray, stance);
        }
    }

    public static final Combo GMAUL = new Combo(
            CombatScript.SpecWeapon.GMAUL, Family.GMAUL, Executor.GMAUL,
            "GMAUL", "AGS", 50, false, MaxHitCalculator.GMAUL_STR_BONUS, Hit.GMAUL);
    public static final Combo AGS = new Combo(
            CombatScript.SpecWeapon.AGS, Family.AGS, Executor.AGS_SPEC,
            "AGS", "AGS", 50, false, MaxHitCalculator.AGS_STR_BONUS, Hit.AGS_SPEC);
    public static final Combo AGS_GMAUL = new Combo(
            CombatScript.SpecWeapon.AGS_GMAUL, Family.AGS, Executor.AGS_GMAUL,
            "AGS+GMAUL", "AGS", 50, true, MaxHitCalculator.AGS_STR_BONUS, Hit.AGS_SPEC);
    public static final Combo STATIUS = new Combo(
            CombatScript.SpecWeapon.STATIUS, Family.STATIUS, Executor.GMAUL,
            "STATIUS", "AGS", 50, false, MaxHitCalculator.STATIUS_STR_BONUS, Hit.STATIUS);
    public static final Combo STATIUS_GMAUL = new Combo(
            CombatScript.SpecWeapon.STATIUS_GMAUL, Family.STATIUS, Executor.GMAUL,
            "STATIUS+GMAUL", "AGS", 50, true, MaxHitCalculator.STATIUS_STR_BONUS, Hit.STATIUS);
    public static final Combo DMACE = new Combo(
            CombatScript.SpecWeapon.DMACE, Family.DMACE, Executor.AGS_SPEC,
            "DMACE", "DMace", 15, false, MaxHitCalculator.DMACE_STR_BONUS, Hit.DMACE);
    public static final Combo DMACE_GMAUL = new Combo(
            CombatScript.SpecWeapon.DMACE_GMAUL, Family.DMACE, Executor.AGS_GMAUL,
            "DMACE+GMAUL", "DMace", 15, true, MaxHitCalculator.DMACE_STR_BONUS, Hit.DMACE);
    public static final Combo VLS = new Combo(
            CombatScript.SpecWeapon.VLS, Family.VLS, Executor.VLS,
            "VLS", "AGS", 50, false, MaxHitCalculator.VLS_STR_BONUS, Hit.VLS);
    public static final Combo VOIDWAKER = new Combo(
            CombatScript.SpecWeapon.VOIDWAKER, Family.VOIDWAKER, Executor.VOIDWAKER,
            "VOIDWAKER", "Voidwaker", 50, false, MaxHitCalculator.VOIDWAKER_STR_BONUS, Hit.VOIDWAKER);
    public static final Combo VOIDWAKER_GMAUL = new Combo(
            CombatScript.SpecWeapon.VOIDWAKER_GMAUL, Family.VOIDWAKER, Executor.VOIDWAKER_GMAUL,
            "VOIDWAKER+GMAUL", "Voidwaker", 50, true, MaxHitCalculator.VOIDWAKER_STR_BONUS, Hit.VOIDWAKER);
    public static final Combo DBOW_AXES = new Combo(
            CombatScript.SpecWeapon.DBOW_AXES, Family.DBOW, Executor.DBOW,
            "DBOW+AXES", "DBow", 50, false, MaxHitCalculator.DBOW_STR_BONUS, Hit.DBOW);
    public static final Combo CLAWS_GMAUL = new Combo(
            CombatScript.SpecWeapon.CLAWS_GMAUL, Family.CLAWS, Executor.AGS_GMAUL,
            "CLAWS+GMAUL", "Claws", 50, true, MaxHitCalculator.AGS_STR_BONUS, Hit.CLAWS_AS_AGS);

    /** HUD R-key order. */
    public static final CombatScript.SpecWeapon[] HUD_CYCLE = {
            CombatScript.SpecWeapon.GMAUL,
            CombatScript.SpecWeapon.CLAWS_GMAUL,
            CombatScript.SpecWeapon.AGS_GMAUL,
            CombatScript.SpecWeapon.DMACE_GMAUL,
            CombatScript.SpecWeapon.VOIDWAKER,
            CombatScript.SpecWeapon.VOIDWAKER_GMAUL,
            CombatScript.SpecWeapon.VLS,
            CombatScript.SpecWeapon.DBOW_AXES
    };
}
