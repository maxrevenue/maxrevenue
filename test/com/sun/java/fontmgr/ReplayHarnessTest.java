package com.sun.java.fontmgr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden decision sequences. Would fail if survive lost priority to spec, if
 * an unfunded kill range converted, or if the big-hit waste path disappeared
 * before item 3 closes it on purpose.
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
        CombatState s = pk(10)
                .targetHp(70).inKillRange(true).specEnergy(100)
                .estimatedOurMaxHit(77).inActiveFight(true)
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
        CombatState s = pk(10)
                .targetHp(60).inKillRange(true).specEnergy(50)
                .inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals(TickDecision.Intent.SPEC, r.decisions.get(0).intent);
        assertEquals(1, r.metrics.killWindowsConverted);
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
    }

    @Test
    public void bigHitStillSpecsOutsideTheKillWindow_currentWaste() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState s = pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .build();
        ReplayHarness.Report r = ReplayHarness.run(Arrays.asList(s), cfg);
        assertEquals("SPEC:bighit", r.actionSequence().get(0));
        assertEquals(1, r.metrics.specsOutsideWindow);
        assertEquals(0, r.metrics.killWindowsEntered);
    }

    @Test
    public void unfundedKillRangeIsAMissedWindow() {
        ReplayHarness.Config cfg = pkCfg();
        CombatState s = pk(3)
                .targetHp(40).inKillRange(true).specEnergy(0)
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
                + "10\t1\tpker\t70\t99\t100\t1\t0\t11802\tMELEE\tMELEE\t-1\t-1\t\tAGS\n";
        List<CombatState> ticks = TickTsv.parse(tsv);
        assertEquals(1, ticks.size());
        assertEquals(70, ticks.get(0).targetHp);
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

    private static ReplayHarness.Config pkCfg() {
        ReplayHarness.Config c = new ReplayHarness.Config();
        c.autoSpec = true;
        c.nhV2 = false;
        return c;
    }

    private static ReplayHarness.Config nh() {
        ReplayHarness.Config c = new ReplayHarness.Config();
        c.nhV2 = true;
        c.nhAutoSpec = true;
        c.autoSpec = true;
        c.autoEat = true;
        return c;
    }

    private static CombatState.Builder pk(int tick) {
        return new CombatState.Builder(tick, tick)
                .targetName("pker")
                .ourOverhead("MELEE")
                .opponentLoadout(TickTsv.loadout(1, "MELEE", false));
    }
}
