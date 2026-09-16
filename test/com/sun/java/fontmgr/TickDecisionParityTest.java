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

/**
 * The replay goldens are the bot: the real onTick arbiter, under
 * {@code -Droatz.dryrun=true}, must emit the same intent sequence
 * {@link TickDecision} produces for the same {@link CombatState}s.
 */
public class TickDecisionParityTest {

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
    public void dryRunOnTickArbiterMatchesTickDecisionForPkWindow() throws Exception {
        assertParity(Arrays.asList(agsWindow()), pkCfg());
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForBighitWaste() throws Exception {
        assertParity(Arrays.asList(bighit()), pkCfg());
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForStaleSplat() throws Exception {
        CombatState stale = pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .hitsplatChangeTick(5)
                .build();
        assertParity(Arrays.asList(stale), pkCfg());
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForHardHit() throws Exception {
        CombatState s = pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastIncomingDmg(50).incomingChangeTick(12)
                .inActiveFight(true)
                .build();
        assertParity(Arrays.asList(s), pkCfg());
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForNhSurviveThenFinish() throws Exception {
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
        assertParity(Arrays.asList(eat, frozen, ko), cfg);
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForDhEat() throws Exception {
        CombatState swing = pk(1)
                .ourHp(15).ourMaxHp(99)
                .inDhDanger(true).estimatedOppDhHit(80)
                .lastTargetAnim(2066)
                .opponentIsDh(true)
                .opponentLoadout(TickTsv.loadout(1, "MELEE", true))
                .targetHp(70).inKillRange(true).specEnergy(100)
                .inActiveFight(true)
                .build();
        assertParity(Arrays.asList(swing), nh());
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForUnfundedWindow() throws Exception {
        CombatState s = pk(3)
                .targetHp(40).inKillRange(true).specEnergy(0)
                .inActiveFight(true)
                .build();
        assertParity(Arrays.asList(s), pkCfg());
    }

    @Test
    public void dryRunOnTickArbiterMatchesTickDecisionForDrainedStr() throws Exception {
        ReplayHarness.Config cfg = nh();
        int drainedHp = TickDecision.nhSpecFinishHp(
                pk(10).ourStr(1).ourMaxHp(99).targetHp(1).build(), cfg);
        CombatState s = pk(10)
                .targetHp(drainedHp + 1).ourStr(1).ourMaxHp(99)
                .specEnergy(100).nhV2Enabled(true)
                .inActiveFight(true).hasSpecWeapon(true)
                .build();
        assertParity(Arrays.asList(s), cfg);
    }

    private static void assertParity(List<CombatState> ticks, TickDecision.Config cfg) throws Exception {
        ReplayHarness.Report expected = ReplayHarness.run(ticks, cfg);
        CombatScript script = new CombatScript(null, Object.class, null);
        applyCfg(script, cfg);
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
        assertEquals(fromDecision, fromLive, "onTick arbiter TickDecision != ReplayHarness");
        List<String> fromDryRun = dryRunIntents();
        assertFalse(fromDryRun.isEmpty() && !fromDecision.isEmpty());
        assertEquals(fromDecision, fromDryRun, "DryRun intent log != TickDecision sequence");
        assertNotNull(script.liveDecisionConfig());
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
        return pk(10)
                .targetHp(70).inKillRange(true).specEnergy(100)
                .estimatedOurMaxHit(77).inActiveFight(true)
                .build();
    }

    private static CombatState bighit() {
        return pk(12)
                .targetHp(90).inKillRange(false).specEnergy(100)
                .lastHitsplatDmg(50).inActiveFight(true)
                .hitsplatChangeTick(12)
                .agsSpecTick(-99)
                .build();
    }
}
