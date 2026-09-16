package com.sun.java.fontmgr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.java.fontmgr.combo.Combo;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden decision sequences. Would fail if survive lost priority to spec, if
 * an unfunded kill range converted, or if a spec fired outside the
 * expectedMaxHit window.
 */
public class ReplayHarnessTest {

    @AfterEach
    public void unseed() {
        Humanizer.unseed();
        DryRun.resetForTest();
        System.clearProperty("roatz.dryrun");
    }

    @Test
    public void dhOneShotVictimEatsAndDoesNotSpec() {
        ReplayHarness.Config cfg = nh();
        CombatState swing = pk(1)
                .ourHp(15).ourMaxHp(99)
                .inDhDanger(true).estimatedOppDhHit(80)
                .lastTargetAnim(2066)
                .opponentIsDh(true)
                .opponentLoadout(TickTsv.loadout(1, "MELEE", true))
                .targetHp(70).inKillRange(true).specEnergy(100)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(swing), cfg);
        assertEquals(TickDecision.Intent.EAT, r.decisions.get(0).intent);
        assertEquals("dh-axe", r.decisions.get(0).reason);
        assertEquals(0, r.metrics.specsFired);
        assertEquals(1, r.metrics.surviveEats);
        assertEquals(0, r.metrics.oneShotDeaths, "eat this tick — not a one-shot death");
    }

    @Test
    public void agsGmaulWindowConverts() {
        ReplayHarness.Config cfg = pkCfg();
        int finish = TickDecision.expectedFinishHp(null, cfg);
        CombatState s = pk(10)
                .targetHp(finish).specEnergy(100)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals("SPEC:kill-window", r.actionSequence().get(0));
        assertEquals(1, r.metrics.killWindowsEntered);
        assertEquals(1, r.metrics.killWindowsConverted);
        assertEquals(0, r.metrics.specsOutsideWindow);
        assertEquals(0, r.metrics.missedWindows);
    }

    @Test
    public void clawsGmaulUsesTheSameKillWindowRule() {
        ReplayHarness.Config cfg = pkCfg();
        cfg.combo = CombatScript.SpecWeapon.CLAWS_GMAUL;
        int finish = TickDecision.expectedFinishHp(null, cfg);
        CombatState s = pk(10)
                .targetHp(finish).specEnergy(50)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.SPEC, r.decisions.get(0).intent);
        assertEquals(1, r.metrics.killWindowsConverted);
    }

    @Test
    public void eachHudComboConvertsAtItsOwnFinishHp() {
        for (CombatScript.SpecWeapon w : Combo.HUD_CYCLE) {
            ReplayHarness.Config cfg = pkCfg();
            cfg.combo = w;
            int finish = TickDecision.expectedFinishHp(null, cfg);
            assertTrue(finish > 0, w.name());
            CombatState in = pk(10)
                    .targetHp(finish).specEnergy(100)
                    .inActiveFight(true)
                    .build();
            CombatState over = pk(10)
                    .targetHp(finish + 1).specEnergy(100)
                    .inActiveFight(true)
                    .build();
            assertEquals("SPEC:kill-window",
                    ReplayHarness.run(Arrays.asList(in), cfg).actionSequence().get(0), w.name());
            assertEquals("HOLD:hold",
                    ReplayHarness.run(Arrays.asList(over), cfg).actionSequence().get(0), w.name());
        }
    }

    @Test
    public void ddsIncomingEatsOnNh() {
        ReplayHarness.Config cfg = nh();
        int dds = 1062;
        CombatState s = pk(4)
                .ourHp(40).ourMaxHp(99)
                .lastTargetAnim(dds)
                .targetHp(80).specEnergy(100)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.EAT, r.decisions.get(0).intent);
        assertEquals("opp-spec", r.decisions.get(0).reason);
        assertEquals(0, r.metrics.specsFired);
    }

    @Test
    public void opponentAgsSpecEatsBeforeDump() {
        ReplayHarness.Config cfg = nh();
        CombatState s = pk(5)
                .ourHp(50).ourMaxHp(99)
                .lastTargetAnim(7644)
                .targetHp(40).inKillRange(true).specEnergy(100)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals("EAT:opp-spec", r.actionSequence().get(0));
        assertEquals(0, r.metrics.specsFired);
        assertEquals(1, r.metrics.windowsSuppressedBySurvival);
        assertEquals(0, r.metrics.missedWindows);
    }

    @Test
    public void nhFreezeThenFinishWhenHpEntersSpecRange() {
        ReplayHarness.Config cfg = nh();
        cfg.nhAutoSpec = true;
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState frozen = pk(20)
                .targetHp(finish + 20).specEnergy(100)
                .nhV2Enabled(true).nhPhase("MAGE").nhFreezeTicksLeft(20)
                .inActiveFight(true)
                .build();
        CombatState finishTick = pk(32)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).nhPhase("MELEE").nhFreezeTicksLeft(8)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(frozen, finishTick), cfg);
        assertEquals(TickDecision.Intent.HOLD, r.decisions.get(0).intent);
        assertEquals(TickDecision.Intent.SPEC, r.decisions.get(1).intent);
        assertEquals(1, r.metrics.killWindowsConverted);
        assertEquals(0, r.metrics.specsOutsideWindow);
    }

    @Test
    public void foodBrewKarambwanGoldenIsSurviveNotSpec() {
        ReplayHarness.Config cfg = nh();
        CombatState s = pk(8)
                .ourHp(18).ourMaxHp(99)
                .inDhDanger(true).estimatedOppDhHit(72)
                .lastTargetAnim(2066)
                .opponentLoadout(TickTsv.loadout(1, "MELEE", true))
                .targetHp(55).inKillRange(true).specEnergy(100)
                .inActiveFight(true)
                .lastAction("EAT")
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.EAT, r.decisions.get(0).intent);
        assertEquals(1, r.metrics.surviveEats);
        assertEquals(1, r.metrics.windowsSuppressedBySurvival);
        assertEquals(0, r.metrics.missedWindows);
    }

    @Test
    public void bigHitOutsideTheWindowHolds_wasteClosed() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState s = pk(12)
                .targetHp(90).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .hitsplatChangeTick(12)
                .agsSpecTick(-99)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals("HOLD:hold", r.actionSequence().get(0));
        assertEquals(0, r.metrics.specsOutsideWindow);
        assertEquals(0, r.metrics.killWindowsEntered);
    }

    @Test
    public void unfundedKillRangeIsAMissedWindow() {
        ReplayHarness.Config cfg = pkCfg();
        int finish = TickDecision.expectedFinishHp(null, cfg);
        CombatState s = pk(3)
                .targetHp(finish).specEnergy(0)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.HOLD, r.decisions.get(0).intent);
        assertEquals(1, r.metrics.killWindowsEntered);
        assertEquals(1, r.metrics.missedWindows);
        assertEquals(0, r.metrics.killWindowsConverted);
    }

    @Test
    public void wrongOverheadIsCounted() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState s = pk(1)
                .opponentLoadout(TickTsv.loadout(1, "MELEE", false))
                .ourOverhead("MAGIC")
                .targetHp(80).specEnergy(0)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertTrue(r.decisions.get(0).overheadWrong);
        assertEquals(1, r.metrics.wrongOverheadTicks);
    }

    @Test
    public void tsvRoundTripFeedsTheHarness() {
        String tsv = ""
                + "tick\tseq\ttgt\tthp\ttmax\tspec\tokill\todh\towpn\tostyle\toh\tanim\ttanim\tdefpray\taction\n"
                + "10\t1\tpker\t55\t99\t100\t1\t0\t11802\tMELEE\tMELEE\t-1\t-1\t\tAGS\n";
        List<CombatState> ticks = TickTsv.parse(tsv);
        assertEquals(1, ticks.size());
        assertEquals(55, ticks.get(0).targetHp);
        assertTrue(ticks.get(0).inKillRange);
        ReplayHarness.Report r = ReplayHarness.run(ticks, pkCfg());
        assertEquals(TickDecision.Intent.SPEC, r.decisions.get(0).intent);
    }

    @Test
    public void seededHumanizerIsDeterministic() {
        Humanizer.seed(42L);
        int a = Humanizer.invGapMs();
        Humanizer.seed(42L);
        int b = Humanizer.invGapMs();
        assertEquals(a, b);
        assertTrue(a >= 72 && a <= 99);
    }

    @Test
    public void dryRunSinkRecordsInsteadOfSending() {
        System.setProperty("roatz.dryrun", "true");
        assertTrue(DryRun.enabled());
        DryRun.resetForTest();
        DryRun.doActionSink(0, 0, 454, 1, 0, 0, "Wield", "whip", -1, -1);
        assertEquals(1, DryRun.actions().size());
        assertTrue(DryRun.actions().get(0).contains("Wield"));
        System.clearProperty("roatz.dryrun");
        assertFalse(DryRun.enabled());
    }

    @Test
    public void estimateSpecDamageMatchesCombatScriptTable() {
        int str = 99;
        assertEquals(MaxHitCalculator.agsSpecMaxHit(str),
                TickDecision.estimateSpecDamage(CombatScript.SpecWeapon.AGS_GMAUL, str));
        assertEquals(MaxHitCalculator.agsSpecMaxHit(str),
                TickDecision.estimateSpecDamage(CombatScript.SpecWeapon.CLAWS_GMAUL, str));
        int drained = TickDecision.estimateSpecDamage(CombatScript.SpecWeapon.AGS_GMAUL, 1);
        assertTrue(drained < TickDecision.estimateSpecDamage(CombatScript.SpecWeapon.AGS_GMAUL, 99));
    }

    @Test
    public void staleOutgoingSplatDoesNotBighit() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState s = pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .hitsplatChangeTick(5)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals("HOLD:hold", r.actionSequence().get(0));
        assertEquals(0, r.metrics.specsOutsideWindow);
    }

    @Test
    public void hardHitOutsideTheWindowHolds_wasteClosed() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState s = pk(12)
                .targetHp(90).specEnergy(100)
                .lastIncomingDmg(50).incomingChangeTick(12)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals("HOLD:hold", r.actionSequence().get(0));
        assertEquals(0, r.metrics.specsOutsideWindow);
    }

    @Test
    public void wrongOverheadClosesTheKillWindow() {
        ReplayHarness.Config cfg = pkCfg();
        int finish = TickDecision.expectedFinishHp(null, cfg);
        CombatState s = pk(10)
                .opponentLoadout(TickTsv.loadout(1, "MELEE", false))
                .ourOverhead("MAGIC")
                .targetHp(finish).specEnergy(100)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertTrue(r.decisions.get(0).overheadWrong);
        assertEquals("HOLD:hold", r.actionSequence().get(0));
        assertFalse(r.decisions.get(0).killWindowOpen);
    }

    @Test
    public void nhAutoGearOffSpecsWhenSpecWeaponAlreadyWorn() {
        ReplayHarness.Config cfg = nhNoGear();
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState melee = pk(32)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).nhPhase("MELEE").nhFreezeTicksLeft(8)
                .inActiveFight(true).hasSpecWeapon(true)
                .specWeaponEquipped(true)
                .mageStaffEquipped(false)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(melee), cfg);
        assertEquals("SPEC:kill-window", r.actionSequence().get(0));
        assertTrue(r.decisions.get(0).killWindowOpen);
    }

    @Test
    public void drainedStrengthShrinksTheNhFinishWindow() {
        ReplayHarness.Config cfg = nh();
        CombatState maxed = pk(10)
                .targetHp(1).ourStr(99).ourMaxHp(99)
                .specEnergy(100).nhV2Enabled(true)
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        CombatState drained = pk(10)
                .targetHp(1).ourStr(1).ourMaxHp(99)
                .specEnergy(100).nhV2Enabled(true)
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        int maxedHp = TickDecision.nhSpecFinishHp(maxed, cfg);
        int drainedHp = TickDecision.nhSpecFinishHp(drained, cfg);
        assertTrue(drainedHp < maxedHp, "ostr=1 window=" + drainedHp + " vs ostr=99 window=" + maxedHp);

        int between = drainedHp + 1;
        assertTrue(between <= maxedHp);
        CombatState inMaxedOnly = pk(10)
                .targetHp(between).ourStr(1).ourMaxHp(99)
                .specEnergy(100).nhV2Enabled(true)
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        CombatState stillInMaxed = pk(10)
                .targetHp(between).ourStr(99).ourMaxHp(99)
                .specEnergy(100).nhV2Enabled(true)
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        assertEquals(TickDecision.Intent.HOLD,
                ReplayHarness.run(Arrays.asList(inMaxedOnly), cfg).decisions.get(0).intent);
        assertEquals("SPEC:kill-window",
                ReplayHarness.run(Arrays.asList(stillInMaxed), cfg).actionSequence().get(0));
    }

    @Test
    public void accuracyBelowOneTightensThePkWindow() {
        ReplayHarness.Config cfg = pkCfg();
        cfg.accuracy = 0.5;
        int full = TickDecision.estimateSpecDamage(null, cfg);
        int expected = TickDecision.expectedFinishHp(null, cfg);
        assertTrue(expected < full);
        CombatState over = pk(10)
                .targetHp(full).specEnergy(100).inActiveFight(true)
                .build();
        CombatState in = pk(10)
                .targetHp(expected).specEnergy(100).inActiveFight(true)
                .build();
        assertEquals("HOLD:hold", ReplayHarness.run(Arrays.asList(over), cfg).actionSequence().get(0));
        assertEquals("SPEC:kill-window", ReplayHarness.run(Arrays.asList(in), cfg).actionSequence().get(0));
    }

    @Test
    public void nhAutoGearOffHoldsInRangePhaseEvenWhenHpIsInFinishWindow() {
        ReplayHarness.Config cfg = nhNoGear();
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState range = pk(32)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).nhPhase("RANGE").nhFreezeTicksLeft(8)
                .inActiveFight(true).hasSpecWeapon(true)
                .mageStaffEquipped(false)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(range), cfg);
        assertEquals("HOLD:hold", r.actionSequence().get(0));
        assertFalse(r.decisions.get(0).killWindowOpen,
                "ed2f83c: Auto Gear off must not NH-finish / yank spec mid-range");
    }

    @Test
    public void nhAutoGearOnSpecsInRangePhaseWhenHpIsInFinishWindow() {
        ReplayHarness.Config cfg = nh();
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState range = pk(32)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).nhPhase("RANGE").nhFreezeTicksLeft(8)
                .inActiveFight(true).hasSpecWeapon(true)
                .mageStaffEquipped(false)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(range), cfg);
        assertEquals("SPEC:kill-window", r.actionSequence().get(0));
        assertTrue(r.decisions.get(0).killWindowOpen);
    }

    @Test
    public void nhFinishRequiresCarriedSpecWeapon() {
        ReplayHarness.Config cfg = nh();
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState s = pk(10)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).inActiveFight(true)
                .hasSpecWeapon(false)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.HOLD, r.decisions.get(0).intent);
        assertFalse(r.decisions.get(0).killWindowOpen);
    }

    @Test
    public void oneShotExposureLatchesOncePerBracket() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState.Builder b = pk(1)
                .ourHp(12).ourMaxHp(99)
                .inDhDanger(true).estimatedOppDhHit(80)
                .targetHp(80).specEnergy(0)
                .inActiveFight(true);
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(b.build(), pk(2)
                .ourHp(12).ourMaxHp(99)
                .inDhDanger(true).estimatedOppDhHit(80)
                .targetHp(80).specEnergy(0)
                .inActiveFight(true)
                .build()), cfg);
        assertEquals(1, r.metrics.oneShotDeaths, "two ticks in one bracket is one exposure");
        assertEquals(2, r.metrics.ticks);
    }

    @Test
    public void pkKillWindowRequiresActiveFight() {
        ReplayHarness.Config cfg = pkCfg();
        int finish = TickDecision.expectedFinishHp(null, cfg);
        CombatState s = pk(10)
                .targetHp(finish).specEnergy(100)
                .inActiveFight(false)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.HOLD, r.decisions.get(0).intent);
        assertEquals(0, r.metrics.killWindowsEntered);
    }

    private static ReplayHarness.Config pkCfg() {
        ReplayHarness.Config c = new ReplayHarness.Config();
        c.autoSpec = true;
        c.nhV2 = false;
        return c;
    }

    /** NH V2 with Auto Gear — the finish-enabled config. Production default is off. */
    private static ReplayHarness.Config nh() {
        ReplayHarness.Config c = new ReplayHarness.Config();
        c.nhV2 = true;
        c.nhAutoSpec = true;
        c.nhAutoGear = true;
        c.autoSpec = true;
        c.autoEat = true;
        return c;
    }

    /** Production defaults: NH Auto Spec on, Auto Gear off ({@code ed2f83c}). */
    private static ReplayHarness.Config nhNoGear() {
        ReplayHarness.Config c = nh();
        c.nhAutoGear = false;
        return c;
    }

    private static CombatState.Builder pk(int tick) {
        return new CombatState.Builder(tick, tick)
                .targetName("pker")
                .ourOverhead("MELEE")
                .opponentLoadout(TickTsv.loadout(1, "MELEE", false));
    }
}
