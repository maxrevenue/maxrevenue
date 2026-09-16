package com.sun.java.fontmgr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known OSRS melee max hits. These would fail if piety were still applied
 * unconditionally, or if the AGS 132 bonus leaked into every combo.
 */
public class MaxHitCalculatorTest {

    @Test
    public void pietyAgs99Is40BaseAnd55Spec() {
        // floor(99 * 1.23) + 8 + 3 = 132; floor(0.5 + 132 * 196 / 640) = 40
        assertEquals(132, MaxHitCalculator.effectiveStrength(99, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(40, MaxHitCalculator.baseMaxHit(99, MaxHitCalculator.AGS_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(55, MaxHitCalculator.agsSpecMaxHit(99));
        assertEquals(55, TickDecision.estimateSpecDamage(CombatScript.SpecWeapon.AGS_GMAUL, 99));
    }

    @Test
    public void noPrayerAgs99Is34BaseAnd46Spec() {
        assertEquals(110, MaxHitCalculator.effectiveStrength(99, 1.0,
                MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(34, MaxHitCalculator.baseMaxHit(99, MaxHitCalculator.AGS_STR_BONUS,
                1.0, MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(46, MaxHitCalculator.agsSpecMaxHit(99, MaxHitCalculator.AGS_STR_BONUS,
                1.0, MaxHitCalculator.STANCE_AGGRESSIVE));
        assertTrue(46 < 55, "dropping piety must shrink the AGS spec");
    }

    @Test
    public void superCombatPietyAgs118Is48BaseAnd66Spec() {
        assertEquals(156, MaxHitCalculator.effectiveStrength(118, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(48, MaxHitCalculator.baseMaxHit(118, MaxHitCalculator.AGS_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(66, MaxHitCalculator.agsSpecMaxHit(118));
    }

    @Test
    public void drainedStrShrinksAgsSpec() {
        int maxed = MaxHitCalculator.agsSpecMaxHit(99, MaxHitCalculator.AGS_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE);
        int drained = MaxHitCalculator.agsSpecMaxHit(1, MaxHitCalculator.AGS_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE);
        assertTrue(drained < maxed, "ostr=1 spec=" + drained + " vs ostr=99 spec=" + maxed);
        assertEquals(5, drained);
    }

    @Test
    public void gmaulUsesWeaponBonusNotAgs132() {
        int gmaul = MaxHitCalculator.gmaulSpecMaxHit(99, MaxHitCalculator.GMAUL_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE);
        int agsBase = MaxHitCalculator.baseMaxHit(99, MaxHitCalculator.AGS_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE);
        assertTrue(gmaul < agsBase);
        assertEquals(MaxHitCalculator.GMAUL_STR_BONUS,
                MaxHitCalculator.strBonusForCombo(CombatScript.SpecWeapon.GMAUL));
    }

    @Test
    public void expectedHitAtFullAccuracyIsMaxHit() {
        assertEquals(55, MaxHitCalculator.expectedHit(55, 1.0));
        assertEquals(27, MaxHitCalculator.expectedHit(55, 0.5));
        assertEquals(0, MaxHitCalculator.expectedHit(55, 0.0));
    }

    @Test
    public void hitChanceIsSymmetricOsrsFormula() {
        // att > def: 1 - (def+2)/(2*(att+1))
        assertEquals(1.0 - 12.0 / (2.0 * 21), MaxHitCalculator.hitChance(20, 10), 1e-9);
        // att <= def: att / (2*(def+1))
        assertEquals(10.0 / (2.0 * 21), MaxHitCalculator.hitChance(10, 20), 1e-9);
    }

    @Test
    public void strengthPrayerMultiplierPrefersPiety() {
        assertEquals(MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.strengthPrayerMultiplier(true, true, true, true, true));
        assertEquals(1.0,
                MaxHitCalculator.strengthPrayerMultiplier(false, false, false, false, false));
        assertEquals(MaxHitCalculator.CHIVALRY_STR,
                MaxHitCalculator.strengthPrayerMultiplier(false, true, false, false, false));
    }

    @Test
    public void noPrayerIsNotSilentPiety() {
        int withPiety = TickDecision.estimateSpecDamage(
                CombatScript.SpecWeapon.AGS_GMAUL, 99, 0, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE);
        int noPray = TickDecision.estimateSpecDamage(
                CombatScript.SpecWeapon.AGS_GMAUL, 99, 0, 1.0,
                MaxHitCalculator.STANCE_AGGRESSIVE);
        assertTrue(noPray < withPiety);
    }
}
