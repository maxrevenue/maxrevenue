package com.sun.java.fontmgr;

/**
 * Current combat decision rules, evaluated against an immutable
 * {@link CombatState}. This is the function {@link ReplayHarness} runs.
 *
 * <p>Extracted from {@link CombatScript#onTick} / {@code nhFireSpecFinish} /
 * {@code tryEatOffOpponentSpec} as they exist today — including spec-on-big-hit
 * waste. Item 3 will tighten the window; these tests are the before.
 *
 * <p>Does not mutate client state and does not send packets.
 */
public final class TickDecision {

    /** Must match {@code CombatScript.SPEC_COOLDOWN}. */
    public static final int SPEC_COOLDOWN = 2;

    public enum Intent { EAT, SPEC, HOLD }

    public final Intent intent;
    /** Stable token: {@code dh-axe}, {@code opp-spec}, {@code kill-window}, {@code bighit}, {@code hold}. */
    public final String reason;
    public final boolean killWindowOpen;
    public final boolean overheadWrong;
    public final boolean oneShotBracket;

    TickDecision(Intent intent, String reason, boolean killWindowOpen,
                 boolean overheadWrong, boolean oneShotBracket) {
        this.intent = intent;
        this.reason = reason;
        this.killWindowOpen = killWindowOpen;
        this.overheadWrong = overheadWrong;
        this.oneShotBracket = oneShotBracket;
    }

    /** Mutable across a replay so cooldown / eaten-anim match live onTick. */
    static final class Session {
        int lastSpecTick = -99;
        int lastEatAnim = -1;
    }

    public static TickDecision decide(CombatState s, ReplayHarness.Config cfg) {
        return decide(s, cfg, new Session());
    }

    static TickDecision decide(CombatState s, ReplayHarness.Config cfg, Session session) {
        if (s == null) {
            return new TickDecision(Intent.HOLD, "null", false, false, false);
        }
        boolean window = killWindowOpen(s, cfg);
        boolean wrongOh = overheadWrong(s);
        boolean oneShot = s.inDhDanger;

        // Survive first — CombatScript.onTick returns after these eats.
        if (cfg.nhV2 && s.inDhDanger && AnimationDb.isDharokAnimation(s.lastTargetAnim)) {
            return new TickDecision(Intent.EAT, "dh-axe", window, wrongOh, oneShot);
        }
        if (cfg.nhV2 && cfg.autoEat && shouldEatOffOpponentSpec(s, session)) {
            session.lastEatAnim = s.lastTargetAnim;
            return new TickDecision(Intent.EAT, "opp-spec", window, wrongOh, oneShot);
        }

        boolean funded = s.specEnergy >= cfg.minSpecPct;
        boolean cooling = s.tick - session.lastSpecTick <= SPEC_COOLDOWN;
        boolean specEnabled = cfg.autoSpec || (cfg.nhV2 && cfg.nhAutoSpec);

        if (specEnabled && window && funded && !cooling) {
            session.lastSpecTick = s.tick;
            return new TickDecision(Intent.SPEC, "kill-window", window, wrongOh, oneShot);
        }

        // Current waste path: auto-spec on an outgoing splat ≥ damageTriggerMin (40).
        if (cfg.autoSpec && s.inActiveFight && s.lastHitsplatDmg >= 40 && funded && !cooling) {
            session.lastSpecTick = s.tick;
            return new TickDecision(Intent.SPEC, "bighit", window, wrongOh, oneShot);
        }

        return new TickDecision(Intent.HOLD, "hold", window, wrongOh, oneShot);
    }

    /**
     * PK: published {@link CombatState#inKillRange}. NH finish: target HP at or
     * below {@link #nhSpecFinishHp} (same formula as CombatScript).
     */
    public static boolean killWindowOpen(CombatState s, ReplayHarness.Config cfg) {
        if (s == null || s.targetHp <= 0) return false;
        if (cfg.nhV2 && cfg.nhAutoSpec) {
            return s.targetHp <= nhSpecFinishHp(cfg);
        }
        return s.inKillRange;
    }

    /** Same table as {@code CombatScript.estimateOurSpecDamage}. */
    public static int estimateSpecDamage(CombatScript.SpecWeapon combo, int boostedStr) {
        int str = boostedStr > 0 ? boostedStr : 99;
        if (combo == CombatScript.SpecWeapon.CLAWS_GMAUL) {
            return MaxHitCalculator.agsSpecMaxHit(str);
        }
        if (combo == CombatScript.SpecWeapon.VOIDWAKER
                || combo == CombatScript.SpecWeapon.VOIDWAKER_GMAUL) {
            return MaxHitCalculator.baseMaxHit(str, 100) + 15;
        }
        if (combo == CombatScript.SpecWeapon.DMACE
                || combo == CombatScript.SpecWeapon.DMACE_GMAUL
                || combo == CombatScript.SpecWeapon.STATIUS
                || combo == CombatScript.SpecWeapon.STATIUS_GMAUL) {
            return MaxHitCalculator.statiusSpecMaxHit(str);
        }
        if (combo == CombatScript.SpecWeapon.DBOW_AXES) {
            return MaxHitCalculator.baseMaxHit(str, 100) + MaxHitCalculator.THREAT_MARGIN;
        }
        if (combo == CombatScript.SpecWeapon.VLS) {
            return MaxHitCalculator.baseMaxHit(str, 75) + 10;
        }
        return MaxHitCalculator.agsSpecMaxHit(str);
    }

    public static int nhSpecFinishHp(ReplayHarness.Config cfg) {
        int spec = estimateSpecDamage(cfg.combo, cfg.ourStr);
        return Math.min(99, Math.max(cfg.nhKoHp, spec > 0 ? spec : cfg.nhKoHp));
    }

    public static boolean overheadWrong(CombatState s) {
        AnimationDb.AttackStyle expected = s.opponentWeaponStyle();
        if (expected == AnimationDb.AttackStyle.UNKNOWN) return false;
        return !expected.name().equals(s.ourOverhead);
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
