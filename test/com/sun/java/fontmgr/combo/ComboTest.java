package com.sun.java.fontmgr.combo;

import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.MaxHitCalculator;
import com.sun.java.fontmgr.TickDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combo descriptors own energy, follow-up, HUD cycle, and max-hit. Goldens
 * would fail if claws silently used the 56 str bonus, or if R-key order drifted.
 */
public class ComboTest {

    @Test
    public void everySpecWeaponHasADescriptor() {
        for (CombatScript.SpecWeapon w : CombatScript.SpecWeapon.values()) {
            assertEquals(w, Combo.of(w).weapon, w.name());
        }
        assertEquals(CombatScript.SpecWeapon.AGS_GMAUL, Combo.of(null).weapon);
    }

    @Test
    public void hudCycleIsClosedAndMatchesToggle() throws Exception {
        CombatScript.SpecWeapon w = CombatScript.SpecWeapon.GMAUL;
        for (int i = 0; i < Combo.HUD_CYCLE.length; i++) {
            assertEquals(Combo.HUD_CYCLE[i], w, "cycle[" + i + "]");
            w = Combo.of(w).nextHud();
        }
        assertEquals(CombatScript.SpecWeapon.GMAUL, w);
        CombatScript script = new CombatScript(null, Object.class, null);
        script.selectedSpec = CombatScript.SpecWeapon.GMAUL;
        for (int i = 0; i < Combo.HUD_CYCLE.length; i++) {
            script.toggleComboSetup();
        }
        assertEquals(CombatScript.SpecWeapon.GMAUL, script.selectedSpec);
        assertEquals("GMAUL", script.comboSetupName());
    }

    @Test
    public void singlesCanonicalizeExceptVoidwaker() {
        assertEquals(CombatScript.SpecWeapon.AGS_GMAUL,
                Combo.of(CombatScript.SpecWeapon.AGS).canonicalWeapon());
        assertEquals(CombatScript.SpecWeapon.DMACE_GMAUL,
                Combo.of(CombatScript.SpecWeapon.DMACE).canonicalWeapon());
        assertEquals(CombatScript.SpecWeapon.VOIDWAKER,
                Combo.of(CombatScript.SpecWeapon.VOIDWAKER).canonicalWeapon());
        assertEquals(CombatScript.SpecWeapon.VOIDWAKER_GMAUL,
                Combo.of(CombatScript.SpecWeapon.VOIDWAKER_GMAUL).canonicalWeapon());
    }

    @Test
    public void specMaxHitMatchesKnownOsrsAnchors() {
        assertEquals(55, Combo.AGS_GMAUL.specMaxHit(99, 0, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE));
        assertEquals(55, Combo.CLAWS_GMAUL.specMaxHit(99, 0, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE));
        int vw = Combo.VOIDWAKER.specMaxHit(99, 0, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE);
        int agsBase = MaxHitCalculator.baseMaxHit(99, MaxHitCalculator.AGS_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE);
        assertEquals(MaxHitCalculator.baseMaxHit(99, MaxHitCalculator.VOIDWAKER_STR_BONUS,
                MaxHitCalculator.PIETY_STR, MaxHitCalculator.STANCE_AGGRESSIVE) + 15, vw);
        assertTrue(vw != 55, "voidwaker is base+15, not the AGS spec");
        assertTrue(Combo.GMAUL.specMaxHit(99, 0, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE) < agsBase);
        assertEquals(MaxHitCalculator.AGS_STR_BONUS, Combo.CLAWS_GMAUL.strBonus);
    }

    @Test
    public void clawsStandInIgnoresWornClawsBonus() {
        assertEquals(MaxHitCalculator.CLAWS_STR_BONUS,
                MaxHitCalculator.weaponStrBonus(0, "Dragon claws"));
        assertEquals(MaxHitCalculator.AGS_STR_BONUS, Combo.CLAWS_GMAUL.windowStrBonus(56));
        assertEquals(MaxHitCalculator.AGS_STR_BONUS, Combo.CLAWS_GMAUL.windowStrBonus(0));
        assertEquals(MaxHitCalculator.AGS_STR_BONUS, Combo.AGS_GMAUL.windowStrBonus(132));
        assertEquals(MaxHitCalculator.GMAUL_STR_BONUS, Combo.GMAUL.windowStrBonus(0));
    }

    @Test
    public void primaryLabelsAreHonest() {
        assertEquals("Gmaul", Combo.GMAUL.primaryLabel);
        assertEquals("Statius", Combo.STATIUS.primaryLabel);
        assertEquals("VLS", Combo.VLS.primaryLabel);
        assertEquals("AGS", Combo.AGS_GMAUL.primaryLabel);
        assertEquals("Claws", Combo.CLAWS_GMAUL.primaryLabel);
    }

    @Test
    public void isStatiusComboIsFamilyNotDmace() throws Exception {
        CombatScript script = new CombatScript(null, Object.class, null);
        script.selectedSpec = CombatScript.SpecWeapon.STATIUS;
        assertTrue(script.isStatiusCombo());
        script.selectedSpec = CombatScript.SpecWeapon.STATIUS_GMAUL;
        assertTrue(script.isStatiusCombo());
        script.selectedSpec = CombatScript.SpecWeapon.DMACE_GMAUL;
        assertTrue(script.isDmaceCombo());
        assertFalse(script.isStatiusCombo());
        script.selectedSpec = CombatScript.SpecWeapon.AGS_GMAUL;
        assertFalse(script.isStatiusCombo());
    }

    @Test
    public void tickDecisionDelegatesToCombo() {
        for (CombatScript.SpecWeapon w : CombatScript.SpecWeapon.values()) {
            int fromCombo = Combo.of(w).specMaxHit(99, 0, MaxHitCalculator.PIETY_STR,
                    MaxHitCalculator.STANCE_AGGRESSIVE);
            int fromTd = TickDecision.estimateSpecDamage(w, 99);
            assertEquals(fromCombo, fromTd, w.name());
        }
    }

    @Test
    public void executeSpecExecutorMatchesToday() {
        assertEquals(Combo.Executor.AGS_GMAUL, Combo.of(CombatScript.SpecWeapon.CLAWS_GMAUL).executor);
        assertEquals(Combo.Executor.AGS_SPEC, Combo.of(CombatScript.SpecWeapon.DMACE).executor);
        assertEquals(Combo.Executor.AGS_GMAUL, Combo.of(CombatScript.SpecWeapon.DMACE_GMAUL).executor);
        assertEquals(Combo.Executor.GMAUL, Combo.of(CombatScript.SpecWeapon.STATIUS).executor);
        assertEquals(Combo.Executor.VLS, Combo.of(CombatScript.SpecWeapon.VLS).executor);
        assertEquals(Combo.Executor.DBOW, Combo.of(CombatScript.SpecWeapon.DBOW_AXES).executor);
    }
}
