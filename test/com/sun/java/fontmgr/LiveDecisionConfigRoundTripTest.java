package com.sun.java.fontmgr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CombatScript#runOnTickArbiter} is {@link TickDecision#decide} plus
 * {@link CombatScript#applyTickDecision} using {@link CombatScript#liveDecisionConfig()}.
 * That is true by construction — it does <em>not</em> drive full {@code onTick}
 * (early returns, {@code myPlayer == null}, NH ordering). What it does cover:
 * {@code applyCfg} → {@code liveDecisionConfig()} flag round-trip, and that
 * {@code applyTickDecision} emits the {@code [DryRun] intent} line.
 */
public class LiveDecisionConfigRoundTripTest {

    @BeforeEach
    public void dryRun() {
        System.setProperty("roatz.dryrun", "true");
        DryRun.resetForTest();
        Humanizer.unseed();
    }

    @AfterEach
    public void cleanup() {
        Humanizer.unseed();
        DryRun.resetForTest();
        System.clearProperty("roatz.dryrun");
    }

    @Test
    public void applyCfgRoundTripsPkWindow() throws Exception {
        assertRoundTrip(Arrays.asList(agsWindow()), pkCfg());
    }

    @Test
    public void applyCfgRoundTripsOutsideWindowHold() throws Exception {
        assertRoundTrip(Arrays.asList(outsideWindow()), pkCfg());
    }

    @Test
    public void applyCfgRoundTripsStaleSplat() throws Exception {
        CombatState stale = pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .hitsplatChangeTick(5)
                .build();
        assertRoundTrip(Arrays.asList(stale), pkCfg());
    }

    @Test
    public void applyCfgRoundTripsHardHit() throws Exception {
        CombatState s = pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastIncomingDmg(50).incomingChangeTick(12)
                .inActiveFight(true)
                .build();
        assertRoundTrip(Arrays.asList(s), pkCfg());
    }

    @Test
    public void applyCfgRoundTripsNhSurviveThenFinish() throws Exception {
        ReplayHarness.Config cfg = nh();
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState eat = pk(5)
                .ourHp(50).ourMaxHp(99)
                .lastTargetAnim(7644)
                .targetHp(40).inKillRange(true).specEnergy(100)
                .inActiveFight(true)
                .build();
        CombatState frozen = pk(20)
                .targetHp(finish + 20).specEnergy(100)
                .nhV2Enabled(true).inActiveFight(true)
                .build();
        CombatState ko = pk(32)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).inActiveFight(true)
                .hasSpecWeapon(true)
                .build();
        assertRoundTrip(Arrays.asList(eat, frozen, ko), cfg);
    }

    @Test
    public void applyCfgRoundTripsDhEat() throws Exception {
        CombatState swing = pk(1)
                .ourHp(15).ourMaxHp(99)
                .inDhDanger(true).estimatedOppDhHit(80)
                .lastTargetAnim(2066)
                .opponentIsDh(true)
                .opponentLoadout(TickTsv.loadout(1, "MELEE", true))
                .targetHp(70).inKillRange(true).specEnergy(100)
                .inActiveFight(true)
                .build();
        assertRoundTrip(Arrays.asList(swing), nh());
    }

    @Test
    public void applyCfgRoundTripsUnfundedWindow() throws Exception {
        CombatState s = pk(3)
                .targetHp(40).inKillRange(true).specEnergy(0)
                .inActiveFight(true)
                .build();
        assertRoundTrip(Arrays.asList(s), pkCfg());
    }

    @Test
    public void applyCfgRoundTripsDrainedStr() throws Exception {
        ReplayHarness.Config cfg = nh();
        int drainedHp = TickDecision.nhSpecFinishHp(
                pk(10).ourStr(1).ourMaxHp(99).targetHp(1).build(), cfg);
        CombatState s = pk(10)
                .targetHp(drainedHp + 1).ourStr(1).ourMaxHp(99)
                .specEnergy(100).nhV2Enabled(true)
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        assertRoundTrip(Arrays.asList(s), cfg);
    }

    @Test
    public void applyCfgRoundTripsNhAutoGearOff() throws Exception {
        ReplayHarness.Config cfg = nh();
        cfg.nhAutoGear = false;
        int finish = TickDecision.nhSpecFinishHp(cfg);
        CombatState range = pk(32)
                .targetHp(finish).specEnergy(100)
                .nhV2Enabled(true).nhPhase("RANGE")
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        assertRoundTrip(Arrays.asList(range), cfg);
    }

    @Test
    public void runOnTickArbiterThrowsUnlessDryRun() throws Exception {
        System.clearProperty("roatz.dryrun");
        CombatScript script = new CombatScript(null, Object.class, null);
        assertThrows(IllegalStateException.class, () -> script.runOnTickArbiter(agsWindow()));
    }

    @Test
    public void applyTickDecisionExecutesTheComboTheWindowUsed() throws Exception {
        CombatScript script = new CombatScript(null, Object.class, null);
        script.selectedSpec = CombatScript.SpecWeapon.AGS;
        TickDecision.Config cfg = new TickDecision.Config();
        cfg.combo = CombatScript.SpecWeapon.CLAWS_GMAUL;
        cfg.autoSpec = true;
        TickDecision d = TickDecision.decide(agsWindow(), cfg);
        assertEquals(TickDecision.Intent.SPEC, d.intent);
        assertEquals(CombatScript.SpecWeapon.CLAWS_GMAUL, d.combo);
        script.applyTickDecision(10, d);
        assertEquals(CombatScript.SpecWeapon.CLAWS_GMAUL, script.selectedSpec);
    }

    private static void assertRoundTrip(List<CombatState> ticks, TickDecision.Config cfg) throws Exception {
        ReplayHarness.Report expected = ReplayHarness.run(ticks, cfg);
        CombatScript script = new CombatScript(null, Object.class, null);
        applyCfg(script, cfg);
        TickDecision.Config live = script.liveDecisionConfig();
        assertEquals(cfg.nhV2, live.nhV2);
        assertEquals(cfg.nhAutoSpec, live.nhAutoSpec);
        assertEquals(cfg.nhAutoGear, live.nhAutoGear);
        assertEquals(cfg.autoSpec, live.autoSpec);
        assertEquals(cfg.autoEat, live.autoEat);
        assertEquals(cfg.counterSpec, live.counterSpec);
        assertEquals(script.comboSpec(), live.combo);
        DryRun.resetForTest();
        List<TickDecision> liveDecisions = new ArrayList<>();
        for (CombatState s : ticks) {
            liveDecisions.add(script.runOnTickArbiter(s));
        }
        List<String> fromDecision = expected.actionSequence();
        List<String> fromLive = new ArrayList<>();
        for (TickDecision d : liveDecisions) {
            fromLive.add(d.intent + ":" + d.reason);
        }
        assertEquals(fromDecision, fromLive,
                "applyTickDecision TickDecision != ReplayHarness (same decide())");
        List<String> fromDryRun = dryRunIntents();
        assertFalse(fromDryRun.isEmpty() && !fromDecision.isEmpty());
        assertEquals(fromDecision, fromDryRun, "DryRun intent log != TickDecision sequence");
        assertNotNull(live);
    }

    private static List<String> dryRunIntents() {
        List<String> out = new ArrayList<>();
        for (String line : DryRun.actions()) {
            if (line != null && line.startsWith("intent ")) {
                out.add(line.substring("intent ".length()).trim());
            }
        }
        return out;
    }

    private static void applyCfg(CombatScript s, TickDecision.Config c) {
        s.autoSpecEnabled = c.autoSpec;
        s.nhV2Enabled = c.nhV2;
        s.nhAutoSpec = c.nhAutoSpec;
        s.nhAutoGearEnabled = c.nhAutoGear;
        s.autoEatEnabled = c.autoEat;
        s.selectedSpec = c.combo;
        s.nhKoHp = c.nhKoHp;
        s.damageTriggerMin = c.damageTriggerMin;
        s.counterSpecEnabled = c.counterSpec;
        s.agsMinSpecPct = c.minSpecPct;
        s.enabled = true;
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
        c.nhAutoGear = true;
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

    private static CombatState agsWindow() {
        ReplayHarness.Config cfg = pkCfg();
        int finish = TickDecision.expectedFinishHp(null, cfg);
        return pk(10)
                .targetHp(finish).specEnergy(100)
                .inActiveFight(true)
                .build();
    }

    private static CombatState outsideWindow() {
        return pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .hitsplatChangeTick(12)
                .agsSpecTick(-99)
                .build();
    }
}
