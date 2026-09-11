package com.sun.java.fontmgr;

/**
 * Immutable read-model of everything {@link CombatScript} observes/decides
 * during one game tick.
 *
 * <p><b>Why.</b> The script's live fields are a few dozen {@code volatile}s
 * written one at a time while the tick runs. Any outside reader (the Swing HUD,
 * the command socket, the shared-memory publisher) therefore saw a *torn* tick:
 * {@code targetHp} from tick N next to {@code inKillRange} from tick N-1, which
 * showed up as "KO IN KILL RANGE" on a target that had already died, HP labels
 * that flickered backwards, etc.
 *
 * <p>{@link CombatScript#onTick(int)} finishes by publishing one fully built
 * {@code CombatState} through a single {@code volatile} write, so every reader
 * gets a consistent snapshot: either all of tick N or all of tick N-1, never a
 * blend. Because the payload is immutable it is safe to hand to the Swing EDT
 * and to keep across ticks.
 *
 * <p>The snapshot is a <em>read-model only</em>: it never feeds decisions back
 * into the combat sequencing (that would make the timing depend on when the HUD
 * happened to look). Requirements are captured in the {@link Builder} below.
 */
public final class CombatState {

    /** Monotonic snapshot id. Distinct values let readers detect a fresh tick. */
    public final long seq;
    /** Game tick the snapshot was published for, or {@code -1} before the first. */
    public final int tick;

    // ── Progress / HUD line ──────────────────────────────────────────────────
    /** Last action label, e.g. {@code DH_AXE@1234}. Never null. */
    public final String lastAction;

    // ── Target ───────────────────────────────────────────────────────────────
    /** Target display name, or "" when nobody is targeted. Never null. */
    public final String targetName;
    /** Target HP, or {@code -1} when unknown. */
    public final int targetHp;
    /** Target max HP, or {@code -1} when unknown. */
    public final int targetMaxHp;
    /** True while we consider the target inside our KO range. */
    public final boolean inKillRange;
    /** True while we believe the opponent is on a Dharok's set. */
    public final boolean opponentIsDh;
    /**
     * Opponent's worn gear for this tick. Never null; {@link OpponentLoadout#empty()}
     * when nobody is targeted or the equipment array was unreadable.
     *
     * <p>{@link #opponentIsDh} and the opponent weapon style are both derived from
     * this, but the whole loadout is kept so readers get the weapon/armour the
     * opponent is actually wearing instead of only the one pre-digested flag.
     */
    public final OpponentLoadout opponentLoadout;
    /** True while we think we are the DH set's one-shot victim. */
    public final boolean inDhDanger;
    /** PvP fight in progress (not merely "a target is set"). */
    public final boolean inActiveFight;

    // ── Animations / hitsplats ───────────────────────────────────────────────
    /** Last animation id seen on the target. */
    public final int lastTargetAnim;
    /** Last hitsplat damage we read (ours or theirs, whichever was newest). */
    public final int lastHitsplatDmg;
    /** Type of that hitsplat. */
    public final int lastHitsplatType;
    /** Most recent incoming damage, or {@code -1}. */
    public final int lastIncomingDmg;
    /** Our own current animation id, or {@code -1}. */
    public final int localAnim;
    /** Last animation id seen on us at all. */
    public final int lastAnimSeen;

    // ── Special attack / estimates ───────────────────────────────────────────
    /** Special attack energy percent, or {@code -1} when unreadable. */
    public final int specEnergy;
    /** Estimated max hit of our current weapon, or {@code -1}. */
    public final int estimatedOurMaxHit;
    /** Estimated max hit of an opposing Dharok's, or {@code -1}. */
    public final int estimatedOppDhHit;

    // ── DH state machine ─────────────────────────────────────────────────────
    /** Ko stack in flight (axe swung, waiting to land). */
    public final boolean pendingDhStack;
    /** Stack armed for this cycle. */
    public final boolean dharokStackArmed;
    /** Whip + defender swap in flight. */
    public final boolean pendingDhWhipDef;
    /** Axe re-swing queued behind a completed stack. */
    public final boolean pendingDhAxeAfterStack;
    /** Must eat before the next DH attack. */
    public final boolean pendingDhMustEat;
    /** Last eat tier chosen by the DH band logic. */
    public final int lastDhEatTier;
    /** Tick of the last DH axe swing, or {@code -99}. */
    public final int lastDhAxeTick;
    /** Tick of the last DH spec-survive eat, or {@code -99}. */
    public final int lastDhSpecEatTick;

    // ── NH / prayer ──────────────────────────────────────────────────────────
    /** Current NH phase label. Never null. */
    public final String nhPhase;
    /** Freeze ticks believed to remain on the target. */
    public final int nhFreezeTicksLeft;
    /** NH V2 engine enabled (read-side view of the config at publish time). */
    public final boolean nhV2Enabled;
    /**
     * Which branch the defensive-prayer logic took this tick — a short, stable
     * token ({@code anim}, {@code gear-mage}, {@code gear-stable},
     * {@code gear-corr}, {@code bait-hold}, {@code hit}, {@code hit-mem},
     * {@code raw}, {@code held}, {@code none}), or "" when it did not run.
     * Never null.
     *
     * <p>Exists so a {@link TickRecorder} session can be aggregated after the
     * fact — the trace, not the outcome, is what shows whether a change to the
     * prayer logic actually took effect.
     */
    public final String defPrayTrace;

    // ── Diagnostics ──────────────────────────────────────────────────────────
    /** Compact debug string, or "" when overlay detail is off. Never null. */
    public final String debugState;

    private CombatState(Builder b) {
        this.seq = b.seq;
        this.tick = b.tick;
        this.lastAction = b.lastAction;
        this.targetName = b.targetName;
        this.targetHp = b.targetHp;
        this.targetMaxHp = b.targetMaxHp;
        this.inKillRange = b.inKillRange;
        this.opponentIsDh = b.opponentIsDh;
        this.opponentLoadout = b.opponentLoadout;
        this.inDhDanger = b.inDhDanger;
        this.inActiveFight = b.inActiveFight;
        this.lastTargetAnim = b.lastTargetAnim;
        this.lastHitsplatDmg = b.lastHitsplatDmg;
        this.lastHitsplatType = b.lastHitsplatType;
        this.lastIncomingDmg = b.lastIncomingDmg;
        this.localAnim = b.localAnim;
        this.lastAnimSeen = b.lastAnimSeen;
        this.specEnergy = b.specEnergy;
        this.estimatedOurMaxHit = b.estimatedOurMaxHit;
        this.estimatedOppDhHit = b.estimatedOppDhHit;
        this.pendingDhStack = b.pendingDhStack;
        this.dharokStackArmed = b.dharokStackArmed;
        this.pendingDhWhipDef = b.pendingDhWhipDef;
        this.pendingDhAxeAfterStack = b.pendingDhAxeAfterStack;
        this.pendingDhMustEat = b.pendingDhMustEat;
        this.lastDhEatTier = b.lastDhEatTier;
        this.lastDhAxeTick = b.lastDhAxeTick;
        this.lastDhSpecEatTick = b.lastDhSpecEatTick;
        this.nhPhase = b.nhPhase;
        this.nhFreezeTicksLeft = b.nhFreezeTicksLeft;
        this.nhV2Enabled = b.nhV2Enabled;
        this.defPrayTrace = b.defPrayTrace;
        this.debugState = b.debugState;
    }

    /** The "nothing observed yet" snapshot, so readers never see null. */
    private static final CombatState EMPTY = new Builder(0, -1).build();

    /** Snapshot published before the first tick (or for a freshly built script). */
    public static CombatState empty() {
        return EMPTY;
    }

    // ── Derived read-only helpers (no state, no side effects) ────────────────

    /** True when a target HP reading is available. */
    public boolean hasTargetHp() {
        return targetHp > 0;
    }

    /** Worn weapon id of the opponent, or 0 when unknown. */
    public int opponentWeaponId() {
        return opponentLoadout.weaponId();
    }

    /** Attack style implied by the opponent's worn weapon. Never null. */
    public AnimationDb.AttackStyle opponentWeaponStyle() {
        return opponentLoadout.weaponStyle();
    }

    /** True while a DH KO swing is in flight or armed. */
    public boolean dhSwinging() {
        return pendingDhStack || dharokStackArmed;
    }

    /** True when the spec orb could fund a spec at {@code minPct}. */
    public boolean specReady(int minPct) {
        return specEnergy >= 0 && specEnergy >= minPct;
    }

    /** Action label safe for the HUD ("Ready" instead of a blank/absent label). */
    public String actionLabel() {
        return lastAction == null || lastAction.isEmpty() ? "Ready" : lastAction;
    }

    @Override
    public String toString() {
        return "CombatState# " + seq + " tick=" + tick + " action=" + actionLabel()
                + " target=" + targetName + " hp=" + targetHp + " ko=" + inKillRange
                + " spec=" + specEnergy;
    }

    /**
     * Mutable accumulator. Only {@link CombatScript} builds these, exactly once
     * per tick, on the tick thread; the builder is never shared.
     */
    static final class Builder {
        private final long seq;
        private final int tick;

        private String lastAction = "none";
        private String targetName = "";
        private int targetHp = -1;
        private int targetMaxHp = -1;
        private boolean inKillRange = false;
        private boolean opponentIsDh = false;
        private OpponentLoadout opponentLoadout = OpponentLoadout.empty();
        private boolean inDhDanger = false;
        private boolean inActiveFight = false;
        private int lastTargetAnim = -1;
        private int lastHitsplatDmg = -1;
        private int lastHitsplatType = -1;
        private int lastIncomingDmg = -1;
        private int localAnim = -1;
        private int lastAnimSeen = -1;
        private int specEnergy = -1;
        private int estimatedOurMaxHit = -1;
        private int estimatedOppDhHit = -1;
        private boolean pendingDhStack = false;
        private boolean dharokStackArmed = false;
        private boolean pendingDhWhipDef = false;
        private boolean pendingDhAxeAfterStack = false;
        private boolean pendingDhMustEat = false;
        private int lastDhEatTier = 0;
        private int lastDhAxeTick = -99;
        private int lastDhSpecEatTick = -99;
        private String nhPhase = "IDLE";
        private int nhFreezeTicksLeft = 0;
        private boolean nhV2Enabled = false;
        private String defPrayTrace = "";
        private String debugState = "";

        Builder(long seq, int tick) {
            this.seq = seq;
            this.tick = tick;
        }

        Builder lastAction(String v)                { this.lastAction = orDefault(v, "none"); return this; }
        Builder targetName(String v)                { this.targetName = orDefault(v, ""); return this; }
        Builder targetHp(int v)                     { this.targetHp = v; return this; }
        Builder targetMaxHp(int v)                  { this.targetMaxHp = v; return this; }
        Builder inKillRange(boolean v)              { this.inKillRange = v; return this; }
        Builder opponentIsDh(boolean v)             { this.opponentIsDh = v; return this; }
        Builder opponentLoadout(OpponentLoadout v)  { this.opponentLoadout = v == null ? OpponentLoadout.empty() : v; return this; }
        Builder inDhDanger(boolean v)               { this.inDhDanger = v; return this; }
        Builder inActiveFight(boolean v)            { this.inActiveFight = v; return this; }
        Builder lastTargetAnim(int v)               { this.lastTargetAnim = v; return this; }
        Builder lastHitsplatDmg(int v)              { this.lastHitsplatDmg = v; return this; }
        Builder lastHitsplatType(int v)             { this.lastHitsplatType = v; return this; }
        Builder lastIncomingDmg(int v)              { this.lastIncomingDmg = v; return this; }
        Builder localAnim(int v)                    { this.localAnim = v; return this; }
        Builder lastAnimSeen(int v)                 { this.lastAnimSeen = v; return this; }
        Builder specEnergy(int v)                   { this.specEnergy = v; return this; }
        Builder estimatedOurMaxHit(int v)           { this.estimatedOurMaxHit = v; return this; }
        Builder estimatedOppDhHit(int v)            { this.estimatedOppDhHit = v; return this; }
        Builder pendingDhStack(boolean v)           { this.pendingDhStack = v; return this; }
        Builder dharokStackArmed(boolean v)         { this.dharokStackArmed = v; return this; }
        Builder pendingDhWhipDef(boolean v)         { this.pendingDhWhipDef = v; return this; }
        Builder pendingDhAxeAfterStack(boolean v)   { this.pendingDhAxeAfterStack = v; return this; }
        Builder pendingDhMustEat(boolean v)         { this.pendingDhMustEat = v; return this; }
        Builder lastDhEatTier(int v)                { this.lastDhEatTier = v; return this; }
        Builder lastDhAxeTick(int v)                { this.lastDhAxeTick = v; return this; }
        Builder lastDhSpecEatTick(int v)            { this.lastDhSpecEatTick = v; return this; }
        Builder nhPhase(String v)                   { this.nhPhase = orDefault(v, "IDLE"); return this; }
        Builder nhFreezeTicksLeft(int v)            { this.nhFreezeTicksLeft = v; return this; }
        Builder nhV2Enabled(boolean v)              { this.nhV2Enabled = v; return this; }
        Builder defPrayTrace(String v)              { this.defPrayTrace = orDefault(v, ""); return this; }
        Builder debugState(String v)                { this.debugState = orDefault(v, ""); return this; }

        CombatState build() {
            return new CombatState(this);
        }

        private static String orDefault(String v, String fallback) {
            return v == null ? fallback : v;
        }
    }
}
