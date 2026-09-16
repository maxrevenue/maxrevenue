package com.sun.java.fontmgr;

/**
 * Current combat decision rules, evaluated against an immutable
 * {@link CombatState}. {@link CombatScript#onTick} consults this and
 * {@link ReplayHarness} runs the same function, so goldens describe the
 * shipped bot rather than a parallel model.
 *
 * <p>Extracted from {@link CombatScript#onTick} / {@code nhFireSpecFinish} /
 * {@code tryEatOffOpponentSpec} as they exist today — including spec-on-big-hit
 * waste (fresh splat only). Item 3 will tighten the window; these tests are
 * the before.
 *
 * <p>Does not mutate client state and does not send packets.
 */
public final class TickDecision {

    /** Must match {@code CombatScript.SPEC_COOLDOWN}. */
    public static final int SPEC_COOLDOWN = 2;

    public enum Intent { EAT, SPEC, HOLD }

    /**
     * Live flags for one tick. {@link CombatScript} builds this from HUD
     * toggles; the harness builds it from the golden's config.
     */
    public static class Config {
        public CombatScript.SpecWeapon combo = CombatScript.SpecWeapon.AGS_GMAUL;
        public int minSpecPct = 50;
        public int nhKoHp = 35;
        public int ourStr = 99;
        /** Combo/worn strength bonus. {@code <= 0} uses {@link MaxHitCalculator#strBonusForCombo}. */
        public int strBonus;
        /**
         * Strength-prayer multiplier. {@code <= 0} means no prayer (1.0), not piety.
         * Harness PK/NH helpers set piety explicitly.
         */
        public double prayerMult = MaxHitCalculator.PIETY_STR;
        public int stanceBonus = MaxHitCalculator.STANCE_AGGRESSIVE;
        /** 1.0 = max hit. Lower tightens {@link TickDecision#expectedFinishHp}. */
        public double accuracy = MaxHitCalculator.DEFAULT_ACCURACY;
        public int damageTriggerMin = 40;
        public boolean nhV2;
        public boolean nhAutoSpec;
        /**
         * NH mage→range→melee swaps. Default {@code false} matches
         * {@link CombatScript#nhAutoGearEnabled}. The NH kill window is
         * gated on this so Auto Spec cannot yank gear while Auto Gear is off
         * ({@code ed2f83c}).
         */
        public boolean nhAutoGear;
        public boolean autoSpec = true;
        public boolean autoEat = true;
        public boolean counterSpec;
        public long rngSeed = 1L;
    }

    public final Intent intent;
    /** Stable token: {@code dh-axe}, {@code opp-spec}, {@code kill-window}, {@code hardHit}, {@code bighit}, {@code hold}. */
    public final String reason;
    public final boolean killWindowOpen;
    public final boolean overheadWrong;
    public final boolean oneShotBracket;
    /**
     * Combo the window was estimated with. {@link CombatScript} executes
     * this rather than calling {@code comboSpec()} again, so estimate and
     * dump cannot diverge.
     */
    public final CombatScript.SpecWeapon combo;

    TickDecision(Intent intent, String reason, boolean killWindowOpen,
                 boolean overheadWrong, boolean oneShotBracket,
                 CombatScript.SpecWeapon combo) {
        this.intent = intent;
        this.reason = reason;
        this.killWindowOpen = killWindowOpen;
        this.overheadWrong = overheadWrong;
        this.oneShotBracket = oneShotBracket;
        this.combo = combo;
    }

    /**
     * Mutable across a replay so cooldown / eaten-anim / consumed-spec match
     * live onTick. Freshness of the splat itself lives on {@link CombatState}
     * ({@code hitsplatChangeTick} / {@code incomingChangeTick}).
     */
    static final class Session {
        int lastSpecTick = -99;
        int lastEatAnim = -1;
        int lastConsumedSpecAnim = -1;
    }

    public static TickDecision decide(CombatState s, Config cfg) {
        return decide(s, cfg, new Session());
    }

    static TickDecision decide(CombatState s, Config cfg, Session session) {
        if (s == null) {
            return new TickDecision(Intent.HOLD, "null", false, false, false, null);
        }
        if (cfg == null) cfg = new Config();
        if (session == null) session = new Session();
        boolean window = killWindowOpen(s, cfg);
        boolean wrongOh = overheadWrong(s);
        boolean oneShot = s.inDhDanger;
        CombatScript.SpecWeapon combo = cfg.combo;

        // Survive first — CombatScript.onTick returns after these eats.
        if (cfg.nhV2 && s.inDhDanger && AnimationDb.isDharokAnimation(s.lastTargetAnim)) {
            return new TickDecision(Intent.EAT, "dh-axe", window, wrongOh, oneShot, combo);
        }
        if (cfg.nhV2 && cfg.autoEat && shouldEatOffOpponentSpec(s, session)) {
            session.lastEatAnim = s.lastTargetAnim;
            return new TickDecision(Intent.EAT, "opp-spec", window, wrongOh, oneShot, combo);
        }

        boolean funded = s.specEnergy >= cfg.minSpecPct;
        boolean cooling = s.tick - session.lastSpecTick <= SPEC_COOLDOWN;
        boolean busy = s.specSequenceBusy;
        boolean specEnabled = cfg.autoSpec || (cfg.nhV2 && cfg.nhAutoSpec);
        int trigger = Math.max(1, cfg.damageTriggerMin);

        // Counter-spec dump. Immediate (no Humanizer jitter). Survive already ran.
        if (cfg.counterSpec && specEnabled && funded && !cooling && !busy
                && AnimationDb.isSpecAnimation(s.lastTargetAnim)
                && s.lastTargetAnim != session.lastConsumedSpecAnim) {
            session.lastSpecTick = s.tick;
            session.lastConsumedSpecAnim = s.lastTargetAnim;
            return new TickDecision(Intent.SPEC, "opp-spec", window, wrongOh, oneShot, combo);
        }

        if (specEnabled && window && funded && !cooling && !busy) {
            session.lastSpecTick = s.tick;
            return new TickDecision(Intent.SPEC, "kill-window", window, wrongOh, oneShot, combo);
        }

        // Incoming splat ≥ damageTriggerMin this tick (live hardHit).
        boolean hardHit = s.incomingChangeTick == s.tick && s.lastIncomingDmg >= trigger;
        if (cfg.autoSpec && s.inActiveFight && hardHit && funded && !cooling && !busy) {
            session.lastSpecTick = s.tick;
            return new TickDecision(Intent.SPEC, "hardHit", window, wrongOh, oneShot, combo);
        }

        // Outgoing splat ≥ trigger THIS tick, not a held reading. Matches
        // CombatScript outBigHit (fresh splat, agsSpecTick>6, not our spec anim).
        boolean bighit = cfg.autoSpec
                && hasCombatTarget(s)
                && s.inActiveFight
                && s.hitsplatChangeTick == s.tick
                && s.lastHitsplatDmg >= trigger
                && !busy
                && funded
                && !cooling
                && (s.tick - s.agsSpecTick > 6)
                && !AnimationDb.isSpecAnimation(s.lastAnimSeen);
        if (bighit) {
            session.lastSpecTick = s.tick;
            return new TickDecision(Intent.SPEC, "bighit", window, wrongOh, oneShot, combo);
        }

        return new TickDecision(Intent.HOLD, "hold", window, wrongOh, oneShot, combo);
    }

    /**
     * PK: published {@link CombatState#inKillRange} while
     * {@link CombatState#inActiveFight}. NH finish: Auto Gear on, target HP
     * at or below {@link #nhSpecFinishHp(CombatState, Config)}, spec weapon
     * carried, not on a mage staff. Auto Gear off closes the NH window —
     * the spec used to fire only from {@code runNhTick}'s melee-commit
     * branch, which is gated on {@code nhAutoGearEnabled} ({@code ed2f83c}).
     */
    public static boolean killWindowOpen(CombatState s, Config cfg) {
        if (s == null || s.targetHp <= 0) return false;
        if (cfg != null && cfg.nhV2 && cfg.nhAutoSpec) {
            if (!cfg.nhAutoGear) return false;
            if (s.mageStaffEquipped || !s.hasSpecWeapon) return false;
            return s.targetHp <= nhSpecFinishHp(s, cfg);
        }
        return s.inKillRange && s.inActiveFight;
    }

    /** Same table as {@code CombatScript.estimateOurSpecDamage}. */
    public static int estimateSpecDamage(CombatScript.SpecWeapon combo, int boostedStr) {
        return estimateSpecDamage(combo, boostedStr, 0, MaxHitCalculator.PIETY_STR,
                MaxHitCalculator.STANCE_AGGRESSIVE);
    }

    public static int estimateSpecDamage(CombatScript.SpecWeapon combo, int boostedStr,
                                         int strBonus, double prayerMult, int stanceBonus) {
        int str = boostedStr > 0 ? boostedStr : 99;
        int bonus = strBonus > 0 ? strBonus : MaxHitCalculator.strBonusForCombo(combo);
        double pray = prayerMult > 0 ? prayerMult : 1.0;
        int stance = stanceBonus >= 0 ? stanceBonus : MaxHitCalculator.STANCE_AGGRESSIVE;
        if (combo == CombatScript.SpecWeapon.CLAWS_GMAUL) {
            return MaxHitCalculator.agsSpecMaxHit(str, bonus, pray, stance);
        }
        if (combo == CombatScript.SpecWeapon.VOIDWAKER
                || combo == CombatScript.SpecWeapon.VOIDWAKER_GMAUL) {
            return MaxHitCalculator.baseMaxHit(str, bonus, pray, stance) + 15;
        }
        if (combo == CombatScript.SpecWeapon.DMACE
                || combo == CombatScript.SpecWeapon.DMACE_GMAUL) {
            return MaxHitCalculator.specMaxHit(str, bonus, pray, stance,
                    MaxHitCalculator.DMACE_SPEC_MULT);
        }
        if (combo == CombatScript.SpecWeapon.STATIUS
                || combo == CombatScript.SpecWeapon.STATIUS_GMAUL) {
            return MaxHitCalculator.specMaxHit(str, bonus, pray, stance,
                    MaxHitCalculator.STATIUS_SPEC_MULT);
        }
        if (combo == CombatScript.SpecWeapon.DBOW_AXES) {
            return MaxHitCalculator.baseMaxHit(str, bonus, pray, stance)
                    + MaxHitCalculator.THREAT_MARGIN;
        }
        if (combo == CombatScript.SpecWeapon.VLS) {
            return MaxHitCalculator.specMaxHit(str, bonus, pray, stance,
                    MaxHitCalculator.VLS_SPEC_MULT);
        }
        if (combo == CombatScript.SpecWeapon.GMAUL) {
            return MaxHitCalculator.gmaulSpecMaxHit(str, bonus, pray, stance);
        }
        return MaxHitCalculator.agsSpecMaxHit(str, bonus, pray, stance);
    }

    public static int estimateSpecDamage(CombatState s, Config cfg) {
        if (cfg == null) cfg = new Config();
        return estimateSpecDamage(cfg.combo, effectiveStr(s, cfg), cfg.strBonus,
                cfg.prayerMult, cfg.stanceBonus);
    }

    public static int expectedFinishHp(CombatState s, Config cfg) {
        if (cfg == null) cfg = new Config();
        int spec = estimateSpecDamage(s, cfg);
        int expected = MaxHitCalculator.expectedHit(spec, cfg.accuracy);
        int cap = (s != null && s.ourMaxHp > 0) ? s.ourMaxHp : 99;
        int floor = cfg.nhKoHp > 0 ? cfg.nhKoHp : 0;
        if (cfg.nhV2 && cfg.nhAutoSpec) {
            return Math.min(cap, Math.max(floor, expected > 0 ? expected : floor));
        }
        return Math.min(cap, expected);
    }

    /**
     * Boosted strength for the window: the per-tick snapshot when readable,
     * otherwise the config fallback (default 99).
     */
    public static int effectiveStr(CombatState s, Config cfg) {
        if (s != null && s.ourStr > 0) return s.ourStr;
        if (cfg != null && cfg.ourStr > 0) return cfg.ourStr;
        return 99;
    }

    public static int nhSpecFinishHp(Config cfg) {
        return nhSpecFinishHp(null, cfg);
    }

    public static int nhSpecFinishHp(CombatState s, Config cfg) {
        return expectedFinishHp(s, cfg);
    }

    public static boolean overheadWrong(CombatState s) {
        AnimationDb.AttackStyle expected = s.opponentWeaponStyle();
        if (expected == AnimationDb.AttackStyle.UNKNOWN) return false;
        return !expected.name().equals(s.ourOverhead);
    }

    private static boolean hasCombatTarget(CombatState s) {
        return s.targetName != null && !s.targetName.isEmpty();
    }

    private static boolean shouldEatOffOpponentSpec(CombatState s, Session session) {
        if (!AnimationDb.isSpecAnimation(s.lastTargetAnim)) return false;
        if (AnimationDb.isDharokAnimation(s.lastTargetAnim) && !AnimationDb.isSpecAnimation(s.lastTargetAnim)) {
            return false;
        }
        if (s.lastTargetAnim == session.lastEatAnim) return false;
        int threat = MaxHitCalculator.opponentSpecThreat(s.lastTargetAnim);
        if (threat <= 0) threat = 60;
        int hp = s.ourHp;
        if (hp <= 0 || hp >= 92 || hp >= threat + 8) return false;
        if (hp >= threat && hp >= CombatScript.DH_EAT_BAND_MIN) return false;
        return true;
    }
}
