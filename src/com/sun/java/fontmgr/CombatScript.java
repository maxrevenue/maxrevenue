package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;

/**
 * CombatScript — tick-driven PK automation.
 *
 * Each game tick it:
 *   1. Reads the player's current target and its animation (via Actor.sequence)
 *   2. Reads the latest incoming hitsplat
 *   3. Finishes in-flight keybind sequences (AGS wield→spec, gmaul follow, sanfew)
 *   4. Queues actions by priority, then fires them in order:
 *        EMERGENCY_HEAL > PRAYER_SWITCH > SPECIAL_ATTACK > GEAR_SWITCH > RE_ATTACK
 *
 * AGS/Gmaul dumps and eats are keybind-only. Auto-eat / auto-spec stay off
 * unless the player turns those flags back on.
 *
 * Supported spec weapons:
 *   - Granite Maul (Gmaul)  — animation 7514 / 1667 / 7328
 *   - Armadyl Godsword (AGS) — animation 7644
 *     1-tick: wield → sendClickingButton(5004) → re-attack in the same cycle.
 *   - AGS → Gmaul combo: piety + both specs in one tick.
 *   - Statius warhammer → Gmaul: smash (35%) then gmaul (50%) the next tick.
 *
 * All config is mutated live from OverlayUI.
 */
public class CombatScript implements TickListener {

    // ── Spec weapon type ─────────────────────────────────────────────────────
    public enum EatContext { AUTO, SAFETY, KILL, MANUAL }

    public enum SpecWeapon {
        GMAUL         ("Gmaul",     7328),
        AGS           ("AGS",       7644),
        AGS_GMAUL     ("AGS/Gmaul", 7644),
        STATIUS       ("Statius",   1378),
        STATIUS_GMAUL ("SW/Gmaul",  1378),
        DMACE         ("DMace",      1060),
        DMACE_GMAUL   ("DMace/Gmaul",1060),
        VLS           ("VLS",        7515),
        DBOW_AXES     ("DBow/Axes",   426),
        CLAWS_GMAUL   ("Claws/Gmaul", 7642);

        public final String label;
        public final int    defaultAnim;

        SpecWeapon(String label, int defaultAnim) {
            this.label       = label;
            this.defaultAnim = defaultAnim;
        }
    }

    /** Hybrid baseline gear for v7 AGS/Gmaul combo (Eclipse + Blood Moon + Barrows gloves). */
    private static final String[] HYBRID_GEAR_NAMES = {
        "Eclipse moon helm", "Eclipse moon chestplate", "Eclipse moon tassets",
        "Blood moon helm", "Blood moon chestplate", "Blood moon tassets",
        "Barrows gloves"
    };

    // ── Priority tiers ───────────────────────────────────────────────────────
    public enum ActionPriority {
        EMERGENCY_HEAL(1),
        PRAYER_SWITCH(2),
        SPECIAL_ATTACK(3),
        GEAR_SWITCH(4),
        RE_ATTACK(5);
        private final int level;
        ActionPriority(int level) { this.level = level; }
        public int getLevel()     { return level; }
    }

    public static class QueuedAction implements Comparable<QueuedAction> {
        public final ActionPriority priority;
        public final String         name;
        public final Runnable       action;
        public QueuedAction(ActionPriority p, String n, Runnable a) { priority = p; name = n; action = a; }
        @Override public int compareTo(QueuedAction o) {
            return Integer.compare(priority.getLevel(), o.priority.getLevel());
        }
    }

    private final java.util.List<QueuedAction> actionQueue = new ArrayList<>();

    // ── Config (live-mutated by OverlayUI) ──────────────────────────────────
    public volatile boolean    enabled              = false;
    public volatile int        animTriggerAnim      = SpecWeapon.GMAUL.defaultAnim;
    public volatile int        damageTriggerMin     = 40;
    public volatile int        specWeaponSlot       = 0;
    public volatile boolean    animTriggerEnabled   = false;
    public volatile boolean    damageTriggerEnabled = false;
    public volatile boolean    autoVengEnabled      = false;
    /** When true, vengeance only fires during spec combos — never on a passive tick timer. */
    public volatile boolean    vengWithSpecOnly     = false;
    /** Auto-spec dumps the current combo (default claws→gmaul) when you have a target. */
    public volatile boolean    autoSpecEnabled      = false;
 
    /** Which spec weapon to use when a trigger fires. */
    public volatile SpecWeapon selectedSpec = SpecWeapon.CLAWS_GMAUL;

    // AGS-specific: require minimum spec energy before attempting AGS spec
    public volatile int        agsMinSpecPct = 50;
    /** Dragon mace (BH) spec is 15%. */
    public volatile int        dmaceMinSpecPct = 15;
    /** DMace max in this gear. Gmaul only if the splat is at least dmaceHighHitMin. */
    public volatile int        dmaceMaxHit = 63;
    public volatile int        dmaceHighHitMin = 50;
    /** Live max AGS splat in this gear. Used for KO math and "hit high" gmaul. */
    public volatile int        agsMaxHit = 77;
    /** Gmaul follow-up only if the AGS splat is at least this. */
    public volatile int        agsHighHitMin = 40;
    /** Gmaul after claws only if the combined claws splat is at least this. */
    public volatile int        clawsHighHitMin = 50;

    // v7 failsafe flags (hardcoded by HardcodedCombatAgent)
    public volatile boolean disableSwapDelay      = true;
    public volatile boolean disableDistanceChecks = true;
    public volatile boolean overheadChecksEnabled = true;
    /** Fire our combo the tick the target plays a spec animation. Off — keybind only. */
    public volatile boolean counterSpecEnabled    = false;
    /** Auto protect from mage/range/melee based on target animation. */
    public volatile boolean defensivePrayersEnabled = true;
    /** Auto-enable Protect Item whenever we step into a PvP (danger) zone. */
    public volatile boolean autoProtectItemEnabled = true;
    /** Protect Item is actively on (tracked so we don't spam the packet). */
    private boolean protectItemActive = false;
    /** Last tick we toggled Protect Item (throttle re-sends). */
    private int lastProtectItemTick = -99;
    /** Require ice barrage selected on staff (Autocast → Ice Barrage) once per login. */
    public volatile boolean nhAutocastHint = true;
 
    // Combo-Eat auto (keys 1-4 / Num3 still eat with this off)
    public volatile boolean comboEatEnabled     = false;
    public volatile int     comboEatHpThreshold = 32;
    /** Above this HP, auto-eat prefers brew sip; at/below prefers marlin combos. */
    public volatile int     brewPreferAboveHp   = 30;
    public volatile int     foodSlot            = 0;
    public volatile int     potionSlot          = 1;
    public volatile int     karambwanSlot       = 2;

    // NH gear sets — item id + name, not inventory slots (slots move when you swap).
    public final NhLoadout mageLoadout  = new NhLoadout();
    public final NhLoadout rangeLoadout = new NhLoadout();
    public final NhLoadout meleeLoadout = new NhLoadout();
    public final NhLoadout tankLoadout  = new NhLoadout();

    /** The four NH gear sets, keyed for the Swapper snapshot buttons. */
    public enum NH_SET { MAGE, RANGE, MELEE, TANK }

    /** Snapshot currently-equipped gear into one NH loadout set. */
    public void snapshotNhLoadout(NH_SET set) {
        NhLoadout target;
        switch (set) {
            case MAGE:  target = mageLoadout; break;
            case RANGE: target = rangeLoadout; break;
            case MELEE: target = meleeLoadout; break;
            default:    target = tankLoadout; break;
        }
        target.clear();
        EquippedPiece[] worn = snapshotEquippedGear();
        for (EquippedPiece p : worn) {
            if (p.itemId > 0) target.pieces().add(new NhLoadout.Piece(p.itemId, p.name));
        }
        NhLoadout.saveAll(this);
        lastAction = "NH_SNAP_" + set.name() + "@" + currentTick;
    }

    /** Mini overlay NH tab — auto ice barrage + gear loop. Off until you turn it on. */
    public volatile boolean nhEnabled = false;
    /** Melee switch when target HP is at or below this. */
    public volatile int nhKoHp = 35;
    public volatile String nhPhaseName = "IDLE";

    // ── Dharok script (off by default — same posture as auto-spec) ───────────
    public volatile boolean dharokEnabled       = false;
    public volatile boolean dharokAutoEat       = false;
    /** One combo eat after greataxe stack-hit (not every tick at low HP). */
    public volatile boolean dharokComboEatAfterAxe = true;
    public volatile boolean dharokUseOrb      = false;
    /** Auto orb when a DH greataxe KO is in range (Odablock-style). */
    public volatile boolean dharokAutoStack   = false;
    /** Target HP as % of max for attack-tick stack (1 = drop to ~1 HP). */
    public volatile int     dharokTargetHpPct  = 1;
    /** Passive safe floor as % of max HP. */
    public volatile int     dharokSafeHpPct    = 55;
    public volatile boolean pendingDhStack     = false;
    public volatile boolean dharokStackArmed   = false;
    public volatile boolean pendingDhAxeAfterStack = false;
    /** Next tick after greataxe: back to whip + defender. */
    public volatile boolean pendingDhWhipDef = false;
    /** Gmaul the tick after greataxe when combo math says they live the axe. */
    private volatile boolean pendingDhGmaulFollow = false;
    private int pendingDhGmaulOppHp = -1;
    private int pendingDhGmaulDhHit = -1;
    /** Keep eating after orb/axe until HP lands in 75–85. */
    public volatile boolean pendingDhMustEat = false;
    private int lastDhForceEatTick = -99;
    /** HUD: eating this tick because they started a spec / DH axe. */
    public volatile boolean pendingDhSpecEat = false;
    private int lastDhSpecEatAnim = -1;
    public volatile int lastDhSpecEatTick = -99;
    public volatile int lastDhEatTier = 0;
    private int lastDhBrewTick = -99;
    private int lastGmaulSpecTick = -99;

    // ── Eat-punish (off by default) ──────────────────────────────────────────
    public volatile boolean eatPunishEnabled   = false;
    public volatile boolean eatPunishPreferVls = false;
    public volatile int     eatPunishMinSpecPct = 50;

    // ── Modular subsystems ───────────────────────────────────────────────────
    public final AnimationMonitor animationMonitor;
    public final GearSwapEngine   gearSwap;
    private final DharokController  dharokController;
    private final EatPunishController eatPunish;
    public final PrayerController prayer = new PrayerController(this);
    public final WalkUnder walkUnder = new WalkUnder(this);

    // StateReader handle (set by FontManager after init)
    public StateReader stateReader;

    // ── Cooldowns ────────────────────────────────────────────────────────────
    private int lastAnimTick   = -99;
    private int lastDamageTick = -99;
    private int lastComboTick  = -99;
    // Headless-trigger cooldowns (separate to avoid stomping UI-driven triggers)
    private int lastHeadlessSpecTick = -99;
    private int lastHeadlessComboTick = -99;
    private int lastHeadlessVengTick = -99;
    private int lastVengCastTick = -99;
    private int lastVengSuccessTick = -999;
    private int lastCombatActivityTick = -999;
    // General short cooldown used for low-frequency triggers
    private static final int COOLDOWN = 1;
    private static final int SPEC_COOLDOWN = 2;
    private static final long MIN_KILL_GAP_MS = 1200;
    private static final long MIN_EAT_GAP_MS  = 600;
    private long lastKillTickMs = 0;
    private int  lastKillOppHp  = Integer.MIN_VALUE;
    private long lastEatMs      = 0;
    private int lastConsumedSpecAnim = -1;
    private int hitsplatChangeTick = -1;
    private int prevSplatDmg = Integer.MIN_VALUE;
    private int prevSplatType = Integer.MIN_VALUE;
    private int incomingChangeTick = -1;
    private int prevIncomingDmg = Integer.MIN_VALUE;
    private int prevIncomingType = Integer.MIN_VALUE;
    public volatile int lastIncomingDmg = -1;
    private int cachedAttackId = -1;
    private boolean cachedAttackIsPlayer = true;
    /**
     * AGS must already be equipped before the spec packet, or the server
     * resolves it as gmaul/whip. Gmaul is the following tick, and only
     * if the AGS splat was high.
     */
    private volatile boolean pendingAgsSpec = false;
    private volatile boolean pendingGmaulDump = false;
    private int agsDumpTick = -99;
    private int primaryWieldTries = 0;
    private int agsSpecTick = -99;
    private int comboStartEnergy = 0;
    /** Our own animation this tick — drives the AGS follow-up. */
    public volatile int localAnim = -1;
    /** Last animation that was not idle, so it survives long enough to read. */
    public volatile int lastAnimSeen = -1;
    /** Live gate readout for the overlay. Guessing blind was costing too much. */
    public volatile String debugState = "";
    private int consumedLocalAgsAnim = -1;
    private int consumedIceAnim = -1;
    /** Set the moment an AGS spec animation is seen on us, from any source. */
    private volatile boolean agsWatchArmed = false;
    private int agsWatchTick = -99;
    /** Splat loopCycle baseline when the watch was armed — ignore older hits. */
    private int watchSplatBaselineCycle = Integer.MIN_VALUE;
    /** Sum of new outgoing splats while the spec watch is armed (claws 4-hit total). */
    private int watchSplatSum = 0;
    private int prevSpecEnergy = -1;
    /** Ticks to wait for the AGS splat before giving up on the gmaul. */
    private static final int AGS_SPLAT_WAIT = 3;
    /** Dragon claws is a multi-hit spec — let all 4 splats land before gmaul. */
    private static final int CLAWS_SPLAT_WAIT = 3;
    /** Dragon mace is 4-tick. Swap off earlier and the spec never lands. */
    private static final int DMACE_SPLAT_WAIT = 5;
    private static final int DMACE_WIELD_WAIT = 1;
    private static final int STAT_WIELD_WAIT  = 1;
    private static final int STAT_HIT_WAIT    = 2;
    /** Newest splat object we have already accounted for. */
    private Object lastOutgoingSplatRef = null;
    private Object lastIncomingSplatRef = null;
    /**
     * The server clears face-entity the moment an attack resolves, so
     * getInteracting() goes null while our splat is still in the air. Hold the
     * last real target for a few ticks or the AGS follow-up never sees the hit.
     */
    private Object stickyTarget = null;
    private int stickyTargetTick = -99;
    private static final int STICKY_TARGET_TICKS = 20;
    /** True only when getInteracting() is non-null this tick — not a leftover sticky name. */
    private boolean liveInteractThisTick = false;
    private static final int DH_FIGHT_IDLE_TICKS = 18;
    /** HitSplat.loopCycle of the newest splat we have already counted. */
    private int lastSeenSplatCycle = Integer.MIN_VALUE;
    private Method getLoopCycleMethod;
    // Vengeance: lunar spell cooldown is 30s (~50 ticks). Buff lasts until it procs.
    private static final int VENG_CAST_COOLDOWN = 50;
    private int lastSanfewTick = -99;
    public volatile boolean pendingSanfew = false;
    /** Cumulative brew sips since the last sanfew — sanfew fires after 3. */
    private int brewSips = 0;
    private boolean pendingWhipDef = false;
    private boolean pendingStatiusWack = false;
    private volatile boolean pendingVengCast = false;
    /** Q pressed — start (or restart) the combo on the next tick. */
    private volatile boolean pendingQDump = false;
    /** After the primary spec, always gmaul (Q / auto claws) — don't wait on a 32 splat. */
    private volatile boolean forceGmaulFollow = false;
    private volatile boolean pendingBarrage = false;
    /** Tick after spellbook select — cast on target (toxic SOTD / trident / sanguinesti). */
    private volatile boolean pendingIceCast = false;
    /** Advanced Swapper cast that needs a second tick after spell select. */
    private volatile boolean pendingSwapCast = false;
    private volatile String pendingSwapCastLabel = null;
    private volatile int pendingSwapCastWidget = -1;
    /** Next tick: click Ice Barrage on the book so left-click casts (staff must already be on). */
    private volatile String pendingLeftClickSpell = null;
    private volatile int pendingProtectPrayer = -1;
    private Method bufferWriteUnsignedByte;
    private volatile boolean pendingVlsFire = false;
    private int vlsWieldTick = -99;
    /** Dark bow (50%) → settle → axes+ward → axe specs (25%, capped) → knives+ward. */
    private volatile boolean pendingDbowSpec = false;
    private int dbowWieldTick = -99;
    private volatile boolean pendingAxesWard = false;
    private int axesWardTick = -99;
    private volatile boolean pendingAxeSpec = false;
    private int axeSpecTick = -99;
    private volatile boolean pendingKnivesWard = false;
    private int knivesWardTick = -99;
    private int axeSpecsFired = 0;
    private int dbowComboEnergyEst = -1;
    public volatile int dbowMinSpecPct = 50;
    public volatile int axeSpecMinPct = 25;
    /** Ticks to wait after DBow spec before swapping to axes (lets the hit land). */
    private static final int DBOW_SETTLE_TICKS = 2;
    /** Max thrownaxe specs per combo — stops dummy infinite loop when energy read fails. */
    private static final int MAX_AXE_SPECS = 2;
    private int lastBarrageTick = -99;
    private int freezeUntilTick = -99;
    private boolean nhRangedThisFreeze = false;
    private boolean nhMeleedThisFreeze = false;
    private int lastPrayerSwitchAnim = -1;
    private int activeProtectPrayer = -1;
    private long lastPrayerSwitchMs = 0;
    private int lastProtectSendTick = -10;
    private long nhSwitchBusyUntilMs = 0;
    private static final int BARRAGE_CAST_TICKS = 5;
    private static final int FREEZE_TICKS = 32;
    private static final int REFREEZE_LEAD_TICKS = 4;
    /** 0 idle, 1 wield mace, 2 spec, 3 wait splat, 4 gmaul, 5 wield stat, 6 wack, 7 whip */
    private int dmacePhase = 0;
    private int dmacePhaseTick = -99;
    private int lastOffensiveSwapTick = -99;
    private int lastDhAxeTick = -99;
    private int lastDhOrbTick = -99;
    private int manualDhAxeSinceTick = -99;
    /** One-shot: only the greataxe KO tick may auto-eat, and only once. */
    private volatile boolean dhPostAxeEatArmed = false;
    private static final int MAX_ACTION_QUEUE = 16;

    // ── Reflected handles ────────────────────────────────────────────────────
    private final Object  clientInstance;
    private final Method  doActionMethod;
    private final Field   myPlayerField;
    private final Field   sequenceField;
    private final Field   hitSplatsField;
    private final Field   specEnergyField;
    private final Field   specEnabledField;
    private final Field   weaponHasSpecField;
    private final Field   interfaceCacheField;
    private final Method  setTabMethod;
    /** Roat MagicSpell.ICE_BARRAGE widget group (12891). */
    private final int iceBarrageWidgetId;
    private static final int SPELL_SELECT_OPCODE = 626;
    private static final int SPELL_ON_PLAYER_OPCODE = 365;
    private static final int SPELL_ON_NPC_OPCODE = 413;
    private static final int SPELL_ON_OBJECT_OPCODE = 956;
    /** NPC | object | player — Ice Barrage click-cast targets. */
    private static final int SPELL_USABLE_WORLD = 2 | 4 | 8;
    private final Field clientSpellSelectedField;
    private final Field clientSpellIdField;
    private final Field clientSpellWidgetField;
    private final Field clientSpellNameField;
    private final Field clientSpellUsableOnField;
    private final Field clientMagicSpellField;
    private final Field clientItemSelectedField;
    /** Client's own spell-selection setter methods (more reliable than fields). */
    private final Method clientSetSpellSelectedMethod;
    private final Method clientSetSelectedSpellWidgetMethod;
    private final Method clientSetSelectedSpellNameMethod;
    private volatile boolean leftClickCastArmed;
    private volatile String leftClickCastName;
    private volatile int leftClickCastWidget = -1;
    /** Tick the left-click-cast was armed — auto-disarms after a short window. */
    private int leftClickCastArmedTick = -99;
    private static final int LEFT_CLICK_ARM_TICKS = 10;
    private volatile String pendingNamedPrayer;
    private Field destXField;
    private Field destYField;
    private Method bufferWriteUnsignedShortAdd;
    private Method bufferWriteUnsignedShortLE;
    private Method bufferWriteUnsignedShortLEAdd;
    private final Field   equipmentIdsField;
    private final Field   npcsField;
    private final Field   playerArrayField;
    private Field   currentSkillLevelField;
    private final Field   loggedInField;
    private final Method  getInteractingMethod;
    private final Method  getInteractingEntityMethod;
    private final Object  packetHelper;
    private final Method  sendClickingButtonMethod;
    private final Method  sendPrayerButtonMethod;
    private final Method  sendInterfaceItemClickMethod;
    private final Field   bufferField;
    private       Method  bufferCreateFrame;
    private       Method  bufferWriteUnsignedShort;
    private       Method  getDamageMethod;
    private       Method  getSplatIdMethod;
    private       Field   actorCurrentHealthField;
    private       Field   actorMaxHealthField;
    private       Field   playerEquipmentField;
    private       Object  cachedTarget;

    // Item definition cache (lazy, for name-based zero-config gear swaps)
    private static Class<?> itemDefClass;
    private static Method   itemDefGetMethod;
    private static Field    itemDefNameField;
    private static Field    itemDefInventoryOptionsField;
    private static final java.util.Map<Integer, String> ITEM_NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Live state (read by OverlayUI) ───────────────────────────────────────
    public volatile int    lastTargetAnim   = -1;
    public volatile int    lastHitsplatDmg  = -1;
    public volatile int    lastHitsplatType = -1;
    public volatile String targetName       = "";
    public volatile int    specEnergy       = -1;
    public volatile int    currentTick      = -1;
    public volatile String lastAction       = "none";
    public volatile int    targetHp         = -1;
    public volatile int    targetMaxHp      = -1;
    public volatile int    estimatedOurMaxHit = -1;
    public volatile int    estimatedOppDhHit  = -1;
    public volatile boolean inKillRange     = false;
    public volatile boolean inDhDanger      = false;
    public volatile boolean opponentIsDh    = false;
    /** Last tick the opponent attacked or landed a hit on us. */
    public volatile int lastOppAttackTick = -99;
    private int lastSeenOppAttackAnim = -1;
    /** Last tick our HP dropped from a hit (not our own orb). */
    public volatile int lastLocalHpDropTick = -99;
    private int lastSeenLocalHp = -1;
    /** Last tick HP dropped with no hitsplat while holding an orb — player stacking by hand. */
    public volatile int lastSelfOrbTick = -99;
    /** Ticks after a manual orb click where auto-eat stays out of the way. */
    public static final int SELF_ORB_GRACE_TICKS = 5;
    /** Sticky opponent HP — Roat health fields flicker to 0 between hits. */
    public volatile int lastKnownTargetHp = -1;
    private int lastKnownTargetHpTick = -99;

    // ════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════════

    public CombatScript(Object clientInstance, Class<?> clientClass, Method doActionMethod) throws Exception {
        this.clientInstance = clientInstance;
        this.doActionMethod = doActionMethod;

        myPlayerField      = getField(clientClass, "myPlayer");
        specEnergyField    = getField(clientClass, "playerSpecialEnergy");
        specEnabledField   = getField(clientClass, "isPlayerSpecialAttackEnabled");
        weaponHasSpecField = getField(clientClass, "doesCurrentWeaponHaveSpecialAttack");
        bufferField        = getField(clientClass, "buffer");
        equipmentIdsField  = getField(clientClass, "myPlayerEquipmentIds");
        currentSkillLevelField = getField(clientClass, "currentSkillLevel");

        Class<?> rsIface = RtLookup.rsInterface();
        interfaceCacheField = rsIface != null ? getField(rsIface, "interfaceCache") : null;
        setTabMethod = findMethod(clientClass, "setTab", 1);

        int barrageWidget = 12891;
        try {
            Class<?> ms = RtLookup.magicSpell();
            if (ms != null) {
                Object ice = ms.getField("ICE_BARRAGE").get(null);
                barrageWidget = (int) ms.getMethod("getSpellId").invoke(ice);
            }
        } catch (Exception ignored) {}
        this.iceBarrageWidgetId = barrageWidget;

        Field spellSel = null, spellId = null, spellWid = null, spellName = null, spellUse = null;
        try {
            spellSel = getField(clientClass, "spellSelected");
            spellId  = getField(clientClass, "spellID");
            if (spellId == null) spellId = findField(clientClass, "selectedSpellId");
            spellWid = findField(clientClass, "anInt1137");
            spellName = findSpellNameField(clientClass);
            spellUse = findField(clientClass, "spellUsableOn");
            if (spellUse == null) spellUse = findField(clientClass, "selectedSpellUseName");
        } catch (Exception ignored) {}
        this.clientSpellSelectedField = spellSel;
        this.clientSpellIdField = spellId;
        this.clientSpellWidgetField = spellWid;
        this.clientSpellNameField = spellName != null ? spellName : findField(clientClass, "spellTooltip");
        this.clientSpellUsableOnField = spellUse;
        this.clientMagicSpellField = findField(clientClass, "magicSpell");
        this.clientItemSelectedField = findField(clientClass, "itemSelected");

        // Client's own spell-selection setters — more reliable than raw field
        // writes because they run the client's internal state transitions.
        this.clientSetSpellSelectedMethod = findMethod(clientClass, "setSpellSelected", 1);
        this.clientSetSelectedSpellWidgetMethod = findMethod(clientClass, "setSelectedSpellWidget", 1);
        this.clientSetSelectedSpellNameMethod = findMethod(clientClass, "setSelectedSpellName", 1);

        npcsField        = clientClass != null ? getField(clientClass, "npcs")        : null;
        playerArrayField = clientClass != null ? getField(clientClass, "playerArray") : null;
        loggedInField    = getStaticField(clientClass, "loggedIn");

        Class<?> actorClass = RtLookup.actor();
        sequenceField               = actorClass != null ? getField(actorClass, "sequence")   : null;
        hitSplatsField              = actorClass != null ? getField(actorClass, "hitSplats")  : null;
        getInteractingMethod        = actorClass != null ? findMethod(actorClass, "getInteracting", 0) : null;
        getInteractingEntityMethod  = actorClass != null ? findMethod(actorClass, "getInteractingEntity", 0) : null;

        Object helper = null;
        Method clickBtn = null, prayBtn = null;
        try {
            Method getPh = RtLookup.method(clientClass, "getPacketHelper", 0);
            if (getPh == null) getPh = findMethod(clientClass, "getPacketHelper", 0);
            if (getPh != null) {
                helper = getPh.invoke(clientInstance);
                if (helper != null) {
                    clickBtn = RtLookup.method(helper.getClass(), "sendClickingButton", 1);
                    prayBtn  = RtLookup.method(helper.getClass(), "sendPrayerButton", 1);
                    if (clickBtn != null) clickBtn.setAccessible(true);
                    if (prayBtn  != null) prayBtn.setAccessible(true);
                }
            }
        } catch (Exception ignored) {}
        this.packetHelper = helper;
        this.sendClickingButtonMethod = clickBtn;
        this.sendPrayerButtonMethod   = prayBtn;
        this.sendInterfaceItemClickMethod = helper != null ? RtLookup.fourIntVoid(helper.getClass()) : null;

        FontManager.log("[CombatScript] Init: sequence=" + (sequenceField != null)
                + " hitSplats=" + (hitSplatsField != null)
                + " doAction="  + (doActionMethod != null)
                + " packetHelper=" + (packetHelper != null)
                + " prayBtn=" + (sendPrayerButtonMethod != null)
                + " iceWid=" + iceBarrageWidgetId
                + " spellName=" + (clientSpellNameField != null)
                + " usableOnInt=" + (clientSpellUsableOnField != null
                    && clientSpellUsableOnField.getType() == int.class)
                + " magicSpell=" + (clientMagicSpellField != null)
                + " prayBtn=" + (sendPrayerButtonMethod != null)
                + " specOrb=" + (sendClickingButtonMethod != null || bufferField != null));
        installLeftClickCastHook();
        this.destXField = findField(clientClass, "destX");
        this.destYField = findField(clientClass, "destY");
        this.gearSwap = new GearSwapEngine(this);
        this.animationMonitor = new AnimationMonitor();
        this.dharokController = new DharokController(this);
        this.eatPunish = new EatPunishController(this, gearSwap);
        this.animationMonitor.setListener(eatPunish);
        NhLoadout.loadAll(this);
    }

    /** One inventory cell for the NH live viewer. */
    public static final class InvCell {
        public final int slot;
        public final int itemId;
        public final String name;
        public InvCell(int slot, int itemId, String name) {
            this.slot = slot;
            this.itemId = itemId;
            this.name = name != null ? name : "";
        }
        public boolean empty() { return itemId <= 0; }
    }

    public InvCell[] getInventoryView() {
        InvCell[] out = new InvCell[28];
        int[] inv = getInventorySnapshot();
        for (int s = 0; s < 28; s++) {
            int raw = inv[s];
            if (raw <= 0) {
                out[s] = new InvCell(s, -1, "");
            } else {
                int id = raw - 1;
                out[s] = new InvCell(s, id, resolveItemName(id));
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TickListener
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onTick(int tick) {
        currentTick = tick;
        HardcodedCombatAgent.applyDefaults(this);

        if (!isLoggedIn()) {
            if (pendingQDump) {
                pendingQDump = false;
                startManualCombo(tick);
            }
            drainActionQueue();
            return;
        }

        try {
            // DH mode: forcefully zero every spec/combo state every tick.
            // Any stale agsWatchArmed, pendingGmaulDump, pendingAgsSpec, etc.
            // will kill you by dumping a spec at 39 HP → "AGS+GMAUL" in the log.
            if (dharokEnabled) {
                autoSpecEnabled = false;
                comboEatEnabled = false;
                dharokAutoEat = false;
                enabled = false;
                // Do not abort a Q spec dump — claws/AGS wield→spec needs the next tick.
                if (!pendingQDump && !pendingAgsSpec && !agsWatchArmed && !pendingGmaulDump) {
                    abortComboState();
                }
            }

            if (pendingVengCast) {
                pendingVengCast = false;
                castVengeance();
                lastAction = "VENG@" + tick;
            } else if (pendingSanfew && !dharokEnabled) {
                pendingSanfew = false;
                drinkSanfew();
                lastAction = "SANFEW@" + tick;
            }
            // DH: sanfew is the tick AFTER a triple. DharokController drinks it.
            if (pendingBarrage && dmacePhase == 0 && !pendingQDump) {
                pendingBarrage = false;
                nhCastBarrage();
            }
            if (pendingIceCast && dmacePhase == 0 && !pendingQDump) {
                pendingIceCast = false;
                nhFinishBarrageCast();
            }
            if (pendingSwapCast && dmacePhase == 0 && !pendingQDump) {
                pendingSwapCast = false;
                finishPendingSwapCast();
            }
            if (pendingNamedPrayer != null && dmacePhase == 0 && !pendingQDump) {
                String pray = pendingNamedPrayer;
                pendingNamedPrayer = null;
                fireNamedPrayer(pray);
            }
            if (pendingLeftClickSpell != null && dmacePhase == 0 && !pendingQDump) {
                String spell = pendingLeftClickSpell;
                pendingLeftClickSpell = null;
                finishArmLeftClickSpell(spell);
            }
            // One-shot arm: do NOT re-assert every tick — that lattles "Cast Ice
            // Barrage ->" onto the cursor and blocks normal ground clicks / movement.
            // The native client owns spell-selected lifetime; we only disarm on a
            // timeout so the cursor returns to "Walk here" if nothing was cast.
            if (leftClickCastArmed && leftClickCastWidget > 0
                    && currentTick - leftClickCastArmedTick > LEFT_CLICK_ARM_TICKS) {
                clearLeftClickArm();
            }
            if (pendingProtectPrayer >= 0) {
                int pid = pendingProtectPrayer;
                pendingProtectPrayer = -1;
                activateProtectPrayer(pid, AnimationDb.protectPrayerName(pid));
            }
            if (!dharokEnabled && pendingVlsFire && tick > vlsWieldTick) {
                pendingVlsFire = false;
                specAndAttack();
                lastAction = "VLS_SPEC@" + tick;
                nhPhaseName = "VLS";
            }
            if (!dharokEnabled && pendingDbowSpec && tick > dbowWieldTick) {
                pendingDbowSpec = false;
                fireDbowSpecThenAxes(tick);
                drainActionQueue();
                return;
            }
            if (pendingAxesWard && tick > axesWardTick) {
                pendingAxesWard = false;
                reequipAxesAndWard(true);
                drainActionQueue();
                return;
            }
            if (pendingAxeSpec && tick > axeSpecTick) {
                pendingAxeSpec = false;
                continueAxeSpecs(tick);
                drainActionQueue();
                return;
            }
            if (pendingKnivesWard && tick > knivesWardTick) {
                pendingKnivesWard = false;
                reequipKnivesAndWard();
                drainActionQueue();
                return;
            }

            if (pendingQDump) {
                pendingQDump = false;
                startManualCombo(tick);
                drainActionQueue();
                return;
            }

            if (!dharokEnabled && dmacePhase > 0) {
                if (tick - dmacePhaseTick > 20) {
                    abortComboState();
                    lastAction = "DMACE_STALL@" + tick;
                } else {
                    runDmacePhase(tick);
                    drainActionQueue();
                    return;
                }
            }

            Object me = myPlayerField != null ? myPlayerField.get(null) : null;

            // Scan every tick so the splat is timestamped when it truly lands,
            // even if it arrives in the same update as the spec animation.
            scanOutgoingSplat();

            if (specEnergyField != null && !dharokEnabled) {
                try {
                    int energy = specEnergyField.getInt(clientInstance);
                    int dropNeed = isDmaceCombo() ? 12 : 45;
                    if (autoSpecEnabled && prevSpecEnergy >= primaryMinSpecPct() && energy >= 0
                            && prevSpecEnergy - energy >= dropNeed
                            && !AnimationDb.isGmaulSpec(lastAnimSeen)) {
                        String tag = isDmaceCombo() ? "DMACE_ENERGY"
                                : (isClawsCombo() ? "CLAWS_ENERGY" : "AGS_ENERGY");
                        armAgsWatch(tick, tag);
                    }
                    prevSpecEnergy = energy;
                    specEnergy = energy;
                } catch (Exception ignored) {}
            } else if (specEnergyField != null) {
                try {
                    specEnergy = specEnergyField.getInt(clientInstance);
                    prevSpecEnergy = specEnergy;
                } catch (Exception ignored) {}
            }

            // An AGS spec animation on us arms the gmaul follow-up (master mode only).
            if (me != null) {
                localAnim = readSequence(me);
                if (localAnim > 0) lastAnimSeen = localAnim;
                if (!dharokEnabled) {
                    if (isPrimarySpecAnim(localAnim)) {
                        if (localAnim != consumedLocalAgsAnim) {
                            consumedLocalAgsAnim = localAnim;
                            if (autoSpecEnabled || agsSpecFromScript) {
                                String tag = isDmaceCombo() ? "DMACE_SEEN"
                                        : (isClawsCombo() ? "CLAWS_SEEN" : "AGS_SEEN");
                                armAgsWatch(tick, tag);
                            }
                        }
                    } else if (!agsWatchArmed) {
                        consumedLocalAgsAnim = -1;
                    }
                    if (AnimationDb.isIceCast(localAnim) && localAnim != consumedIceAnim) {
                        consumedIceAnim = localAnim;
                        freezeUntilTick = tick + FREEZE_TICKS;
                        nhRangedThisFreeze = false;
                        nhMeleedThisFreeze = false;
                        lastBarrageTick = tick;
                        lastAction = "ICE_SEEN@" + tick;
                    } else if (!AnimationDb.isIceCast(localAnim)) {
                        consumedIceAnim = -1;
                    }
                }
            }

            // Wield stage: spec goes out the tick after the weapon is really on.
            // Q dumps must still fire while DH mode is on — DH only skips leftover auto-spec.
            if (pendingAgsSpec && tick > agsDumpTick) {
                pendingAgsSpec = false;
                fireAgsSpecNow();
                drainActionQueue();
                return;
            }

            // Did the primary spec land hard enough to be worth the gmaul?
            if (agsWatchArmed && tick > agsWatchTick) {
                resolveAgsFollowUp(tick);
            }

            if (pendingGmaulDump) {
                executePendingGmaulDump();
                drainActionQueue();
                return;
            }
            if (pendingWhipDef && tick > lastOffensiveSwapTick) {
                pendingWhipDef = false;
                reequipWhipAndDef();
            }

            if (!enabled && !nhEnabled && !dharokEnabled && !eatPunishEnabled && !autoSpecEnabled) {
                drainActionQueue();
                return;
            }
            if (PauseManager.get().isPaused(tick)) {
                drainActionQueue();
                return;
            }

            Object myPlayer = myPlayerField != null ? myPlayerField.get(null) : null;
            if (myPlayer == null) return;

            if (specEnergyField != null) {
                try { specEnergy = specEnergyField.getInt(clientInstance); } catch (Exception ignored) {}
            }

            Object target = null;
            if (getInteractingMethod != null) {
                try { target = getInteractingMethod.invoke(myPlayer); } catch (Exception ignored) {}
            }
            if (target != null) {
                liveInteractThisTick = true;
                cachedTarget   = target;
                stickyTarget   = target;
                stickyTargetTick = tick;
                targetName     = readTargetName(target);
                lastTargetAnim = readSequence(target);
                cacheAttackTarget(myPlayer, target);
                noteOpponentAttack(tick, lastTargetAnim);
                if (lastTargetAnim != lastConsumedSpecAnim && !AnimationDb.isSpecAnimation(lastTargetAnim)) {
                    lastConsumedSpecAnim = -1;
                }
            } else if (recentTarget() != null) {
                liveInteractThisTick = false;
                // Facing flickers off between attacks. Keep reading the same
                // actor instead of dropping combat state every other tick.
                cachedTarget   = recentTarget();
                lastTargetAnim = readSequence(cachedTarget);
                noteOpponentAttack(tick, lastTargetAnim);
            } else {
                liveInteractThisTick = false;
                cachedTarget   = null;
                stickyTarget   = null;
                targetName     = "";
                lastTargetAnim = -1;
                lastConsumedSpecAnim = -1;
                targetHp = targetMaxHp = -1;
                lastKnownTargetHp = -1;
                inKillRange = inDhDanger = opponentIsDh = false;
                if (!dharokEnabled) lastKillOppHp = Integer.MIN_VALUE;
            }
            readLatestHitsplat(myPlayer, true);
            refreshPvpVitals();
            if (isFreshIncomingHit()) lastOppAttackTick = tick;
            noteLocalHpDrop(tick);

            if (dharokEnabled) {
                comboEatEnabled = false;
                dharokAutoEat = false;
                if (!isSpecSequenceBusy()) {
                    dharokController.onTick(tick);
                }
            }

            if (defensivePrayersEnabled) runAutoDefPrayer(tick);
            tryAutoProtectItem(tick);

            boolean inCombat = hasCombatContext();
            boolean justHit = isFreshIncomingHit();
            boolean oppSpec = counterSpecEnabled && isFreshOpponentSpec();
            if (justHit || dealtDamageRecently()) lastCombatActivityTick = tick;

            if (dharokEnabled) {
                if (isSpecSequenceBusy()) {
                    drainActionQueue();
                    return;
                }
                // Never flush leftover spec dumps — that is how a whip spec fires.
                clearActionQueue();
                return;
            }

            if (nhEnabled) runNhTick(tick);

            // NH spec-survive eat: react to a fresh opponent spec animation by
            // eating out of the one-shot bracket. NH-only — regular PK stays
            // fully manual (keys 1-4 / Q).
            if (!dharokEnabled && nhEnabled
                    && isFreshOpponentSpec()
                    && tryEatOffOpponentSpec(tick)) {
                return;
            }

            // NH one-shot protection vs Dharok greataxe (normal swing, not a
            // spec). When we're inside their stacked max-hit bracket and a DH
            // swing anim is live, eat out of the danger band.
            if (!dharokEnabled && nhEnabled && inDhDanger
                    && AnimationDb.isDharokAnimation(lastTargetAnim)) {
                eatOffDhStackForced();
                return;
            }

            if (!enabled && !autoSpecEnabled) {
                drainActionQueue();
                return;
            }

            if (!inCombat && !isNhHolding() && !autoSpecEnabled) {
                drainActionQueue();
                return;
            }

            if (enabled && !nhEnabled) ensurePiety();

            // Eats are keybind-only (1-4). No auto combo-eat in regular PK.
            if (autoSpecEnabled && tryAutoSpecDump(tick)) {
                drainActionQueue();
                return;
            }

            if (autoSpecEnabled && tryEnqueueKillTickIfReady()) {
                drainActionQueue();
                return;
            }

            boolean canDump = autoSpecEnabled && !isSpecSequenceBusy() && specEnergy >= primaryMinSpecPct()
                    && (tick - lastHeadlessSpecTick > SPEC_COOLDOWN);
            if (canDump && oppSpec) {
                lastConsumedSpecAnim = lastTargetAnim;
                lastHeadlessSpecTick = tick;
                selectedSpec = comboSpec();
                forceGmaulFollow = true;
                executeSpec();
                if (autoVengEnabled) tryCastVengeanceEngage();
                if (autoVengEnabled && !vengWithSpecOnly) tryCastVengeance();
                drainActionQueue();
                return;
            }

            boolean ko = inKillRange && targetHp > 0 && specEnergy >= primaryMinSpecPct();
            boolean hardHit = justHit && lastIncomingDmg >= Math.max(1, damageTriggerMin);
            if (canDump && isInActivePvpFight() && (ko || hardHit) && !Humanizer.delayPassiveSpec()) {
                lastHeadlessSpecTick = tick;
                selectedSpec = comboSpec();
                forceGmaulFollow = true;
                executeSpec();
            }

            if (autoVengEnabled) tryCastVengeanceEngage();
            if (autoVengEnabled && !vengWithSpecOnly) tryCastVengeance();
            drainActionQueue();
        } catch (Throwable t) {
            FontManager.debug("tick err " + t.getClass().getSimpleName());
            lastAction = "ERR_" + t.getClass().getSimpleName() + "@" + tick;
        } finally {
            animationMonitor.update(tick, localAnim, lastTargetAnim);
            if (stateReader != null) {
                stateReader.publishTick(cachedTarget, lastTargetAnim,
                        animationMonitor.isTargetConsuming());
            }
            debugState = Stealth.showOverlayDetail()
                    ? ("e" + (enabled ? 1 : 0)
                    + "a" + (autoSpecEnabled ? 1 : 0)
                    + "c" + (hasCombatContext() ? 1 : 0)
                    + "w" + (agsWatchArmed ? 1 : 0)
                    + " an" + lastAnimSeen
                    + " d" + lastHitsplatDmg
                    + " s" + specEnergy)
                    : "";
        }
    }

    /** Re-read our own splat on the target so follow-up decisions see the AGS hit. */
    private void refreshOutgoingSplat() {
        scanOutgoingSplat();
    }

    /**
     * Finds the newest hitsplat on any actor that is not us, using
     * HitSplat.loopCycle as the timestamp. Deliberately does not go through
     * interactingEntity: that is null on a combat dummy and gets cleared the
     * moment an attack resolves, which is precisely when our splat shows up.
     */
    private void scanOutgoingSplat() {
        if (hitSplatsField == null) return;
        Object me = null;
        try { me = myPlayerField != null ? myPlayerField.get(null) : null; } catch (Exception ignored) {}

        if (agsWatchArmed) {
            Object focus = cachedTarget != null ? cachedTarget : stickyTarget;
            if (focus != null && focus != me) {
                int added = sumNewSplatsOn(focus);
                if (added > 0) watchSplatSum += added;
                return;
            }
        }

        Object best = null;
        int bestCycle = Integer.MIN_VALUE;
        for (Field f : new Field[] { npcsField, playerArrayField }) {
            Object[] arr = readActorArray(f);
            if (arr == null) continue;
            for (Object a : arr) {
                if (a == null || a == me) continue;
                Object s = newestSplat(a);
                if (s == null) continue;
                int c = splatCycle(s);
                if (c > bestCycle) { bestCycle = c; best = s; }
            }
        }
        if (best == null || bestCycle <= lastSeenSplatCycle) return;

        lastSeenSplatCycle   = bestCycle;
        lastOutgoingSplatRef = best;
        try {
            if (getDamageMethod  == null) getDamageMethod  = best.getClass().getMethod("getDamage");
            if (getSplatIdMethod == null) getSplatIdMethod = best.getClass().getMethod("getSplatId");
            lastHitsplatDmg    = (int) getDamageMethod.invoke(best);
            lastHitsplatType   = (int) getSplatIdMethod.invoke(best);
            hitsplatChangeTick = currentTick;
            if (agsWatchArmed) watchSplatSum += Math.max(0, lastHitsplatDmg);
        } catch (Exception ignored) {}
    }

    /** Sum every new hitsplat on one actor since lastSeenSplatCycle (claws 4-hit). */
    private int sumNewSplatsOn(Object actor) {
        try {
            java.util.ArrayList<?> splats = (java.util.ArrayList<?>) hitSplatsField.get(actor);
            if (splats == null || splats.isEmpty()) return 0;
            int added = 0;
            int maxCycle = lastSeenSplatCycle;
            Object last = null;
            int lastDmg = lastHitsplatDmg;
            for (Object s : splats) {
                if (s == null) continue;
                int c = splatCycle(s);
                if (c <= lastSeenSplatCycle || c <= watchSplatBaselineCycle) continue;
                if (getDamageMethod == null) getDamageMethod = s.getClass().getMethod("getDamage");
                int dmg = (int) getDamageMethod.invoke(s);
                if (dmg < 0) continue;
                added += dmg;
                if (c >= maxCycle) {
                    maxCycle = c;
                    last = s;
                    lastDmg = dmg;
                }
            }
            if (added <= 0) return 0;
            lastSeenSplatCycle = maxCycle;
            lastOutgoingSplatRef = last;
            lastHitsplatDmg = lastDmg;
            hitsplatChangeTick = currentTick;
            return added;
        } catch (Exception e) {
            return 0;
        }
    }

    private Object[] readActorArray(Field f) {
        if (f == null) return null;
        try {
            Object arr = f.get(clientInstance);
            return arr instanceof Object[] ? (Object[]) arr : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Object newestSplat(Object actor) {
        try {
            java.util.ArrayList<?> splats = (java.util.ArrayList<?>) hitSplatsField.get(actor);
            if (splats == null || splats.isEmpty()) return null;
            return splats.get(splats.size() - 1);
        } catch (Exception e) {
            return null;
        }
    }

    private int splatCycle(Object splat) {
        try {
            if (getLoopCycleMethod == null) getLoopCycleMethod = splat.getClass().getMethod("getLoopCycle");
            return (int) getLoopCycleMethod.invoke(splat);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Last real target, still valid for a short window after facing is lost. */
    private Object recentTarget() {
        if (stickyTarget == null) return null;
        return currentTick - stickyTargetTick <= STICKY_TARGET_TICKS ? stickyTarget : null;
    }

    private void drainActionQueue() {
        java.util.List<QueuedAction> batch;
        synchronized (actionQueue) {
            if (actionQueue.isEmpty()) return;
            batch = new java.util.ArrayList<>(actionQueue);
            actionQueue.clear();
        }
        java.util.Collections.sort(batch);
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) Humanizer.sameTickPause();
            try { batch.get(i).action.run(); } catch (Throwable ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Actions — dispatcher
    // ════════════════════════════════════════════════════════════════════════

    /** Routes to the correct spec executor based on selectedSpec. */
    public void executeSpec() {
        switch (selectedSpec) {
            case AGS:           executeAgsSpec();       break;
            case AGS_GMAUL:     executeAgsGmaulCombo(); break;
            case DMACE:         executeAgsSpec();       break;
            case DMACE_GMAUL:   executeAgsGmaulCombo(); break;
            case VLS:           triggerVlsSpecNow();      break;
            case DBOW_AXES:     executeDbowAxesCombo(true); break;
            case CLAWS_GMAUL:   executeAgsGmaulCombo(); break;
            case GMAUL:         executeGmaulSpec();     break;
            default:            executeGmaulSpec();     break;
        }
    }

    public boolean isDmaceCombo() {
        return selectedSpec == SpecWeapon.DMACE || selectedSpec == SpecWeapon.DMACE_GMAUL;
    }

    public boolean isVlsCombo() {
        return selectedSpec == SpecWeapon.VLS;
    }

    public boolean isDbowCombo() {
        return selectedSpec == SpecWeapon.DBOW_AXES;
    }

    public boolean isClawsCombo() {
        return selectedSpec == SpecWeapon.CLAWS_GMAUL;
    }

    public boolean isStatiusCombo() {
        return isDmaceCombo();
    }

    public boolean isGmaulOnly() {
        return selectedSpec == SpecWeapon.GMAUL;
    }

    public SpecWeapon comboSpec() {
        if (isGmaulOnly()) return SpecWeapon.GMAUL;
        if (isClawsCombo()) return SpecWeapon.CLAWS_GMAUL;
        if (isDbowCombo()) return SpecWeapon.DBOW_AXES;
        if (isVlsCombo()) return SpecWeapon.VLS;
        return isDmaceCombo() ? SpecWeapon.DMACE_GMAUL : SpecWeapon.AGS_GMAUL;
    }

    public int primaryMinSpecPct() {
        if (isGmaulOnly()) return 50;
        if (isClawsCombo()) return 50;
        if (isDbowCombo()) return dbowMinSpecPct;
        return isDmaceCombo() ? dmaceMinSpecPct : agsMinSpecPct;
    }

    public String primarySpecLabel() {
        if (isDbowCombo()) return "DBow";
        if (isClawsCombo()) return "Claws";
        return isDmaceCombo() ? "DMace" : "AGS";
    }

    public String comboSetupName() {
        if (isGmaulOnly()) return "GMAUL";
        if (isClawsCombo()) return "CLAWS+GMAUL";
        if (isDbowCombo()) return "DBOW+AXES";
        if (isVlsCombo()) return "VLS";
        return isDmaceCombo() ? "DMACE+GMAUL" : "AGS+GMAUL";
    }

    /** R — cycle Gmaul → Claws+Gmaul → AGS+Gmaul → DMace+Gmaul → VLS → DBow+Axes. */
    public void toggleComboSetup() {
        if (isGmaulOnly()) {
            selectedSpec = SpecWeapon.CLAWS_GMAUL;
        } else if (isClawsCombo()) {
            selectedSpec = SpecWeapon.AGS_GMAUL;
        } else if (isDbowCombo()) {
            selectedSpec = SpecWeapon.GMAUL;
        } else if (isVlsCombo()) {
            selectedSpec = SpecWeapon.DBOW_AXES;
        } else if (isDmaceCombo()) {
            selectedSpec = SpecWeapon.VLS;
        } else {
            selectedSpec = SpecWeapon.DMACE_GMAUL;
        }
        lastAction = comboSetupName();
        FontManager.debug("[CombatScript] Spec setup → " + comboSetupName());
    }

    private boolean isPrimarySpecAnim(int animId) {
        if (isDbowCombo()) return AnimationDb.isDarkBowSpec(animId);
        if (isClawsCombo()) return AnimationDb.isClawsSpec(animId);
        return isDmaceCombo() ? AnimationDb.isDmaceSpec(animId) : AnimationDb.isAgsSpec(animId);
    }

    /**
     * Manual spec hotkey (Q / F) — fires the currently-selected spec setup
     * (Gmaul, Claws→Gmaul, AGS→Gmaul, DMace→Gmaul, VLS, DBow+Axes).
     */
    public void triggerSpecNow() {
        forceGmaulFollow = true;
        pendingQDump = true;
        lastAction = "Q_" + comboSetupName();
        FontManager.log("[CombatScript] Q → " + comboSetupName());
    }

    /** Q / hotkey: wield claws, spec, then gmaul — not gated on splat size. */
    public void triggerClawsGmaulNow() {
        selectedSpec = SpecWeapon.CLAWS_GMAUL;
        forceGmaulFollow = true;
        pendingQDump = true;
        lastAction = "Q_CLAWS+GMAUL";
        FontManager.log("[CombatScript] Q → CLAWS+GMAUL");
    }

    /** Manual gmaul-only dump for when you specced AGS by hand. */
    public void triggerGmaulFollowNow() {
        if (dharokEnabled) {
            lastAction = "G_DH_BLOCK@" + currentTick;
            return;
        }
        try {
            pendingAgsSpec = false;
            agsWatchArmed = false;
            pendingGmaulDump = false;
            if (fireGmaulSameTick("GMAUL_G")) scheduleBaselineRestore();
        } catch (Throwable ignored) {}
    }

    /** Drop mid-flight spec state so Q can fire again. */
    private void abortComboState() {
        dmacePhase = 0;
        agsWatchArmed = false;
        pendingAgsSpec = false;
        pendingGmaulDump = false;
        pendingStatiusWack = false;
        pendingWhipDef = false;
        agsSpecFromScript = false;
        pendingDbowSpec = false;
        pendingAxesWard = false;
        pendingAxeSpec = false;
        pendingKnivesWard = false;
        axeSpecsFired = 0;
        dbowComboEnergyEst = -1;
        primaryWieldTries = 0;
        forceGmaulFollow = false;
        clearPendingDhGmaul();
    }

    private void startManualCombo(int tick) {
        abortComboState();
        lastHeadlessSpecTick = -99;
        if (isGmaulOnly()) {
            executeGmaulSpec();
            return;
        }
        if (isDbowCombo()) {
            executeDbowAxesCombo(true);
            return;
        }
        if (isVlsCombo()) {
            triggerVlsSpecNow();
            return;
        }
        if (isClawsCombo()) {
            WeaponRef claws = findClawsWeapon();
            if (claws == null) {
                lastAction = "NO_CLAWS@" + tick;
                logMissingWeapon("Claws", false);
                logInventorySnapshot("NO_CLAWS");
                return;
            }
            forceGmaulFollow = true;
            executeAgsGmaulCombo(true);
            return;
        }
        WeaponRef mace = findDragonMaceWeapon();
        WeaponRef ags = findAgsWeapon();
        if (isDmaceCombo() && mace != null) {
            startDmaceCombo(mace);
            return;
        }
        if (!isDmaceCombo() && ags != null) {
            executeAgsGmaulCombo(true);
            return;
        }
        if (mace != null) {
            selectedSpec = SpecWeapon.DMACE_GMAUL;
            startDmaceCombo(mace);
            return;
        }
        if (ags != null) {
            selectedSpec = SpecWeapon.AGS_GMAUL;
            executeAgsGmaulCombo(true);
            return;
        }
        WeaponRef claws = findClawsWeapon();
        if (claws != null) {
            selectedSpec = SpecWeapon.CLAWS_GMAUL;
            executeAgsGmaulCombo(true);
            return;
        }
        WeaponRef gmaul = findWeapon(true);
        if (gmaul != null) {
            selectedSpec = SpecWeapon.GMAUL;
            executeGmaulSpec();
            return;
        }
        lastAction = "NO_SPEC_WEP@" + tick;
        FontManager.log("[CombatScript] Q: no spec weapon in inv/equip");
    }

    /** True when we know their HP and the hit already KO'd — skip gmaul to save 50% spec. */
    private boolean splatAlreadyKo(int hitDmg) {
        return targetHp > 0 && hitDmg >= targetHp;
    }

    private void finishPrimarySpecNoGmaul(int tick) {
        if (isDmaceCombo()) queueStatiusWack(tick);
        else if (agsSpecFromScript) scheduleBaselineRestore();
        agsSpecFromScript = false;
    }

    /** Same-tick gmaul wield + spec (server-side equip is instant). Returns false if skipped. */
    private boolean fireGmaulSameTick(String label) {
        if (specEnergy >= 0 && specEnergy < 50) {
            lastAction = "GMAUL_NOENERGY@" + currentTick;
            return false;
        }
        refreshOutgoingSplat();
        if (splatAlreadyKo(lastHitsplatDmg)) {
            lastAction = "GMAUL_SKIP_KO_" + lastHitsplatDmg + "v" + targetHp + "@" + currentTick;
            return false;
        }
        WeaponRef gmaul = findWeapon(true);
        if (gmaul == null) {
            logMissingWeapon("Gmaul", true);
            return false;
        }
        if (!ensureGmaulEquipped()) {
            lastAction = "GMAUL_NO_WIELD@" + currentTick;
            FontManager.log("[CombatScript] Gmaul not equipped — refusing spec (would dump on whip)");
            return false;
        }
        specAndAttack();
        lastHeadlessSpecTick = currentTick;
        lastAction = label + "@" + currentTick;
        scheduleBaselineRestore();
        return true;
    }

    private boolean shouldGmaulFollow(int hitDmg) {
        if (splatAlreadyKo(hitDmg)) {
            lastAction = "GMAUL_SKIP_KO_" + hitDmg + "v" + targetHp + "@" + currentTick;
            return false;
        }
        if (findWeapon(true) == null) return false;
        if (targetHp > 0) {
            int str = stateReader != null ? stateReader.getStrength() : 99;
            int gmax = MaxHitCalculator.gmaulSpecMaxHit(str);
            if (hitDmg + gmax >= targetHp) return true;
        }
        int need = isClawsCombo() ? Math.max(1, clawsHighHitMin)
                : (isDmaceCombo() ? Math.max(1, dmaceHighHitMin) : Math.max(1, agsHighHitMin));
        return hitDmg >= need;
    }

    /**
     * Enqueue an action into the main action queue in a thread-safe way.
     * Headless helpers should use this to ensure correct ordering with other onTick actions.
     */
    public void enqueueHeadlessAction(ActionPriority p, String name, Runnable action) {
        synchronized (actionQueue) {
            if (actionQueue.size() >= MAX_ACTION_QUEUE) {
                FontManager.log("[CombatScript] Dropping headless action (queue full): " + name);
                return;
            }
            actionQueue.add(new QueuedAction(p, name, action));
            FontManager.debug("[CombatScript] Enqueued " + name);
        }
    }

    /**
     * Clear and cancel any pending actions — safe for UI panic.
     */
    public void clearActionQueue() {
        synchronized (actionQueue) {
            actionQueue.clear();
            FontManager.log("[CombatScript] Action queue cleared via clearActionQueue()");
        }
    }

    /**
     * Headless-friendly combo-eat enqueue. Returns true when queued.
     */
    public boolean tryEnqueueComboEatIfReady(int tick) {
        if (dharokEnabled) return false;
        if (!comboEatEnabled) return false;
        if (tick - lastHeadlessComboTick <= COOLDOWN) return false;
        lastHeadlessComboTick = tick;
        lastComboTick = tick;
        enqueueHeadlessAction(ActionPriority.EMERGENCY_HEAL, "HEADLESS_COMBO_EAT",
                () -> executeComboEat(EatContext.AUTO));
        FontManager.debug("[CombatScript] Headless queued COMBO_EAT at tick=" + tick);
        return true;
    }

    /**
     * Auto Spec: dump the current combo (default claws→gmaul) when we have
     * energy and a target. Does not require BOT ON or an incoming 18+ splat.
     */
    private boolean tryAutoSpecDump(int tick) {
        if (!autoSpecEnabled || dharokEnabled) return false;
        if (isSpecSequenceBusy()) return false;
        if (tick - lastHeadlessSpecTick <= SPEC_COOLDOWN) return false;
        int energy = specEnergy;
        try {
            if (specEnergyField != null) energy = specEnergyField.getInt(clientInstance);
        } catch (Exception ignored) {}
        specEnergy = energy;
        if (energy < primaryMinSpecPct()) return false;
        if (!liveInteractThisTick && recentTarget() == null && !isInActivePvpFight()) return false;

        lastHeadlessSpecTick = tick;
        if (findClawsWeapon() != null) selectedSpec = SpecWeapon.CLAWS_GMAUL;
        else selectedSpec = comboSpec();
        forceGmaulFollow = true;
        lastAction = "AUTO_" + comboSetupName() + "@" + tick;
        FontManager.log("[CombatScript] Auto spec → " + comboSetupName() + " energy=" + energy);
        executeSpec();
        return true;
    }

    /**
     * Headless-friendly spec enqueue. Returns true when queued.
     * Adds extra guards: avoids enqueueing if a HEADLESS_SPEC is already pending
     * and uses a stronger SPEC_COOLDOWN to stop sustained re-enqueues while
     * the triggering condition persists.
     */
    public boolean tryEnqueueSpecIfReady(int tick, int specialEnergy, int lastHitDmg) {
        if (!autoSpecEnabled) return false;
        if (isSpecSequenceBusy()) return false;
        // Respect the stronger SPEC cooldown window
        if (tick - lastHeadlessSpecTick <= SPEC_COOLDOWN) return false;

        // Avoid enqueueing if there's already a HEADLESS_SPEC pending
        synchronized (actionQueue) {
            for (QueuedAction q : actionQueue) {
                if (q != null && "HEADLESS_SPEC".equals(q.name)) {
                    FontManager.log("[CombatScript] Skipping enqueue: HEADLESS_SPEC already in queue");
                    return false;
                }
            }
        }

        boolean trigger = false;
        if (selectedSpec == SpecWeapon.AGS_GMAUL || selectedSpec == SpecWeapon.DMACE_GMAUL
                || selectedSpec == SpecWeapon.STATIUS_GMAUL || selectedSpec == SpecWeapon.CLAWS_GMAUL) {
            if (specialEnergy >= primaryMinSpecPct() && isFreshDamageHit()) trigger = true;
            if (counterSpecEnabled && isFreshOpponentSpec()
                    && specialEnergy >= primaryMinSpecPct()) trigger = true;
        } else if (selectedSpec == SpecWeapon.AGS || selectedSpec == SpecWeapon.DMACE
                || selectedSpec == SpecWeapon.STATIUS) {
            if (specialEnergy >= primaryMinSpecPct()) trigger = true;
        } else if (selectedSpec == SpecWeapon.GMAUL) {
            if (isFreshDamageHit() || isFreshOpponentSpec()) trigger = true;
        } else {
            if (specialEnergy >= 50 && isFreshDamageHit()) trigger = true;
        }
        if (!trigger) return false;

        lastHeadlessSpecTick = tick;
        if (AnimationDb.isSpecAnimation(lastTargetAnim)) lastConsumedSpecAnim = lastTargetAnim;
        enqueueHeadlessAction(ActionPriority.SPECIAL_ATTACK, "HEADLESS_SPEC", this::executeSpec);
        FontManager.debug("[CombatScript] Headless queued SPEC at tick=" + tick + " energy=" + specialEnergy + " dmg=" + lastHitDmg);
        return true;
    }

    /**
     * Headless-friendly AGS/Gmaul combo enqueue. Requires both spec energy and damage thresholds.
     */
    public boolean tryEnqueueAgsGmaulComboIfReady(int tick, int specialEnergy, int lastHitDmg) {
        if (!autoSpecEnabled) return false;
        if (isSpecSequenceBusy()) return false;
        if (tick - lastHeadlessSpecTick <= SPEC_COOLDOWN) return false;
        boolean energyOk = specialEnergy < 0 || specialEnergy >= primaryMinSpecPct();
        boolean dmgOk = isFreshDamageHit();
        boolean oppSpec = counterSpecEnabled && isFreshOpponentSpec();
        if (!energyOk) return false;
        if (!dmgOk && !oppSpec) return false;
        if (overheadChecksEnabled && !targetOverheadAllowsSpec()) return false;

        synchronized (actionQueue) {
            for (QueuedAction q : actionQueue) {
                if (q != null && q.name != null && q.name.startsWith("HEADLESS_")) {
                    FontManager.log("[CombatScript] Skipping combo enqueue: headless action pending");
                    return false;
                }
            }
        }

        lastHeadlessSpecTick = tick;
        selectedSpec = comboSpec();
        if (AnimationDb.isSpecAnimation(lastTargetAnim)) lastConsumedSpecAnim = lastTargetAnim;
        enqueueHeadlessAction(ActionPriority.SPECIAL_ATTACK, "HEADLESS_AGS_GMAUL", this::executeAgsGmaulCombo);
        FontManager.debug("[CombatScript] Headless queued AGS/GMAUL combo tick=" + tick
                + " energy=" + specialEnergy + " dmg=" + lastHitDmg);
        return true;
    }

    /**
     * Opponent HP is inside our calculated max hit. Same tick: swap, spec, then
     * food/karam eat. Eat is after the attack packet so a DH stack still counts.
     * Never flicks protect-melee.
     */
    public boolean tryEnqueueKillTickIfReady() {
        if (dharokEnabled) return false;
        if (!autoSpecEnabled) return false;
        if (isSpecSequenceBusy()) return false;
        if (currentTick - lastHeadlessSpecTick <= SPEC_COOLDOWN) return false;
        long now = System.currentTimeMillis();
        if (now - lastKillTickMs < MIN_KILL_GAP_MS) return false;
        if (targetHp <= 0) return false;
        if (lastKillOppHp > 0 && targetHp >= lastKillOppHp) return false;
        if (overheadChecksEnabled && !targetOverheadAllowsSpec()) return false;

        int ourHp = readLocalHp();
        int ourMax = stateReader != null ? stateReader.getMaxHp() : 99;
        int str = stateReader != null ? stateReader.getStrength() : 99;

        WeaponRef primary = findWeapon(false);
        WeaponRef axe = findGreataxe();
        boolean dhKoOk = weAreDhStacked(ourHp, axe);
        int dhHit = (axe != null && dhKoOk) ? MaxHitCalculator.dharokMaxHit(str, ourHp, ourMax) : 0;
        int primaryHit = 0;
        if (primary != null) {
            primaryHit = isDmaceCombo()
                    ? dmaceMaxHit
                    : agsMaxHit;
        }
        estimatedOurMaxHit = Math.max(dhHit, primaryHit);
        // Same-tick KO only. Gmaul is next tick — they can eat, so don't count it.
        inKillRange = targetHp > 0 && estimatedOurMaxHit >= targetHp;

        int mode = -1;
        // Primary KO needs it already wielded — a same-tick swap resolves as gmaul.
        if (primary != null && primary.equipped && specEnergy >= primaryMinSpecPct() && primaryHit >= targetHp) {
            mode = 1;
        } else if (dhKoOk && dhHit >= targetHp) {
            mode = 0;
        }
        if (mode < 0) {
            inKillRange = false;
            return false;
        }

        pendingKillMode = mode;
        lastHeadlessSpecTick = currentTick;
        lastHeadlessComboTick = currentTick;
        lastComboTick = currentTick;
        lastKillTickMs = now;
        lastKillOppHp = targetHp;
        enqueueHeadlessAction(ActionPriority.SPECIAL_ATTACK, "HEADLESS_KILL_TICK", this::executeKillTick);
        FontManager.debug("[CombatScript] Kill tick oppHp=" + targetHp + " hit=" + estimatedOurMaxHit
                + " mode=" + killModeName(mode));
        return true;
    }

    /** Eat out of a DH one-shot bracket. No defensive overhead. */
    public boolean tryEnqueueSafetyEatIfReady(int tick) {
        if (dharokEnabled) return false;
        if (!comboEatEnabled) return false;
        if (tick - lastHeadlessComboTick < 3) return false;
        if (System.currentTimeMillis() - lastEatMs < MIN_EAT_GAP_MS) return false;
        lastHeadlessComboTick = tick;
        lastComboTick = tick;
        enqueueHeadlessAction(ActionPriority.EMERGENCY_HEAL, "SAFETY_EAT",
                () -> executeComboEat(EatContext.SAFETY));
        FontManager.debug("[CombatScript] Safety eat threat=" + estimatedOppDhHit);
        return true;
    }

    public boolean isInDhOneShotRange() {
        return inDhDanger;
    }

    public void refreshPvpVitals() {
        int ourHp = readLocalHp();
        int ourMax = stateReader != null ? stateReader.getMaxHp() : 99;
        int str = stateReader != null ? stateReader.getStrength() : 99;
        readTargetHealth(cachedTarget);
        opponentIsDh = targetLooksLikeDharok(cachedTarget)
                || AnimationDb.isDharokAnimation(lastTargetAnim);
        int threatHp = -1;
        if (targetHp > 0) {
            threatHp = targetHp;
        } else if (opponentIsDh && AnimationDb.isDharokAnimation(lastTargetAnim)) {
            threatHp = 1;
        }
        estimatedOppDhHit = threatHp > 0
                ? MaxHitCalculator.opponentDhThreat(threatHp, targetMaxHp)
                : 0;

        WeaponRef axe = findGreataxe();
        WeaponRef ags = findWeapon(false);
        int dhHit = axe != null ? MaxHitCalculator.dharokMaxHit(str, ourHp, ourMax) : 0;
        int agsHit = ags != null ? agsMaxHit : 0;
        estimatedOurMaxHit = Math.max(dhHit, agsHit);
        if (dharokEnabled) {
            wouldDhKoIfStacked();
        } else {
            inKillRange = targetHp > 0 && estimatedOurMaxHit >= targetHp;
        }
        inDhDanger = opponentIsDh && ourHp > 0 && ourHp <= estimatedOppDhHit;
    }

    private int pendingKillMode = -1;

    private static String killModeName(int mode) {
        switch (mode) {
            case 0: return "DH_AXE";
            case 1: return "AGS";
            case 2: return "AGS_GMAUL";
            default: return "none";
        }
    }

    /** Swap → spec/attack at current HP → combo eat. Same tick, no protect melee. */
    public void executeKillTick() {
        WeaponRef axe = findGreataxe();
        if (axe != null && pendingKillMode == 0) {
            executeDhAxeThenHeal(axe);
            return;
        }
        try {
            int mode = pendingKillMode;
            pendingKillMode = -1;
            activatePiety();
            if (mode == 1) executeAgsSpec();
            else executeAgsGmaulCombo();
        } catch (Throwable t) {
            FontManager.log("[CombatScript] Kill tick error: " + t.getMessage());
        }
    }

    /**
     * Stay stacked. Wield greataxe → one attack at current HP → combo eat →
     * whip + tank next tick. Super combat is left to the player.
     */
    private void executeDhAxeThenHeal(WeaponRef axe) {
        if (axe == null) return;
        int hp = readLocalHp();
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        int targetHp = Math.max(1, (maxHp * dharokTargetHpPct) / 100);
        if (hp > targetHp + 8) {
            lastAction = "DH_WAIT_STACK@" + currentTick;
            return;
        }
        tryOdablockDhAxe();
    }

    /** Next tick: restore fang/whip + dragon defender after an offensive swap. */
    private void scheduleBaselineRestore() {
        lastOffensiveSwapTick = currentTick;
        pendingWhipDef = true;
    }

    /** Whip or fang + dragon defender after a spec dump. Fang preferred when present. */
    private void reequipWhipAndDef() {
        WeaponRef mh = findBaselineMelee();
        if (mh != null) {
            if (mh.inInventory()) wieldItem(mh.slot, mh.itemId);
            else if (!mh.equipped) {
                FontManager.debug("[CombatScript] Baseline MH not in inv: " + mh);
            }
        }
        UiExecutor.schedule(this::wieldDragonDefender, Humanizer.invGapMs());
        UiExecutor.schedule(this::reAttackTarget, Humanizer.invGapMs() + Humanizer.tickDelayMs());
        lastAction = "MH_DEF@" + currentTick;
    }

    private WeaponRef findBaselineMelee() {
        WeaponRef fang = findFang();
        if (fang != null) return fang;
        return findWhip();
    }

    private WeaponRef findFang() {
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (InventoryTracker.isFang(id, name)) {
                    return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
                }
            }
        }
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isFang(id, name)) return new WeaponRef(slot, id, name, false);
        }
        return null;
    }

    /**
     * PK range:
     *   tick N     — Rigour + wield dark bow
     *   tick N+1   — DBow spec (50%)
     *   tick N+3   — dragon thrownaxe + ward (2-tick settle after DBow)
     *   then       — up to 2 axe specs (25% each), never infinite
     *   finally    — dragon knives + ward + re-attack
     */
    public void executeDbowAxesCombo(boolean manual) {
        if (doActionMethod == null) return;
        try {
            if (!manual && isSpecSequenceBusy()) return;

            axeSpecsFired = 0;
            dbowComboEnergyEst = -1;

            int energy = readSpecEnergy();
            if (energy >= 0 && energy < dbowMinSpecPct) {
                lastAction = "DBOW_NOENERGY@" + currentTick;
                if (energy >= axeSpecMinPct) {
                    reequipAxesAndWard(true);
                } else {
                    reequipKnivesAndWard();
                }
                return;
            }

            WeaponRef bow = findGear(InventoryTracker::isDarkBow);
            if (bow == null) {
                lastAction = "NO_DBOW@" + currentTick;
                FontManager.debug("[CombatScript] No dark bow in inv/equip");
                return;
            }

            selectedSpec = SpecWeapon.DBOW_AXES;
            ensureEagleEye(); // Rigour while unlocked

            if (bow.inInventory()) {
                wieldItem(bow.slot, bow.itemId);
                pendingDbowSpec = true;
                dbowWieldTick = currentTick;
                lastAction = "DBOW_WIELD@" + currentTick;
                return;
            }

            // Already on bow — still wait a tick so Rigour/equip settle before spec.
            pendingDbowSpec = true;
            dbowWieldTick = currentTick;
            lastAction = "DBOW_READY@" + currentTick;
        } catch (Exception e) {
            FontManager.debug("[CombatScript] DBow combo error: " + e.getMessage());
        }
    }

    /** Fire one DBow spec, then wait {@link #DBOW_SETTLE_TICKS} before axes. */
    private void fireDbowSpecThenAxes(int tick) {
        // Confirm bow is actually on — if not, re-wield and retry next tick.
        WeaponRef bow = findGear(InventoryTracker::isDarkBow);
        if (bow == null) {
            lastAction = "NO_DBOW@" + tick;
            return;
        }
        if (bow.inInventory()) {
            wieldItem(bow.slot, bow.itemId);
            pendingDbowSpec = true;
            dbowWieldTick = tick;
            lastAction = "DBOW_WIELD@" + tick;
            return;
        }

        int energy = readSpecEnergy();
        if (energy >= 0) {
            dbowComboEnergyEst = Math.max(0, energy - dbowMinSpecPct);
        } else {
            dbowComboEnergyEst = 50; // assume started near 100
        }

        lastHeadlessSpecTick = tick;
        specAndAttack();
        axeSpecsFired = 0;
        pendingAxesWard = true;
        // tick > axesWardTick → wait DBOW_SETTLE_TICKS after this fire
        axesWardTick = tick + (DBOW_SETTLE_TICKS - 1);
        lastAction = "DBOW_SPEC@" + tick;
    }

    private int readSpecEnergy() {
        if (specEnergyField == null) return -1;
        try {
            int e = specEnergyField.getInt(clientInstance);
            if (e >= 0) specEnergy = e;
            return e;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Equip thrownaxes + ward. If {@code thenSpec}, axe-spec next ticks (capped).
     */
    private void reequipAxesAndWard(boolean thenSpec) {
        WeaponRef axes = findGear(InventoryTracker::isDragonThrownaxe);
        if (axes == null) {
            lastAction = "NO_AXES@" + currentTick;
            FontManager.debug("[CombatScript] No dragon thrownaxe — skipping to knives");
            reequipKnivesAndWard();
            return;
        }
        if (axes.inInventory()) {
            wieldItem(axes.slot, axes.itemId);
        }
        scheduleWardEquip();

        if (thenSpec) {
            pendingAxeSpec = true;
            // Extra tick so axes+ward are on before Momentum Throw.
            axeSpecTick = currentTick + 1;
            lastAction = "AXES_WARD@" + currentTick;
        } else {
            pendingKnivesWard = true;
            knivesWardTick = currentTick;
            lastAction = "AXES_WARD@" + currentTick;
        }
    }

    /** Axe specs until energy out, estimate out, or {@link #MAX_AXE_SPECS} — then knives. */
    private void continueAxeSpecs(int tick) {
        int energy = readSpecEnergy();

        boolean outOfEnergy = false;
        if (energy >= 0) {
            outOfEnergy = energy < axeSpecMinPct;
            dbowComboEnergyEst = energy;
        } else if (dbowComboEnergyEst >= 0) {
            outOfEnergy = dbowComboEnergyEst < axeSpecMinPct;
        }

        if (outOfEnergy || axeSpecsFired >= MAX_AXE_SPECS) {
            pendingKnivesWard = true;
            knivesWardTick = tick;
            lastAction = "AXE_SPEC_DONE@" + tick
                    + "(n=" + axeSpecsFired + ",e=" + energy + ")";
            return;
        }

        // Confirm axes still on before pulsing spec.
        WeaponRef axes = findGear(InventoryTracker::isDragonThrownaxe);
        if (axes != null && axes.inInventory()) {
            wieldItem(axes.slot, axes.itemId);
        }

        lastHeadlessSpecTick = tick;
        specAndAttack();
        axeSpecsFired++;
        if (dbowComboEnergyEst >= 0) {
            dbowComboEnergyEst = Math.max(0, dbowComboEnergyEst - axeSpecMinPct);
        }
        pendingAxeSpec = true;
        axeSpecTick = tick; // next tick re-check / next spec
        lastAction = "AXE_SPEC@" + tick + "(" + axeSpecsFired + "/" + MAX_AXE_SPECS + ")";
    }

    private void reequipKnivesAndWard() {
        WeaponRef knives = findGear(InventoryTracker::isDragonKnife);
        if (knives == null) {
            lastAction = "NO_KNIVES@" + currentTick;
            FontManager.debug("[CombatScript] No dragon knife in inv/equip");
            reAttackTarget();
            return;
        }
        if (knives.inInventory()) {
            wieldItem(knives.slot, knives.itemId);
        }
        scheduleWardEquip();
        UiExecutor.schedule(this::reAttackTarget, Humanizer.invGapMs() + 40L);
        lastAction = "KNIVES_WARD@" + currentTick;
    }

    private void scheduleWardEquip() {
        WeaponRef ward = findGear(InventoryTracker::isDragonfireWard);
        if (ward != null && ward.inInventory()) {
            final int wardSlot = ward.slot;
            final int wardId = ward.itemId;
            UiExecutor.schedule(() -> wieldItem(wardSlot, wardId), Humanizer.invGapMs());
        } else {
            FontManager.debug("[CombatScript] No dragonfire ward in inv");
        }
    }

    private WeaponRef findWhip() {
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (InventoryTracker.isWhip(id, name)) {
                    return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
                }
            }
        }
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isWhip(id, name)) return new WeaponRef(slot, id, name, false);
        }
        return null;
    }

    /**
     * Headless-friendly vengeance enqueue with cooldown. Returns true when queued.
     */
    public boolean tryEnqueueVengeanceIfReady(int tick) {
        if (!autoVengEnabled) return false;
        // v7 default: never enqueue passive per-tick vengeance — spec paths call tryCastVengeanceWithSpec()
        if (vengWithSpecOnly) return false;
        if (!canCastVengeance(tick, true)) return false;
        if (tick - lastHeadlessVengTick <= VENG_CAST_COOLDOWN) return false;

        synchronized (actionQueue) {
            for (QueuedAction q : actionQueue) {
                if (q != null && q.name != null && q.name.startsWith("HEADLESS_VENG")) {
                    return false;
                }
            }
        }

        lastHeadlessVengTick = tick;
        enqueueHeadlessAction(ActionPriority.PRAYER_SWITCH, "HEADLESS_VENG", this::tryCastVengeance);
        FontManager.log("[CombatScript] Headless queued VENGEANCE at tick=" + tick);
        return true;
    }

    /** Remove enqueued actions whose name starts with the given prefix. */
    public void removeEnqueuedActionsMatching(String prefix) {
        synchronized (actionQueue) {
            actionQueue.removeIf(q -> q.name != null && q.name.startsWith(prefix));
        }
        FontManager.log("[CombatScript] Removed enqueued actions with prefix=" + prefix);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Actions — Gmaul
    // ════════════════════════════════════════════════════════════════════════

    public boolean isFreshOpponentSpec() {
        return AnimationDb.isSpecAnimation(lastTargetAnim) && lastTargetAnim != lastConsumedSpecAnim;
    }

    public boolean isFreshIncomingHit() {
        return lastIncomingDmg > 0 && incomingChangeTick == currentTick;
    }

    public boolean isFreshDamageHit() {
        return lastIncomingDmg >= Math.max(1, damageTriggerMin)
                && incomingChangeTick == currentTick;
    }

    /**
     * Gmaul 1-tick spec: wield (if not already equipped) → spec ON → attack → spec ON → attack.
     */
    public void executeGmaulSpec() {
        if (doActionMethod == null) { FontManager.log("[CombatScript] No doAction!"); return; }
        try {
            WeaponRef gmaul = findWeapon(true);
            if (gmaul == null) {
                logMissingWeapon("Gmaul", true);
                return;
            }
            lastHeadlessSpecTick = currentTick;
            if (autoVengEnabled) tryCastVengeanceWithSpec();
            // Gmaul is the one weapon where a same-tick wield+spec does work,
            // because the spec is instant rather than an attack-speed swing.
            if (gmaul.inInventory()) wieldItem(gmaul.slot, gmaul.itemId);
            specAndAttack();
            specAndAttack();
            scheduleBaselineRestore();
            lastAction = "GMAUL_1T@" + currentTick;
        } catch (Exception e) {
            FontManager.log("[CombatScript] Gmaul spec error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Actions — AGS 1-tick
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Primary spec (AGS or Statius): wield if needed, spec next tick so the
     * server does not resolve it as a gmaul.
     */
    public void executeAgsSpec() {
        if (doActionMethod == null) { FontManager.log("[CombatScript] No doAction!"); return; }
        String label = primarySpecLabel();
        try {
            int energy = (specEnergyField != null) ? specEnergyField.getInt(clientInstance) : -1;
            int minPct = primaryMinSpecPct();
            if (energy >= 0 && energy < minPct) {
                FontManager.log("[CombatScript] " + label + " spec skipped: energy=" + energy + "% < " + minPct + "%");
                return;
            }

            WeaponRef primary = findWeapon(false);
            if (primary == null) {
                logMissingWeapon(label, false);
                return;
            }
            if (!primary.equipped) {
                wieldItem(primary.slot, primary.itemId);
                if (isStatiusCombo()) wieldDragonDefender();
                pendingAgsSpec = true;
                agsDumpTick = currentTick;
                lastAction = label.toUpperCase() + "_WIELD@" + currentTick;
                return;
            }
            if (isStatiusCombo() && wieldDragonDefender()) {
                pendingAgsSpec = true;
                agsDumpTick = currentTick;
                lastAction = "SW_DEF@" + currentTick;
                return;
            }
            lastHeadlessSpecTick = currentTick;
            specAndAttack();
            agsSpecTick = currentTick;
            scheduleBaselineRestore();
            lastAction = label.toUpperCase() + "_SPEC@" + currentTick;
        } catch (Exception e) {
            FontManager.log("[CombatScript] " + label + " spec error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Actions — primary spec / Gmaul combo
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Primary spec (AGS 50% or Statius 35%), then gmaul the following tick.
     *
     * The server resolves a spec against the weapon it already knows about, so
     * wielding and pulsing 5004 in the same cycle just produced a gmaul spec.
     *   tick N   — wield Dragon mace (BH) + dragon defender
     *   tick N+1 — spec
     *   tick N+2 — gmaul only if splat >= 50 (max 63)
     *   then     — Statius + d defender and a regular wack
     * AGS is 2h (defender drops while it is on). Gmaul follow uses splat logic;
     * after the sequence, restore 1h fang + dragon defender.
     */
    public void executeAgsGmaulCombo() {
        executeAgsGmaulCombo(false);
    }

    public void executeAgsGmaulCombo(boolean manual) {
        if (doActionMethod == null) return;
        String label = primarySpecLabel();
        try {
            if (!manual && isSpecSequenceBusy()) {
                return;
            }

            int energy = (specEnergyField != null) ? specEnergyField.getInt(clientInstance) : -1;
            if (!manual && energy >= 0 && energy < primaryMinSpecPct()) return;
            // Manual Q / auto dump still specs through protect-melee.

            WeaponRef primary = findWeapon(false);
            if (primary == null) {
                logMissingWeapon(label, false);
                lastAction = "NO_" + label.toUpperCase() + "@" + currentTick;
                return;
            }
            if (InventoryTracker.isWhip(primary.itemId, primary.name)) return;

            comboStartEnergy = energy;
            activatePiety();

            if (isDmaceCombo()) {
                startDmaceCombo(primary);
                return;
            }

            beginAgsDump(primary);
        } catch (Exception e) {
            lastAction = "SPEC_ERR@" + currentTick;
            FontManager.log("[CombatScript] " + label + " combo error: " + e);
        }
    }

    /** AGS is 2h — never try to pair it with defender. Wield, then spec next tick if needed. */
    private void beginAgsDump(WeaponRef primary) {
        String label = primarySpecLabel();
        if (primary.equipped || primaryCurrentlyEquipped()) {
            fireAgsSpecNow();
            return;
        }
        wieldItem(primary.slot, primary.itemId);
        pendingAgsSpec = true;
        agsDumpTick = currentTick;
        lastAction = label.toUpperCase() + "_WIELD@" + currentTick;
    }

    /** Spec + attack with primary confirmed on. AGS/DMace/claws watch splat before gmaul. */
    private void fireAgsSpecNow() {
        if (!primaryCurrentlyEquipped()) {
            WeaponRef primary = findWeapon(false);
            if (primary == null) {
                logMissingWeapon(primarySpecLabel(), false);
                lastAction = "NO_" + primarySpecLabel().toUpperCase() + "@" + currentTick;
                return;
            }
            if (++primaryWieldTries > 2) {
                lastAction = "NO_" + primarySpecLabel().toUpperCase() + "@" + currentTick;
                logMissingWeapon(primarySpecLabel(), false);
                logInventorySnapshot("WIELD_FAIL");
                return;
            }
            if (primary.inInventory()) wieldItem(primary.slot, primary.itemId);
            pendingAgsSpec = true;
            agsDumpTick = currentTick;
            lastAction = primarySpecLabel().toUpperCase() + "_WIELD@" + currentTick;
            return;
        }
        primaryWieldTries = 0;
        specAndAttack();
        agsSpecTick = currentTick;
        lastHeadlessSpecTick = currentTick;
        agsSpecFromScript = true;
        String tag = isDmaceCombo() ? "DMACE_SPEC" : (isClawsCombo() ? "CLAWS_SPEC" : "AGS_SPEC");
        armAgsWatch(currentTick, tag);
    }

    private boolean primaryCurrentlyEquipped() {
        int[] eq = readAllEquipmentIds();
        if (eq == null) return false;
        if (eq.length > 3) {
            int id = decodeEquipId(eq[3]);
            if (id > 0 && isPrimaryId(id, resolveItemName(id))) return true;
        }
        for (int e : eq) {
            int id = decodeEquipId(e);
            if (id <= 0) continue;
            if (isPrimaryId(id, resolveItemName(id))) return true;
        }
        return false;
    }

    private boolean isPrimaryId(int id, String name) {
        if (isClawsCombo()) return InventoryTracker.isDragonClaws(id, name);
        if (isDmaceCombo()) return InventoryTracker.isDragonMace(id, name);
        return InventoryTracker.isAgs(id, name);
    }

    private void armAgsWatch(int tick, String label) {
        if (agsWatchArmed) return;
        agsWatchArmed = true;
        agsWatchTick = tick;
        watchSplatBaselineCycle = lastSeenSplatCycle;
        watchSplatSum = 0;
        lastHitsplatDmg = -1;
        lastAction = label + "@" + tick;
    }

    /**
     * True only when we put the AGS on ourselves. If you specced by hand we must
     * not yank your weapon back afterwards — that is the gear flapping.
     */
    private volatile boolean agsSpecFromScript = false;

    /**
     * Decides the gmaul follow-up from our own outgoing splat. A high splat
     * means the AGS landed, so dump gmaul for the KO. Q / auto-spec set
     * {@link #forceGmaulFollow} so gmaul still fires on a low roll or missed splat.
     */
    private void resolveAgsFollowUp(int tick) {
        refreshOutgoingSplat();
        boolean freshSplat = lastSeenSplatCycle > watchSplatBaselineCycle;
        // Claws land 4 hitsplats — use the running total, not the last 8.
        int hit = isClawsCombo() ? watchSplatSum : lastHitsplatDmg;
        int wait = isClawsCombo() ? CLAWS_SPLAT_WAIT : 0;
        if (forceGmaulFollow && tick - agsWatchTick > wait) {
            // For claws, the player only wants the gmaul on a 50+ splat — do
            // NOT force it on a low roll. For AGS/dmace, Q-dump still follows.
            if (isClawsCombo() && hit < Math.max(1, clawsHighHitMin)) {
                agsWatchArmed = false;
                forceGmaulFollow = false;
                agsSpecFromScript = false;
                lastAction = "NOGMAUL_" + hit + "of" + Math.max(1, clawsHighHitMin) + "@" + tick;
                scheduleBaselineRestore();
                return;
            }
            agsWatchArmed = false;
            pendingGmaulDump = true;
            forceGmaulFollow = false;
            lastAction = "GMAUL_Q@" + tick;
            return;
        }
        if (isClawsCombo() && tick - agsWatchTick < wait && hit < Math.max(1, clawsHighHitMin)) {
            return;
        }

        if (freshSplat && hit >= 0) {
            agsWatchArmed = false;
            if (splatAlreadyKo(hit) && !forceGmaulFollow) {
                finishPrimarySpecNoGmaul(tick);
                return;
            }
            if (forceGmaulFollow || shouldGmaulFollow(hit)) {
                pendingGmaulDump = true;
                forceGmaulFollow = false;
                lastAction = "GMAUL_ON_" + hit + "@" + tick;
            } else {
                int need = isClawsCombo() ? clawsHighHitMin : agsHighHitMin;
                lastAction = (findWeapon(true) != null ? "NOGMAUL_" + hit + "of" + Math.max(1, need)
                        : "NOGMAUL_NOWEP") + "@" + tick;
                finishPrimarySpecNoGmaul(tick);
            }
            return;
        }
        if (tick - agsWatchTick < AGS_SPLAT_WAIT) return;
        boolean fromScript = agsSpecFromScript;
        agsWatchArmed = false;
        agsSpecFromScript = false;
        if (forceGmaulFollow) {
            pendingGmaulDump = true;
            forceGmaulFollow = false;
            lastAction = "GMAUL_FORCE@" + tick;
            return;
        }
        if (isDmaceCombo()) queueStatiusWack(tick);
        else if (fromScript) scheduleBaselineRestore();
        lastAction = (isClawsCombo() ? "CLAWS_NOSPLAT@" : (isDmaceCombo() ? "DMACE_NOSPLAT@" : "AGS_NOSPLAT@")) + tick;
    }

    /** Next tick after primary spec: same-tick gmaul wield + spec if still worth it. */
    private void executePendingGmaulDump() {
        pendingGmaulDump = false;
        lastOffensiveSwapTick = currentTick;
        if (isDmaceCombo()) queueStatiusWack(currentTick);
        else pendingWhipDef = true;
        agsSpecFromScript = false;
        if (!fireGmaulSameTick("GMAUL_FOLLOW")) {
            return;
        }
    }

    private void queueStatiusWack(int tick) {
        pendingStatiusWack = true;
        pendingWhipDef = false;
        lastOffensiveSwapTick = tick;
    }

    /** After d mace spec (and optional gmaul): Statius + d defender wack, then whip + d def. */
    private void executeStatiusWack() {
        WeaponRef sw = findStatiusWeapon();
        if (sw != null && sw.inInventory()) wieldItem(sw.slot, sw.itemId);
        wieldDragonDefender();
        reAttackTarget();
        scheduleBaselineRestore();
        lastAction = "SW_WACK@" + currentTick;
    }

    /** True while a wield → spec → gmaul sequence is mid-flight. */
    public boolean isSpecSequenceBusy() {
        return pendingQDump || pendingAgsSpec || pendingGmaulDump || agsWatchArmed || pendingStatiusWack
                || pendingDbowSpec || pendingAxesWard || pendingAxeSpec
                || pendingKnivesWard || dmacePhase > 0;
    }

    private void startDmaceCombo(WeaponRef mace) {
        pendingAgsSpec = false;
        pendingGmaulDump = false;
        pendingWhipDef = false;
        pendingStatiusWack = false;
        agsWatchArmed = false;
        if (mace.inInventory()) wieldItem(mace.slot, mace.itemId);
        wieldDragonDefender();
        dmacePhase = 1;
        dmacePhaseTick = currentTick;
        lastAction = "DMACE_WIELD@" + currentTick;
    }

    /**
     * DMace is 4-tick. Each step waits so the previous hit is committed
     * before the next wield. Skipping a wait cancels the spec or the wack.
     *   1 wield mace+def
     *   2 spec
     *   3 wait splat (5 ticks) — gmaul only on 50+
     *   4 optional gmaul
     *   5 wield Statius+def
     *   6 attack
     *   7 wait, then whip+def
     */
    private void runDmacePhase(int tick) {
        int waited = tick - dmacePhaseTick;
        switch (dmacePhase) {
            case 1:
                if (waited < DMACE_WIELD_WAIT) return;
                fireAgsSpecNow();
                dmacePhase = 3;
                dmacePhaseTick = tick;
                lastAction = "DMACE_SPEC@" + tick;
                return;
            case 3: {
                refreshOutgoingSplat();
                boolean fresh = lastSeenSplatCycle > watchSplatBaselineCycle && lastHitsplatDmg >= 0;
                // Stay on the mace at least 3 ticks or the spec is cancelled.
                if (fresh && waited >= 3) {
                    int hit = lastHitsplatDmg;
                    boolean gmaul = shouldGmaulFollow(hit)
                            && (specEnergy < 0 || specEnergy >= 50);
                    lastAction = (gmaul ? "GMAUL_ON_" : "NOGMAUL_") + hit + "@" + tick;
                    dmacePhase = gmaul ? 4 : 5;
                    dmacePhaseTick = tick;
                    return;
                }
                if (waited >= DMACE_SPLAT_WAIT) {
                    lastAction = "DMACE_NOSPLAT@" + tick;
                    dmacePhase = 5;
                    dmacePhaseTick = tick;
                }
                return;
            }
            case 4:
                executeDmaceGmaul();
                dmacePhase = 5;
                dmacePhaseTick = tick;
                return;
            case 5:
                if (waited < STAT_WIELD_WAIT) return;
                wieldStatiusAndDef();
                dmacePhase = 6;
                dmacePhaseTick = tick;
                lastAction = "SW_WIELD@" + tick;
                return;
            case 6:
                if (waited < STAT_WIELD_WAIT) return;
                reAttackTarget();
                dmacePhase = 7;
                dmacePhaseTick = tick;
                lastAction = "SW_WACK@" + tick;
                return;
            case 7:
                if (waited < STAT_HIT_WAIT) return;
                reequipWhipAndDef();
                abortComboState();
                return;
            default:
                abortComboState();
        }
    }

    private void executeDmaceGmaul() {
        fireGmaulSameTick("GMAUL_FOLLOW");
    }

    private void wieldStatiusAndDef() {
        WeaponRef sw = findStatiusWeapon();
        if (sw != null && sw.inInventory()) wieldItem(sw.slot, sw.itemId);
        wieldDragonDefender();
    }

    /** Spec orb (frame 185/5004) then attack — the 1-tick spec pair. */
    private boolean specAndAttack() {
        pulseSpecOn();
        return reAttackTarget();
    }

    /** Wield opcode 454 on inventory interface 3214 (sends frame 41). */
    private void wieldFromSlot(int slot) {
        if (slot < 0) return;
        int rawId = getInventoryItemId(slot);
        if (rawId <= 0) return;
        wieldItem(slot, rawId - 1);
    }

    private void wieldItem(int slot, int itemId) {
        if (doActionMethod == null || slot < 0 || itemId < 0) return;
        try {
            doActionMethod.invoke(clientInstance, 0, slot, 3214, 454, itemId, 0, "Wield", "", -1, -1);
        } catch (Exception e) {
            FontManager.log("[CombatScript] wieldItem error: " + e.getMessage());
        }
    }

    /**
     * Always send spec-orb packet 185/5004. Never skip based on the local flag —
     * that flag stays true after our last dump and was swallowing the next AGS spec.
     */
    private void pulseSpecOn() {
        setClientBool(weaponHasSpecField, true);
        if (!sendClickingButton(5004)) {
            sendSpecOrbFallback();
        }
        setClientBool(specEnabledField, true);
    }

    /** Frame 185 / button 5004 — identical to spec-orb click. */
    boolean sendClickingButton(int buttonId) {
        Object helper = livePacketHelper();
        Method meth = sendClickingButtonMethod;
        if (helper != null) {
            Method live = RtLookup.method(helper.getClass(), "sendClickingButton", 1);
            if (live != null) meth = live;
        }
        if (helper == null || meth == null) return false;
        try {
            meth.invoke(helper, buttonId);
            return true;
        } catch (Exception e) {
            FontManager.log("[CombatScript] sendClickingButton(" + buttonId + ") failed: " + e.getMessage());
            return false;
        }
    }

    /** Last-resort spec: write frame 185/5004 onto Client.buffer ourselves. */
    private void sendSpecOrbFallback() {
        if (bufferField == null) return;
        try {
            Object buf = bufferField.get(clientInstance);
            if (buf == null) return;
            if (bufferCreateFrame == null) {
                bufferCreateFrame = buf.getClass().getMethod("createFrame", int.class);
                bufferWriteUnsignedShort = buf.getClass().getMethod("writeUnsignedShort", int.class);
            }
            bufferCreateFrame.invoke(buf, 185);
            bufferWriteUnsignedShort.invoke(buf, 5004);
        } catch (Exception e) {
            FontManager.log("[CombatScript] spec buffer fallback failed: " + e.getMessage());
        }
    }

    /** Piety via prayer packet 186 (id 27). Never sends protect prayers. */
    private void activatePiety() {
        ensureOffensivePrayer(AnimationDb.AttackStyle.MELEE);
    }

    private void ensurePiety() {
        prayer.ensurePiety();
    }

    private void ensureMysticMight() {
        prayer.ensureMysticMight();
    }

    private void ensureEagleEye() {
        prayer.ensureEagleEye();
    }

    private void ensureOffensivePrayer(AnimationDb.AttackStyle style) {
        prayer.ensureOffensivePrayer(style);
    }

    private boolean isPietyActive() {
        return prayer.isPietyActive();
    }

    private boolean isPrayerActive(String enumName) {
        return prayer.isPrayerActive(enumName);
    }

    /** Protect hotkeys: Z = mage, X = range, C = melee (fires this tick). */
    public void triggerProtectMagic()  { prayer.triggerProtectMagic(); }
    public void triggerProtectRange()  { prayer.triggerProtectRange(); }
    public void triggerProtectMelee()  { prayer.triggerProtectMelee(); }

    private void fireProtectNow(int prayerId) {
        prayer.fireProtectNow(prayerId);
    }

    private void queueProtectPrayer(int prayerId) {
        prayer.queueProtectPrayer(prayerId);
    }

    private void activateProtectPrayer(int prayerId, String label) {
        prayer.activateProtectPrayer(prayerId, label);
    }

    private boolean invokePrayerButtonClick(String enumName) {
        return prayer.invokePrayerButtonClick(enumName);
    }

    private int livePrayerId(String enumName, int fallback) {
        return prayer.livePrayerId(enumName, fallback);
    }

    private void setPrayerActive(String enumName, boolean on) {
        prayer.setPrayerActive(enumName, on);
    }

    private void deactivateOtherProtectOverheads(String keepEnum) {
        prayer.deactivateOtherProtectOverheads(keepEnum);
    }

    private void markProtectActivated(int prayerId, String label, long now) {
        prayer.markProtectActivatedPublic(prayerId, label, now);
    }

    private boolean trySendPrayerPacket(int prayerId) {
        return prayer.trySendPrayerPacket(prayerId);
    }

    private boolean trySendPrayerViaMap(int prayerId) {
        return prayer.trySendPrayerViaMap(prayerId);
    }

    private boolean trySendPrayerEnum(int prayerId) {
        String enumName = AnimationDb.protectPrayerEnumName(prayerId);
        if (enumName == null) return false;
        return prayer.trySendPrayerEnumByNamePublic(enumName);
    }

    private int resolvePrayerWidgetId(int prayerId) {
        return prayer.resolvePrayerWidgetId(prayerId);
    }

    private boolean clickProtectWidget(int prayerId) {
        return prayer.clickProtectWidget(prayerId);
    }

    private boolean sendPrayerBufferFallback(int prayerId) {
        return prayer.sendPrayerBufferFallback(prayerId);
    }

    private static AnimationDb.AttackStyle prayerIdToStyle(int id) {
        return PrayerController.prayerIdToStyle(id);
    }

    private void ensureProtectFromStyle(AnimationDb.AttackStyle style) {
        prayer.ensureProtectFromStyle(style);
    }

    /** Switch protect when the target's attack style changes or they hit us. */
    private void runAutoDefPrayer(int tick) {
        prayer.runAutoDefPrayer(tick);
    }

    private AnimationDb.AttackStyle detectDefPrayStyle() {
        return prayer.detectDefPrayStyle();
    }

    private void refreshAttackTarget() {
        Object myPlayer = null;
        try { myPlayer = myPlayerField != null ? myPlayerField.get(null) : null; } catch (Exception ignored) {}
        if (myPlayer == null) return;
        Object target = null;
        if (getInteractingMethod != null) {
            try { target = getInteractingMethod.invoke(myPlayer); } catch (Exception ignored) {}
        }
        if (target == null) target = stickyTarget != null ? stickyTarget : cachedTarget;
        if (target != null) cacheAttackTarget(myPlayer, target);
    }

    /**
     * Locate AGS (gmaul=false) or Granite Maul (gmaul=true) in equipment first,
     * then inventory by id/name. Never returns a whip.
     */
    /**
     * Inventory is checked first on purpose. myPlayerEquipmentIds only refreshes
     * when the server echoes container 1688, so trusting it first could report
     * the AGS as worn while the gmaul was still on — every spec came out a gmaul.
     * If it is in the bag, wielding it is always the correct move.
     */
    private WeaponRef findWeapon(boolean gmaul) {
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isWhip(id, name)) continue;
            boolean match = gmaul
                    ? InventoryTracker.isGmaul(id, name)
                    : (isDmaceCombo()
                        ? InventoryTracker.isDragonMace(id, name)
                        : (isClawsCombo()
                            ? InventoryTracker.isDragonClaws(id, name)
                            : InventoryTracker.isAgs(id, name)));
            if (match) return new WeaponRef(slot, id, name, false);
        }

        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (InventoryTracker.isWhip(id, name)) continue;
                boolean match = gmaul
                    ? InventoryTracker.isGmaul(id, name)
                    : (isDmaceCombo()
                        ? InventoryTracker.isDragonMace(id, name)
                        : (isClawsCombo()
                            ? InventoryTracker.isDragonClaws(id, name)
                            : InventoryTracker.isAgs(id, name)));
                if (match) return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
            }
        }
        return null;
    }

    private WeaponRef findDragonMaceWeapon() {
        return findGear(InventoryTracker::isDragonMace);
    }

    private WeaponRef findAgsWeapon() {
        return findGear(InventoryTracker::isAgs);
    }

    private WeaponRef findClawsWeapon() {
        return findGear(InventoryTracker::isDragonClaws);
    }

    private WeaponRef findGear(java.util.function.BiPredicate<Integer, String> match) {
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (match.test(id, name)) return new WeaponRef(slot, id, name, false);
        }
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (match.test(id, name)) return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
            }
        }
        return null;
    }

    private WeaponRef findStatiusWeapon() {
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isStatius(id, name)) return new WeaponRef(slot, id, name, false);
        }
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (InventoryTracker.isStatius(id, name)) {
                    return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
                }
            }
        }
        return null;
    }

    /**
     * Dragon defender first, then any defender. Inventory before equipment so
     * a 2h gmaul does not hide the defender sitting in the bag.
     */
    private WeaponRef findDragonDefender() {
        WeaponRef dragon = findDefender(true);
        if (dragon != null) return dragon;
        return findDefender(false);
    }

    private WeaponRef findDefender(boolean dragonOnly) {
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            boolean match = dragonOnly
                    ? InventoryTracker.isDragonDefender(id, name)
                    : InventoryTracker.isDefender(id, name);
            if (match) return new WeaponRef(slot, id, name, false);
        }
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                boolean match = dragonOnly
                        ? InventoryTracker.isDragonDefender(id, name)
                        : InventoryTracker.isDefender(id, name);
                if (match) return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
            }
        }
        return null;
    }

    /** Wield d defender from the bag. True when a wield packet went out. */
    private boolean wieldDragonDefender() {
        WeaponRef def = findDragonDefender();
        if (def == null) {
            FontManager.debug("[CombatScript] Dragon defender not found");
            return false;
        }
        if (!def.inInventory()) return false;
        wieldItem(def.slot, def.itemId);
        return true;
    }

    /** 2h axe/gmaul needs a free bag slot for the whip/defender. Full food blocks the swap. */
    private int inventoryEmptySlots() {
        int[] inv = getInventorySnapshot();
        int n = 0;
        for (int raw : inv) if (raw <= 0) n++;
        return n;
    }

    private void eatOneFoodForSpace() {
        int[] inv = getInventorySnapshot();
        int food = findPrimaryFood(inv, -1);
        if (food < 0) food = findHalibutSlot(inv, -1);
        if (food < 0) food = findMarlinSlot(inv, -1);
        if (food >= 0) {
            eatFromSlot(food, false);
            FontManager.log("[CombatScript] Ate one food to free a slot for 2h swap");
        }
    }

    private boolean ensureGreataxeEquipped() {
        WeaponRef axe = findGreataxe();
        if (axe == null) return false;
        if (axe.equipped) return true;
        if (inventoryEmptySlots() == 0) {
            eatOneFoodForSpace();
            if (inventoryEmptySlots() == 0) return false;
            axe = findGreataxe();
            if (axe == null) return false;
            if (axe.equipped) return true;
        }
        if (!axe.inInventory()) return false;
        wieldItem(axe.slot, axe.itemId);
        return true;
    }

    private boolean ensureGmaulEquipped() {
        WeaponRef gmaul = findWeapon(true);
        if (gmaul == null) return false;
        if (gmaul.equipped) return true;
        if (inventoryEmptySlots() == 0) {
            eatOneFoodForSpace();
            if (inventoryEmptySlots() == 0) return false;
            gmaul = findWeapon(true);
            if (gmaul == null) return false;
            if (gmaul.equipped) return true;
        }
        if (!gmaul.inInventory()) return false;
        wieldItem(gmaul.slot, gmaul.itemId);
        return true;
    }

    /** DH axe is a normal hit — spec must be off or a whip dump goes out. */
    private void pulseSpecOff() {
        setClientBool(specEnabledField, false);
    }

    private WeaponRef findGreataxe() {
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (InventoryTracker.isDharokAxe(id, name)) {
                    return new WeaponRef(WeaponRef.EQUIPPED, id, name, true);
                }
            }
        }
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isDharokAxe(id, name)) {
                return new WeaponRef(slot, id, name, false);
            }
        }
        return null;
    }

    private void readTargetHealth(Object target) {
        targetHp = -1;
        targetMaxHp = -1;
        if (target == null) return;
        try {
            if (actorCurrentHealthField == null) {
                actorCurrentHealthField = findField(target.getClass(), "currentHealth");
                actorMaxHealthField = findField(target.getClass(), "maxHealth");
            }
            if (actorCurrentHealthField != null) {
                targetHp = actorCurrentHealthField.getInt(target);
            }
            if (actorMaxHealthField != null) {
                targetMaxHp = actorMaxHealthField.getInt(target);
            }
            if (targetMaxHp <= 0 && targetHp > 0) targetMaxHp = Math.max(targetHp, 99);
            if (targetHp > 0) {
                lastKnownTargetHp = targetHp;
                lastKnownTargetHpTick = currentTick;
            } else if (lastHitsplatDmg > 0 && targetMaxHp > 0 && hitsplatChangeTick == currentTick) {
                // Fallback when actor HP field is hidden — estimate from our outgoing hit.
                int est = Math.max(1, targetMaxHp - lastHitsplatDmg);
                if (lastKnownTargetHp > 0) {
                    est = Math.max(1, lastKnownTargetHp - lastHitsplatDmg);
                }
                lastKnownTargetHp = est;
                lastKnownTargetHpTick = currentTick;
            }
        } catch (Exception ignored) {}
    }

    /** Detect the target's attack style from their wielded weapon (range/magic/melee). */
    public AnimationDb.AttackStyle targetWeaponStyle(Object target) {
        if (target == null) return AnimationDb.AttackStyle.UNKNOWN;
        try {
            if (playerEquipmentField == null) {
                playerEquipmentField = findField(target.getClass(), "equipmentItemId");
            }
            int[] eq = null;
            if (playerEquipmentField != null) {
                eq = (int[]) playerEquipmentField.get(target);
            }
            if (eq == null) {
                Method getEq = findMethod(target.getClass(), "getEquipmentIds", 0);
                if (getEq != null) eq = (int[]) getEq.invoke(target);
            }
            if (eq == null || eq.length < 4) return AnimationDb.AttackStyle.UNKNOWN;
            // Weapon slot is index 3 in equipment.
            int wid = decodeEquipId(eq.length > 3 ? eq[3] : -1);
            if (wid <= 0) {
                for (int e : eq) {
                    int id = decodeEquipId(e);
                    if (id > 0) { wid = id; break; }
                }
            }
            if (wid <= 0) return AnimationDb.AttackStyle.UNKNOWN;
            String name = resolveItemName(wid);
            String n = InventoryTracker.stripName(name);
            if (n.contains("bow") || n.contains("crossbow") || n.contains("ballista")
                    || n.contains("thrownaxe") || n.contains("knife") || n.contains("javelin")
                    || n.contains("chinchompa") || n.contains("blowpipe")) {
                return AnimationDb.AttackStyle.RANGED;
            }
            if (InventoryTracker.isNonAutocastStaff(wid, name) || InventoryTracker.isAutocastStaff(wid, name)
                    || n.contains("staff") || n.contains("wand") || n.contains("trident")
                    || n.contains("sanguinesti")) {
                return AnimationDb.AttackStyle.MAGIC;
            }
            return AnimationDb.AttackStyle.MELEE;
        } catch (Exception e) {
            return AnimationDb.AttackStyle.UNKNOWN;
        }
    }

    private boolean targetLooksLikeDharok(Object target) {
        if (target == null) return false;
        try {
            if (playerEquipmentField == null) {
                playerEquipmentField = findField(target.getClass(), "equipmentItemId");
            }
            int[] eq = null;
            if (playerEquipmentField != null) {
                eq = (int[]) playerEquipmentField.get(target);
            }
            if (eq == null) {
                Method getEq = findMethod(target.getClass(), "getEquipmentIds", 0);
                if (getEq != null) eq = (int[]) getEq.invoke(target);
            }
            if (eq == null) return false;
            int pieces = 0;
            boolean axe = false;
            for (int i = 0; i < eq.length; i++) {
                int id = decodeEquipId(eq[i]);
                if (id <= 0) continue;
                String name = resolveItemName(id);
                if (InventoryTracker.isDharokAxe(id, name)) axe = true;
                if (InventoryTracker.isDharokPiece(id, name)) pieces++;
            }
            return axe || pieces >= 3;
        } catch (Exception e) {
            return false;
        }
    }

    /** Greataxe KO only if we are actually DH stacking — not just because the axe is in the bag. */
    private boolean weAreDhStacked(int ourHp, WeaponRef axe) {
        if (axe == null) return false;
        if (axe.equipped && ourHp > 0 && ourHp <= 45) return true;
        int pieces = 0;
        int[] eq = readAllEquipmentIds();
        if (eq != null) {
            for (int e : eq) {
                int id = decodeEquipId(e);
                if (id <= 0) continue;
                if (InventoryTracker.isDharokPiece(id, resolveItemName(id))) pieces++;
            }
        }
        return pieces >= 3 && ourHp > 0 && ourHp <= 45;
    }

    private void logMissingWeapon(String label, boolean gmaul) {
        int[] eq = readAllEquipmentIds();
        int wielded = (eq != null && eq.length > 3) ? decodeEquipId(eq[3]) : -1;
        String wieldName = wielded > 0 ? resolveItemName(wielded) : "?";
        int[] inv = getInventorySnapshot();
        int filled = 0;
        for (int raw : inv) if (raw > 0) filled++;
        FontManager.log("[CombatScript] " + label + " not found. equipped[3]=" + wielded
                + " (" + InventoryTracker.stripName(wieldName) + ") invFilled=" + filled
                + " lookingFor=" + (gmaul ? "gmaul" : primarySpecLabel().toLowerCase()));
    }

    private void logInventorySnapshot(String why) {
        int[] inv = getInventorySnapshot();
        StringBuilder sb = new StringBuilder("[CombatScript] ").append(why).append(" inv=");
        int n = 0;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            if (n++ > 0) sb.append(';');
            sb.append(id).append(':').append(InventoryTracker.stripName(resolveItemName(id)));
        }
        int[] eq = readAllEquipmentIds();
        int wielded = (eq != null && eq.length > 3) ? decodeEquipId(eq[3]) : -1;
        sb.append(" wield=").append(wielded).append(':')
                .append(wielded > 0 ? InventoryTracker.stripName(resolveItemName(wielded)) : "?");
        FontManager.log(sb.toString());
    }

    private int[] readAllEquipmentIds() {
        if (equipmentIdsField != null) {
            try {
                int[] ids = (int[]) equipmentIdsField.get(null);
                if (ids != null) return ids;
            } catch (Exception ignored) {}
        }
        try {
            Object myPlayer = myPlayerField != null ? myPlayerField.get(null) : null;
            if (myPlayer == null) return null;
            Method getEq = findMethod(myPlayer.getClass(), "getEquipmentIds", 0);
            if (getEq == null) return null;
            return (int[]) getEq.invoke(myPlayer);
        } catch (Exception ignored) {}
        return null;
    }

    /** Interface 1688 stores real item ids (not appearance +256). */
    private static int decodeEquipId(int raw) {
        return raw > 0 ? raw : -1;
    }

    /** Worn item for Advanced Swapper gear snapshots. */
    public static final class EquippedPiece {
        public final int slot;
        public final int itemId;
        public final String name;
        public EquippedPiece(int slot, int itemId, String name) {
            this.slot = slot;
            this.itemId = itemId;
            this.name = name != null ? name : "";
        }
    }

    /**
     * Snapshot currently worn gear (helm → ammo). Prefers {@code myPlayerEquipmentIds}
     * (real item ids), then interface 1688 (stored as id+1).
     */
    public EquippedPiece[] snapshotEquippedGear() {
        int[] ids = readAllEquipmentIds();
        if (ids == null || allEmpty(ids)) ids = readEquipmentInterface1688();
        if (ids == null) return new EquippedPiece[0];
        java.util.List<EquippedPiece> out = new java.util.ArrayList<>();
        for (int slot = 0; slot < ids.length; slot++) {
            int id = decodeEquipId(ids[slot]);
            if (id <= 0) continue;
            String name = resolveItemName(id);
            out.add(new EquippedPiece(slot, id, name));
        }
        return out.toArray(new EquippedPiece[0]);
    }

    /**
     * Swapper script for the currently worn set:
     * {@code e:11802  // armadyl godsword}
     */
    public String snapshotEquippedGearCommands() {
        EquippedPiece[] pieces = snapshotEquippedGear();
        if (pieces.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (EquippedPiece p : pieces) {
            sb.append("e:").append(p.itemId);
            String n = InventoryTracker.stripName(p.name);
            if (!n.isEmpty()) sb.append("  // ").append(n);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static boolean allEmpty(int[] ids) {
        if (ids == null) return true;
        for (int id : ids) if (decodeEquipId(id) > 0) return false;
        return true;
    }

    /** Equipment container 1688 — values are itemId+1 like inventory. */
    private int[] readEquipmentInterface1688() {
        if (interfaceCacheField == null) return null;
        try {
            Object[] cache = (Object[]) interfaceCacheField.get(null);
            if (cache == null || cache.length <= 1688 || cache[1688] == null) return null;
            Object iface = cache[1688];
            Field invF = inventoryItemIdField(iface);
            if (invF == null) return null;
            int[] raw = (int[]) invF.get(iface);
            if (raw == null) return null;
            int[] ids = new int[raw.length];
            for (int i = 0; i < raw.length; i++) ids[i] = raw[i] > 0 ? raw[i] - 1 : -1;
            return ids;
        } catch (Exception e) {
            return null;
        }
    }

    /** Equipment interface 1688 slot 3 — stored as real item id (-1 empty). */
    private int readEquippedWeaponId() {
        int[] ids = readAllEquipmentIds();
        if (ids != null && ids.length > 3) return decodeEquipId(ids[3]);
        return -1;
    }

    private static final class WeaponRef {
        static final int EQUIPPED = -2;
        final int slot;
        final int itemId;
        final String name;
        final boolean equipped;
        WeaponRef(int slot, int itemId, String name, boolean equipped) {
            this.slot = slot;
            this.itemId = itemId;
            this.name = name;
            this.equipped = equipped;
        }
        boolean inInventory() { return slot >= 0; }
        @Override public String toString() {
            String n = InventoryTracker.stripName(name);
            if (n.isEmpty()) n = "id=" + itemId;
            return equipped ? ("equipped:" + n) : ("slot" + slot + ":" + n + "(" + itemId + ")");
        }
    }

    private void setClientBool(Field f, boolean value) {
        if (f == null) return;
        try { f.setBoolean(clientInstance, value); } catch (Exception ignored) {}
    }

    private boolean getClientBool(Field f) {
        if (f == null) return false;
        try { return f.getBoolean(clientInstance); } catch (Exception e) { return false; }
    }

    /** Returns false when overhead checks are enabled and target has protect-from-melee up. */
    private boolean targetOverheadAllowsSpec() {
        if (!overheadChecksEnabled) return true;
        Object myPlayer = null;
        try { myPlayer = myPlayerField != null ? myPlayerField.get(null) : null; } catch (Exception ignored) {}
        if (myPlayer == null || getInteractingMethod == null) return true;
        try {
            Object target = getInteractingMethod.invoke(myPlayer);
            if (target == null) return true;
            Field headIconField = findField(target.getClass(), "headIcon");
            if (headIconField == null) headIconField = findField(target.getClass(), "overheadIcon");
            if (headIconField == null) return true;
            int icon = headIconField.getInt(target);
            // 0=melee, 1=mage, 2=ranged — skip spec when protected from melee (AGS/Gmaul are melee)
            return icon != 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** Equip first inventory item whose name contains the given fragment (case-insensitive). */
    public boolean equipByName(String nameFragment) {
        int slot = findInventorySlotByName(nameFragment);
        if (slot < 0) return false;
        equipFromSlot(slot);
        return true;
    }

    /** Equip by exact item id (inventory stored as id+1). */
    public boolean equipById(int itemId) {
        if (itemId <= 0) return false;
        int slot = findInventorySlotById(itemId);
        if (slot < 0) return false;
        equipFromSlot(slot);
        return true;
    }

    public int findInventorySlotById(int itemId) {
        if (itemId <= 0) return -1;
        int[] ids = getInventorySnapshot();
        for (int slot = 0; slot < ids.length; slot++) {
            if (ids[slot] > 0 && ids[slot] - 1 == itemId) return slot;
        }
        return -1;
    }

    /** Drop all inventory stacks matching item id (opcode 847 / iface 3214). */
    public boolean dropById(int itemId) {
        if (doActionMethod == null || itemId <= 0) return false;
        boolean any = false;
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            if (inv[slot] > 0 && inv[slot] - 1 == itemId) {
                try {
                    doActionMethod.invoke(clientInstance, 0, slot, 3214, 847, itemId, 0,
                            "Drop", "", -1, -1);
                    any = true;
                } catch (Exception e) {
                    FontManager.log("[CombatScript] dropById error: " + e.getMessage());
                }
            }
        }
        return any;
    }

    public boolean dropByName(String nameFragment) {
        int slot = findInventorySlotByName(nameFragment);
        if (slot < 0) return false;
        int raw = getInventoryItemId(slot);
        return raw > 0 && dropById(raw - 1);
    }

    /** Eat/drink inventory item (opcode 74). */
    public boolean useItemById(int itemId) {
        int slot = findInventorySlotById(itemId);
        if (slot < 0) return false;
        eatFromSlot(slot, true);
        return true;
    }

    public boolean useItemByName(String nameFragment) {
        int slot = findInventorySlotByName(nameFragment);
        if (slot < 0) return false;
        eatFromSlot(slot, true);
        return true;
    }

    /** Unequip by item id via PacketHelper frame 146 on iface 1688. */
    public boolean removeEquipById(int itemId) {
        if (itemId <= 0) return false;
        int[] eq = readAllEquipmentIds();
        if (eq == null) return false;
        for (int slot = 0; slot < eq.length; slot++) {
            int id = decodeEquipId(eq[slot]);
            if (id == itemId) return removeEquipSlot(slot, itemId);
        }
        return false;
    }

    public boolean removeEquipByName(String nameFragment) {
        if (nameFragment == null || nameFragment.isEmpty()) return false;
        int[] eq = readAllEquipmentIds();
        if (eq == null) return false;
        for (int slot = 0; slot < eq.length; slot++) {
            int id = decodeEquipId(eq[slot]);
            if (id <= 0) continue;
            String name = resolveItemName(id);
            if (name != null && InventoryTracker.nameMatches(name, nameFragment))
                return removeEquipSlot(slot, id);
        }
        return false;
    }

    private boolean removeEquipSlot(int slot, int itemId) {
        if (sendInterfaceItemClick(1688, slot, itemId, 1)) return true;
        if (doActionMethod == null) return false;
        try {
            // Classic remove fallback
            doActionMethod.invoke(clientInstance, 0, slot, 1688, 322, itemId, 0,
                    "Remove", "", -1, -1);
            return true;
        } catch (Exception e) {
            FontManager.log("[CombatScript] removeEquip error: " + e.getMessage());
            return false;
        }
    }

    private int inventoryActionRow(int itemId, String... want) {
        String[] opts = readItemInventoryOptions(itemId);
        if (opts == null) return -1;
        for (int i = 0; i < opts.length; i++) {
            if (opts[i] == null) continue;
            String low = opts[i].toLowerCase();
            for (String w : want) {
                if (low.contains(w)) return i;
            }
        }
        return -1;
    }

    /** Packet 146 — this client's real inventory Eat/Wield path (INTERFACE_ITEM_CLICK). */
    private boolean clickInterfaceItem(int iface, int slot, int itemId, int row) {
        Object helper = livePacketHelper();
        Method m = sendInterfaceItemClickMethod;
        if (helper != null) {
            Method live = RtLookup.fourIntVoid(helper.getClass());
            if (live != null) m = live;
        }
        if (m == null || helper == null) return false;
        try {
            m.invoke(helper, iface, slot, itemId, row);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sendInterfaceItemClick(int iface, int slot, int itemId, int row) {
        return clickInterfaceItem(iface, slot, itemId, row);
    }

    /** Activate prayer by friendly name (piety, protect item, protect from melee, …). */
    public boolean activatePrayerNamed(String prayerName) {
        return prayer.activatePrayerNamed(prayerName);
    }

    private boolean fireNamedPrayer(String prayerName) {
        return prayer.fireNamedPrayer(prayerName);
    }

    private Object resolvePrayerEnum(String prayerName, int fallbackId) {
        return prayer.resolvePrayerEnumPublic(prayerName, fallbackId);
    }

    /** Best-effort: turn off piety/rigour/augury/mystic/eagle via toggle packet. */
    public boolean disableOffensivePrayers() {
        return prayer.disableOffensivePrayers();
    }

    /**
     * Advanced Swapper spec commands:
     * <ul>
     *   <li>{@code spec} — orb 5004 + attack last target (same tick)</li>
     *   <li>{@code spec:combo} / {@code spec:ags_gmaul} — multi-tick Q dump
     *       (AGS/DMace → gmaul) via existing {@link #triggerSpecNow}</li>
     *   <li>{@code spec:gmaul} — gmaul follow dump</li>
     *   <li>{@code spec:ags} — primary weapon spec only</li>
     * </ul>
     * Do not try to pack AGS+Gmaul+attack into one swap line-list — that needs
     * multiple ticks; use {@code spec:combo} instead.
     */
    public boolean fireSpecCommand(String mode) {
        String m = mode == null ? "" : mode.trim().toLowerCase().replace(' ', '_').replace('-', '_');
        if (m.isEmpty() || m.equals("orb") || m.equals("on") || m.equals("attack")) {
            refreshAttackTarget();
            specAndAttack();
            lastAction = "SWAP_SPEC@" + currentTick;
            return true;
        }
        if (m.equals("combo") || m.equals("ags_gmaul") || m.equals("agsgmaul")
                || m.equals("dump") || m.equals("q") || m.equals("auto")
                || m.equals("dmace") || m.equals("dmace_gmaul") || m.equals("primary")
                || m.equals("claws") || m.equals("claws_gmaul") || m.equals("dclaws")) {
            triggerSpecNow();
            lastAction = "SWAP_COMBO_Q@" + currentTick;
            return true;
        }
        if (m.equals("gmaul") || m.equals("gmaul_follow") || m.equals("follow") || m.equals("gm")) {
            triggerGmaulFollowNow();
            lastAction = "SWAP_GMAUL@" + currentTick;
            return true;
        }
        if (m.equals("ags") || m.equals("statius") || m.equals("primary_only")) {
            executeAgsSpec();
            return true;
        }
        if (m.equals("gmaul_only") || m.equals("gmaul1t") || m.equals("double_gmaul")) {
            executeGmaulSpec();
            return true;
        }
        if (m.equals("vls")) {
            triggerVlsSpecNow();
            return true;
        }
        if (m.equals("dbow") || m.equals("dbow_axes")) {
            executeDbowAxesCombo(true);
            return true;
        }
        FontManager.log("[Swapper] unknown spec mode: " + mode + " (use spec / spec:combo / spec:gmaul)");
        refreshAttackTarget();
        return specAndAttack();
    }

    /** Spec orb only (frame 185/5004). Prefer {@link #fireSpecCommand}. */
    public void fireSpecOrb() {
        fireSpecCommand("");
    }

    /** Re-attack current target — Advanced Swapper {@code a:last}. */
    public boolean attackLastTarget() {
        return reAttackTarget();
    }

    /**
     * Advanced Swapper {@code c:Ice Barrage} / {@code c:vengeance} / {@code c:teleblock}.
     * Selects the spell, then casts on the current target (self-cast for vengeance).
     * If select succeeds but cast needs a second tick (SOTD/trident), queues finish.
     */
    public boolean castSpellNamed(String raw) {
        if (raw == null || raw.trim().isEmpty() || doActionMethod == null) return false;
        SpellRef spell = resolveSpell(raw.trim());
        if (spell == null) {
            FontManager.log("[Swapper] unknown spell: " + raw);
            return false;
        }

        if (spell.selfCast) {
            castVengeance();
            lastAction = "SWAP_VENG@" + currentTick;
            return true;
        }

        ensureMagicTab();
        refreshAttackTarget();

        boolean selected = selectSpell(spell);
        boolean cast = castSpellOnCurrentTarget(spell.displayName);
        if (selected && !cast && cachedAttackId >= 0) {
            pendingSwapCast = true;
            pendingSwapCastLabel = spell.displayName;
            pendingSwapCastWidget = spell.widgetId;
            lastAction = "SWAP_CAST_SEL@" + currentTick;
            return true;
        }
        if (cast) {
            lastAction = "SWAP_CAST@" + currentTick;
            return true;
        }
        if (selected) {
            lastAction = "SWAP_CAST_ARMED@" + currentTick;
            return true;
        }
        return false;
    }

    /**
     * Select a spell on the book and arm client click-cast so the next
     * left-click on a player is Cast Ice Barrage. Queued to the next tick
     * so a mage swap can finish wielding the staff first.
     */
    public boolean armLeftClickSpell(String raw) {
        if (raw == null || raw.trim().isEmpty()) raw = "Ice Barrage";
        pendingLeftClickSpell = raw.trim();
        lastAction = "LC_WAIT@" + currentTick;
        FontManager.log("[Swapper] queue left-click: " + pendingLeftClickSpell);
        return true;
    }

    public boolean armLeftClickIceBarrage() {
        return armLeftClickSpell("Ice Barrage");
    }

    private boolean finishArmLeftClickSpell(String raw) {
        SpellRef spell = resolveSpell(raw);
        if (spell == null) {
            lastAction = "LC_FAIL@" + currentTick;
            FontManager.log("[Swapper] unknown spell to arm: " + raw);
            return false;
        }
        if (spell.selfCast) {
            castVengeance();
            lastAction = "SWAP_VENG@" + currentTick;
            return true;
        }
        ensureMagicTab();
        boolean ice = spell.displayName.toLowerCase().contains("ice barrage")
                || spell.widgetId == iceBarrageWidgetId;
        boolean selected = ice ? selectIceBarrageSpell() : selectSpell(spell);
        boolean armed = armSpellSelected(ice ? iceBarrageWidgetId : spell.widgetId, spell.displayName);
        // Mage click-cast always wants Mystic Might on (account doesn't have Augury).
        pendingNamedPrayer = "mystic might";
        leftClickCastArmed = selected || armed;
        leftClickCastName = spell.displayName;
        leftClickCastWidget = ice ? iceBarrageWidgetId : spell.widgetId;
        cancelPendingWalk();
        if (selected || armed) {
            lastAction = "LC_" + spell.displayName.toUpperCase().replace(' ', '_') + "@" + currentTick;
            FontManager.log("[Swapper] left-click armed: " + spell.displayName
                    + " sel=" + selected + " arm=" + armed
                    + " staff=" + isStaffEquipped()
                    + " selectedField=" + readIntField(clientSpellSelectedField)
                    + " spellId=" + readIntField(clientSpellIdField)
                    + " usableOn=" + readIntField(clientSpellUsableOnField)
                    + " nameField=" + (clientSpellNameField != null));
            sendGameMessage("Cast " + spell.displayName + " — left-click a target");
            return true;
        }
        lastAction = "LC_FAIL@" + currentTick;
        return false;
    }

    /**
     * Walk onto the current opponent's tile (Advanced Swapper {@code walkunder}).
     */
    public boolean walkUnderTarget() {
        return walkUnder.walkUnderTarget();
    }

    private static final class SpellRef {
        final int widgetId;
        final String displayName;
        final boolean selfCast;
        SpellRef(int widgetId, String displayName, boolean selfCast) {
            this.widgetId = widgetId;
            this.displayName = displayName;
            this.selfCast = selfCast;
        }
    }

    private SpellRef resolveSpell(String raw) {
        String n = InventoryTracker.normalizeTokens(raw).replace(" ", "");
        // MagicSpell enum ids on Roat
        if (n.equals("icebarrage") || n.equals("ib") || n.equals("barrage"))
            return new SpellRef(12891, "Ice Barrage", false);
        if (n.equals("iceblitz") || n.equals("blitz"))
            return new SpellRef(12871, "Ice Blitz", false);
        if (n.equals("iceburst") || n.equals("burst"))
            return new SpellRef(12881, "Ice Burst", false);
        if (n.equals("icerush") || n.equals("rush"))
            return new SpellRef(12861, "Ice Rush", false);
        if (n.equals("teleblock") || n.equals("tb") || n.equals("teleblockspell"))
            return new SpellRef(12445, "Tele Block", false);
        if (n.equals("entangle") || n.equals("ent"))
            return new SpellRef(1592, "Entangle", false);
        if (n.equals("snare"))
            return new SpellRef(1582, "Snare", false);
        if (n.equals("bind"))
            return new SpellRef(1572, "Bind", false);
        if (n.equals("vengeance") || n.equals("veng") || n.equals("vengenace"))
            return new SpellRef(30306, "Vengeance", true);
        if (n.equals("teletotarget") || n.equals("t2t") || n.equals("ttt"))
            return new SpellRef(18941, "Tele to Target", false);

        // Fall back to MagicSpell enum reflection / widget scan
        try {
            Class<?> ms = RtLookup.magicSpell();
            if (ms == null) throw new ClassNotFoundException("ms");
            for (Object sp : (Object[]) ms.getMethod("values").invoke(null)) {
                String name = sp.toString().toLowerCase().replace('_', ' ');
                if (InventoryTracker.nameMatches(name, raw)
                        || InventoryTracker.normalizeTokens(name).replace(" ", "").equals(n)) {
                    int id = (int) ms.getMethod("getSpellId").invoke(sp);
                    boolean self = name.contains("veng");
                    String pretty = name.substring(0, 1).toUpperCase() + name.substring(1);
                    return new SpellRef(id, pretty, self);
                }
            }
        } catch (Exception ignored) {}

        int[] w = findSpellWidget(raw);
        if (w != null) return new SpellRef(w[0], raw, false);
        return null;
    }

    private boolean selectSpell(SpellRef spell) {
        if (spell == null) return false;
        if (clickSpellOnBook(spell.widgetId, spell.displayName)) {
            return true;
        }
        int[] found = findSpellWidget(spell.displayName);
        if (found != null && clickSpellOnBook(found[0], spell.displayName)) {
            return true;
        }
        return armSpellSelected(spell.widgetId, spell.displayName);
    }

    private boolean armSpellSelected(int widgetId, String displayName) {
        String name = (displayName != null && !displayName.isEmpty()) ? displayName : "Ice Barrage";
        int flags = readWidgetSpellFlags(widgetId);
        if (flags <= 0) flags = SPELL_USABLE_WORLD;
        else flags |= SPELL_USABLE_WORLD;
        String tooltip = "Cast " + name + " -> ";
        try {
            Object iface = readInterface(widgetId);
            if (iface != null) {
                String selected = readIfaceString(iface, "selectedActionName");
                String spellNm = readIfaceString(iface, "spellName");
                if (spellNm == null || spellNm.isEmpty()) spellNm = name;
                if (selected != null && !selected.isEmpty()) {
                    int sp = selected.indexOf(' ');
                    String head = sp > 0 ? selected.substring(0, sp) : selected;
                    String tail = sp > 0 ? selected.substring(sp + 1) : "";
                    tooltip = (head + " " + spellNm + (tail.isEmpty() ? "" : " " + tail)).trim() + " ";
                }
            }
        } catch (Exception ignored) {}

        boolean ok = false;
        ok |= setIntField(clientSpellSelectedField, 1);
        ok |= setIntField(clientSpellIdField, widgetId);
        ok |= setIntField(clientSpellWidgetField, widgetId);
        ok |= setIntField(clientSpellUsableOnField, flags);
        ok |= setIntField(clientItemSelectedField, 0);
        ok |= setStringField(clientSpellNameField, tooltip.trim());
        Field tooltipField = findField(clientInstance.getClass(), "spellTooltip");
        ok |= setStringField(tooltipField, tooltip);
        // Also drive the client's own setter methods — these run its internal
        // state transitions and are far more reliable than raw field writes.
        invokeClientVoid(clientSetSpellSelectedMethod, true);
        invokeClientVoid(clientSetSelectedSpellWidgetMethod, widgetId);
        invokeClientVoid(clientSetSelectedSpellNameMethod, name);
        setMagicSpellForWidget(widgetId, name);
        fillBlankSpellStrings(name);
        leftClickCastArmed = true;
        leftClickCastName = name;
        leftClickCastWidget = widgetId;
        leftClickCastArmedTick = currentTick;
        FontManager.debug("[Swapper] arm widget=" + widgetId + " flags=" + flags + " tip=" + tooltip);
        return ok || flags > 0;
    }

    /** Invoke a Client method with a single argument (void return). */
    private void invokeClientVoid(Method m, Object arg) {
        if (m == null) return;
        try {
            if (Modifier.isStatic(m.getModifiers())) m.invoke(null, arg);
            else m.invoke(clientInstance, arg);
        } catch (Exception ignored) {}
    }

    private void reassertLeftClickArm() {
        if (leftClickCastWidget <= 0) return;
        setIntField(clientSpellSelectedField, 1);
        setIntField(clientSpellIdField, leftClickCastWidget);
        setIntField(clientSpellWidgetField, leftClickCastWidget);
        int flags = readWidgetSpellFlags(leftClickCastWidget);
        if (flags <= 0) flags = SPELL_USABLE_WORLD;
        else flags |= SPELL_USABLE_WORLD;
        setIntField(clientSpellUsableOnField, flags);
        setIntField(clientItemSelectedField, 0);
    }

    private void clearLeftClickArm() {
        leftClickCastArmed = false;
        leftClickCastName = null;
        leftClickCastWidget = -1;
        setIntField(clientSpellSelectedField, 0);
        setIntField(clientSpellUsableOnField, 0);
    }

    /** Called when a gear swap starts so wield clicks are not treated as spell casts. */
    public void clearLeftClickArmPublic() {
        clearLeftClickArm();
    }

    /** Stop a walk that raced the cast click. */
    private void cancelPendingWalk() {
        try {
            if (destXField != null) destXField.setInt(clientInstance, 0);
            if (destYField != null) destYField.setInt(clientInstance, 0);
        } catch (Exception ignored) {}
    }

    private boolean setStringField(Field f, String value) {
        if (f == null || value == null) return false;
        try {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.set(null, value);
            } else {
                f.set(clientInstance, value);
            }
            return true;
        } catch (Exception ignored) {}
        try { f.set(clientInstance, value); return true; } catch (Exception ignored) {}
        try { f.set(null, value); return true; } catch (Exception ignored) {}
        return false;
    }

    private boolean setIntField(Field f, int value) {
        if (f == null) return false;
        try {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setInt(null, value);
            } else {
                f.setInt(clientInstance, value);
            }
            return true;
        } catch (Exception ignored) {}
        try { f.setInt(clientInstance, value); return true; } catch (Exception ignored) {}
        try { f.setInt(null, value); return true; } catch (Exception ignored) {}
        return false;
    }

    private int readIntField(Field f) {
        if (f == null) return 0;
        try {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) return f.getInt(null);
            return f.getInt(clientInstance);
        } catch (Exception ignored) {}
        return 0;
    }

    private Object readInterface(int widgetId) {
        if (interfaceCacheField == null || widgetId <= 0) return null;
        try {
            Object cacheObj = java.lang.reflect.Modifier.isStatic(interfaceCacheField.getModifiers())
                    ? interfaceCacheField.get(null) : interfaceCacheField.get(clientInstance);
            if (!(cacheObj instanceof Object[])) return null;
            Object[] cache = (Object[]) cacheObj;
            if (widgetId >= cache.length) return null;
            return cache[widgetId];
        } catch (Exception ignored) {}
        return null;
    }

    private int readWidgetSpellFlags(int widgetId) {
        Object iface = readInterface(widgetId);
        if (iface == null) return 0;
        int flags = readIfaceInt(iface, "spellUsableOn");
        return Math.max(0, flags);
    }

    private void setMagicSpellForWidget(int widgetId, String displayName) {
        if (clientMagicSpellField == null) return;
        try {
            Class<?> ms = RtLookup.magicSpell();
            if (ms == null) return;
            Object spell = null;
            try {
                Object map = ms.getField("magicSpellInterfaceIds").get(null);
                if (map != null) {
                    try {
                        spell = map.getClass().getMethod("get", int.class).invoke(map, widgetId);
                    } catch (NoSuchMethodException e) {
                        spell = map.getClass().getMethod("get", Integer.class).invoke(map, widgetId);
                    }
                }
            } catch (Exception ignored) {}
            if (spell == null && displayName != null && displayName.toLowerCase().contains("ice barrage")) {
                spell = ms.getField("ICE_BARRAGE").get(null);
            }
            if (spell == null) return;
            if (java.lang.reflect.Modifier.isStatic(clientMagicSpellField.getModifiers())) {
                clientMagicSpellField.set(null, spell);
            } else {
                clientMagicSpellField.set(clientInstance, spell);
            }
        } catch (Exception ignored) {}
    }

    /** Game-chat line (Roat ChatHelper.sendMessage). */
    public void sendGameMessage(String msg) {
        if (msg == null || msg.isEmpty() || clientInstance == null) return;
        try {
            Method m = findMethod(clientInstance.getClass(), "sendMessage", 1);
            if (m != null && m.getParameterTypes()[0] == String.class) {
                m.invoke(clientInstance, msg);
                return;
            }
        } catch (Exception ignored) {}
        try {
            Class<?> ch = Class.forName("com.roatpkz.client.game.ChatHelper");
            ch.getMethod("sendMessage", String.class).invoke(null, msg);
        } catch (Exception ignored) {}
    }

    /** Public chat. */
    public boolean sendPlayerChat(String text) {
        if (text == null || text.isEmpty() || clientInstance == null) return false;
        try {
            Method m = clientInstance.getClass().getMethod("sendPlayerChat", String.class, boolean.class);
            m.invoke(clientInstance, text, false);
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Client command packet (::heal, etc). */
    public boolean sendCommandString(String text) {
        if (text == null || text.isEmpty()) return false;
        String cmd = text.trim();
        if (cmd.startsWith("::")) cmd = cmd.substring(2);
        Object helper = livePacketHelper();
        if (helper == null) return false;
        try {
            Method m = RtLookup.method(helper.getClass(), "sendCommandString", 1);
            if (m == null) return false;
            m.invoke(helper, cmd);
            return true;
        } catch (Exception e) {
            FontManager.debug("[Swapper] cmd failed: " + e.getMessage());
            return false;
        }
    }

    Object livePacketHelper() {
        try {
            Method m = findMethod(clientInstance.getClass(), "getPacketHelper", 0);
            if (m != null) {
                Object h = m.invoke(clientInstance);
                if (h != null) return h;
            }
        } catch (Exception ignored) {}
        return packetHelper;
    }

    private void installLeftClickCastHook() {
        try {
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
                if (!leftClickCastArmed) return;
                if (!(ev instanceof java.awt.event.MouseEvent)) return;
                java.awt.event.MouseEvent e = (java.awt.event.MouseEvent) ev;
                // After the client processes the click — cancel Walk if we cast instead.
                if (e.getID() != java.awt.event.MouseEvent.MOUSE_RELEASED) return;
                if (e.getButton() != java.awt.event.MouseEvent.BUTTON1) return;
                Object src = e.getSource();
                if (src != null) {
                    String cn = src.getClass().getName();
                    if (cn.startsWith("javax.swing") || cn.startsWith("com.sun.java.fontmgr")) return;
                }
                ClientThreadGuard.get().invokeLater(this::fireArmedSpellAtClick);
            }, java.awt.AWTEvent.MOUSE_EVENT_MASK);
        } catch (Exception ignored) {}
    }

    /**
     * Only fire when we already have a real target (dummy / last attack).
     * Never pick a random NPC — that + Walk was sending the character everywhere.
     *
     * <p>Wearing a staff (no autocast) the spell must reach the target via the
     * native click-cast under the cursor, not a cached auto-target. In that case
     * we only cancel a racing walk and keep the spell armed, so the client's own
     * "Cast Ice Barrage ->" left-click resolves the entity the player actually
     * clicked.
     */
    private void fireArmedSpellAtClick() {
        if (!leftClickCastArmed) return;
        // Manual cast: the player clicks the target. The spell is already armed
        // ("Cast Ice Barrage ->"), so the native client casts on whatever entity
        // is under the cursor. Do NOT auto-route to a cached target — that was
        // casting on the wrong entity / firing on dummies when the player was
        // trying to move.
        cancelPendingWalk();
        lastAction = "LC_NATIVE@" + currentTick;
    }

    /** True when the wielded weapon is a staff (no autocast — click-cast only). */
    private boolean isStaffEquipped() {
        int wid = readEquippedWeaponId();
        if (wid <= 0) return false;
        String name = resolveItemName(wid);
        return InventoryTracker.isNonAutocastStaff(wid, name)
                || InventoryTracker.isAutocastStaff(wid, name);
    }

    private int findNpcIndexByName(String needle) {
        if (npcsField == null || needle == null) return -1;
        String n = needle.toLowerCase();
        try {
            Object[] arr = (Object[]) (Modifier.isStatic(npcsField.getModifiers())
                    ? npcsField.get(null) : npcsField.get(clientInstance));
            if (arr == null) return -1;
            for (int i = 0; i < arr.length; i++) {
                Object npc = arr[i];
                if (npc == null) continue;
                String name = npcName(npc);
                if (name != null && name.toLowerCase().contains(n)) {
                    try {
                        Method gi = findMethod(npc.getClass(), "getIndex", 0);
                        if (gi != null) return (int) gi.invoke(npc);
                    } catch (Exception ignored) {}
                    return i;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private static String npcName(Object npc) {
        try {
            Method gn = npc.getClass().getMethod("getName");
            Object n = gn.invoke(npc);
            return n != null ? n.toString() : null;
        } catch (Exception ignored) {}
        return null;
    }

    private void fillBlankSpellStrings(String name) {
        Class<?> cls = clientInstance != null ? clientInstance.getClass() : null;
        if (cls == null) return;
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (!n.contains("spell")) continue;
                try {
                    f.setAccessible(true);
                    Object cur = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            ? f.get(null) : f.get(clientInstance);
                    if (cur == null || "null".equals(String.valueOf(cur)) || ((String) cur).isEmpty()) {
                        Object target = java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                ? null : clientInstance;
                        if (n.contains("use") || n.contains("on")) {
                            f.set(target, "@gre@Cast on");
                        } else {
                            f.set(target, name);
                        }
                    }
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private static Field findSpellNameField(Class<?> cls) {
        String[] names = {
                "selectedSpellName", "spellName", "selectedSpell", "spellTooltip",
                "aString1139", "aString1140", "aString1141", "aString1142"
        };
        for (String n : names) {
            Field f = findField(cls, n);
            if (f != null && f.getType() == String.class) return f;
        }
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (n.contains("spell") && (n.contains("name") || n.contains("tooltip") || n.contains("selected"))) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private boolean castSpellOnCurrentTarget(String spellLabel) {
        if (cachedAttackId < 0) refreshAttackTarget();
        if (cachedAttackId < 0) return false;
        if (cachedAttackIsPlayer) {
            if (invokeClientAction(0, 0, SPELL_ON_PLAYER_OPCODE, cachedAttackId, "Cast", spellLabel))
                return true;
        } else {
            if (invokeClientAction(0, 0, SPELL_ON_NPC_OPCODE, cachedAttackId, "Cast", spellLabel))
                return true;
        }
        // Reuse ice cast fallbacks with the requested label
        return castIceOnTarget(spellLabel);
    }

    private void finishPendingSwapCast() {
        if (pendingSwapCastWidget > 0) {
            armSpellSelected(pendingSwapCastWidget,
                    pendingSwapCastLabel != null ? pendingSwapCastLabel : "Ice Barrage");
        }
        String label = pendingSwapCastLabel != null ? pendingSwapCastLabel : "Cast";
        refreshAttackTarget();
        if (castSpellOnCurrentTarget(label)) {
            lastAction = "SWAP_CAST@" + currentTick;
        } else {
            lastAction = "SWAP_CAST_FAIL@" + currentTick;
        }
        pendingSwapCastLabel = null;
        pendingSwapCastWidget = -1;
    }

    /** Scan inventory for an item whose definition name fuzzy-matches the query. */
    public int findInventorySlotByName(String nameFragment) {
        if (nameFragment == null || nameFragment.isEmpty()) return -1;
        int[] ids = getInventorySnapshot();
        int bestSlot = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int slot = 0; slot < ids.length; slot++) {
            int raw = ids[slot];
            if (raw <= 0) continue;
            String name = resolveItemName(raw - 1);
            if (name == null || !InventoryTracker.nameMatches(name, nameFragment)) continue;
            // Prefer shorter names (more specific) when multiple items match.
            int score = InventoryTracker.normalizeTokens(name).length();
            if (score < bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private String resolveItemName(int itemId) {
        if (itemId <= 0) return null;
        String cached = ITEM_NAME_CACHE.get(itemId);
        if (cached != null) return cached;
        try {
            initItemDefReflection();
            if (itemDefGetMethod == null || itemDefNameField == null) return null;
            Object def = itemDefGetMethod.invoke(null, itemId);
            if (def == null) return null;
            Object name = itemDefNameField.get(def);
            String s = name != null ? name.toString() : "";
            ITEM_NAME_CACHE.put(itemId, s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private String[] readItemInventoryOptions(int itemId) {
        if (itemId <= 0) return null;
        try {
            initItemDefReflection();
            if (itemDefGetMethod == null || itemDefInventoryOptionsField == null) return null;
            Object def = itemDefGetMethod.invoke(null, itemId);
            if (def == null) return null;
            Object opts = itemDefInventoryOptionsField.get(def);
            return opts instanceof String[] ? (String[]) opts : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHpReducerMenuOption(String option) {
        if (option == null || option.isEmpty()) return false;
        String low = option.toLowerCase();
        if (low.contains("drop") || low.contains("destroy") || low.contains("wear")
                || low.contains("wield") || low.contains("equip") || low.contains("open")) {
            return false;
        }
        // Never treat generic "Eat" as an orb — that is food and drains the inv.
        return low.contains("feel") || low.contains("rub") || low.contains("guzzle")
                || low.contains("operate") || low.contains("drain") || low.contains("diminish");
    }

    public boolean slotLooksLikeHpReducer(int slot, int itemId, String name) {
        if (InventoryTracker.isFood(itemId, name) || InventoryTracker.isBrew(itemId, name)
                || InventoryTracker.isKaram(itemId, name) || InventoryTracker.isHalibut(itemId, name)
                || InventoryTracker.isRestore(itemId, name)) {
            return false;
        }
        if (InventoryTracker.isHpReducer(itemId, name)) return true;
        String[] opts = readItemInventoryOptions(itemId);
        if (opts == null) return false;
        for (String opt : opts) {
            if (isHpReducerMenuOption(opt)) return true;
        }
        return false;
    }

    public boolean inventorySlotIsHpReducer(int slot) {
        int raw = getInventoryItemId(slot);
        if (raw <= 0) return false;
        int id = raw - 1;
        String name = resolveItemName(id);
        return slotLooksLikeHpReducer(slot, id, name);
    }

    public int findHpReducerSlotPublic() {
        int[] inv = getInventorySnapshot();
        int orbSlot = -1;
        int cakeSlot = -1;
        int menuSlot = -1;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (!slotLooksLikeHpReducer(slot, id, name)) continue;
            if (InventoryTracker.isLocatorOrb(id, name) || InventoryTracker.isLocatorLikeName(name)) {
                orbSlot = slot;
            } else if (InventoryTracker.isRockCake(id, name)) {
                cakeSlot = slot;
            } else if (menuSlot < 0) {
                menuSlot = slot;
            }
        }
        if (orbSlot >= 0) return orbSlot;
        if (cakeSlot >= 0) return cakeSlot;
        return menuSlot;
    }

    private static void initItemDefReflection() {
        if (itemDefClass != null) return;
        Class<?> looked = RtLookup.itemDef();
        String[] candidates = looked != null
                ? new String[] { looked.getName() }
                : new String[] {
                    "com.roatpkz.client.game.cache.def.items.ItemDef",
                    "com.roatpkz.client.game.cache.def.ItemDefinition",
                    "com.roatpkz.client.game.cache.definitions.ItemDefinition",
                    "com.roatpkz.client.cache.def.ItemDefinition",
                };
        for (String cn : candidates) {
            try {
                Class<?> cls = Class.forName(cn);
                Method get = null;
                for (Method m : cls.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != int.class) continue;
                    if (m.getName().equals("forID") || m.getName().equals("get") || m.getName().equals("forId")
                            || m.getReturnType() == cls) {
                        get = m;
                        get.setAccessible(true);
                        break;
                    }
                }
                Field nameF = findField(cls, "name");
                if (nameF == null) nameF = findField(cls, "itemName");
                Field optsF = findField(cls, "inventoryOptions");
                if (get != null && nameF != null) {
                    itemDefClass = cls;
                    itemDefGetMethod = get;
                    itemDefNameField = nameF;
                    itemDefInventoryOptionsField = optsF;
                    FontManager.debug("[CombatScript] ItemDef=" + cn + " via " + get.getName());
                    return;
                }
            } catch (ClassNotFoundException ignored) {}
        }
    }

    private static Field findField(Class<?> cls, String name) {
        return Reflect.declaredField(cls, name);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Actions — Shared helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Re-attacks the current interacting entity (player opcode 27, NPC opcode 478). */
    private boolean reAttackTarget() {
        if (doActionMethod == null) return false;
        int idx = -1;
        boolean isPlayer = true;
        Object myPlayer = null;
        try { myPlayer = myPlayerField != null ? myPlayerField.get(null) : null; } catch (Exception ignored) {}
        try {
            if (getInteractingEntityMethod != null && myPlayer != null) {
                Object raw = getInteractingEntityMethod.invoke(myPlayer);
                if (raw instanceof Integer) idx = (Integer) raw;
            }
            if (idx < 0 && myPlayer != null) {
                Field interField = findField(myPlayer.getClass(), "interactingEntity");
                if (interField != null) idx = interField.getInt(myPlayer);
            }
        } catch (Exception ignored) {}

        if (idx == 65535) idx = -1;
        if (idx >= 32768) {
            isPlayer = true;
            idx = idx - 32768;
        } else if (idx >= 0) {
            isPlayer = false;
        } else if (cachedAttackId >= 0) {
            idx = cachedAttackId;
            isPlayer = cachedAttackIsPlayer;
        } else {
            return false;
        }

        try {
            if (isPlayer) {
                doActionMethod.invoke(clientInstance, 0, 0, 0, 27, idx, 0, "Attack", "", -1, -1);
            } else {
                doActionMethod.invoke(clientInstance, 0, 0, 0, 478, idx, 0, "Attack", "", -1, -1);
            }
            cachedAttackId = idx;
            cachedAttackIsPlayer = isPlayer;
            return true;
        } catch (Exception e) {
            FontManager.log("[CombatScript] reAttackTarget error: " + e.getMessage());
            return false;
        }
    }

    private void cacheAttackTarget(Object myPlayer, Object target) {
        try {
            int idx = -1;
            if (getInteractingEntityMethod != null && myPlayer != null) {
                Object raw = getInteractingEntityMethod.invoke(myPlayer);
                if (raw instanceof Integer) idx = (Integer) raw;
            }
            if (idx >= 32768) {
                cachedAttackId = idx - 32768;
                cachedAttackIsPlayer = true;
                return;
            }
            if (idx >= 0 && idx != 65535) {
                cachedAttackId = idx;
                cachedAttackIsPlayer = false;
                return;
            }
            if (target == null) return;
            String cn = target.getClass().getSimpleName().toLowerCase();
            if (cn.contains("player")) {
                int arrIdx = resolvePlayerArrayIndex(target);
                if (arrIdx >= 0) {
                    cachedAttackId = arrIdx;
                    cachedAttackIsPlayer = true;
                    return;
                }
                Method pid = findMethod(target.getClass(), "getPlayerId", 0);
                if (pid == null) pid = findMethod(target.getClass(), "getId", 0);
                if (pid != null) {
                    cachedAttackId = (int) pid.invoke(target);
                    cachedAttackIsPlayer = true;
                }
            } else {
                Method nid = findMethod(target.getClass(), "getIndex", 0);
                if (nid == null) nid = findMethod(target.getClass(), "getId", 0);
                if (nid != null) {
                    cachedAttackId = (int) nid.invoke(target);
                    cachedAttackIsPlayer = false;
                }
            }
        } catch (Exception ignored) {}
    }

    private int resolvePlayerArrayIndex(Object player) {
        if (player == null || playerArrayField == null) return -1;
        try {
            Object[] arr = (Object[]) playerArrayField.get(clientInstance);
            if (arr == null) return -1;
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == player) return i;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private void ensureMagicTab() {
        invokeSetTab(6);
    }

    private void ensurePrayerTab() {
        invokeSetTab(5);
    }

    void invokeSetTab(int tab) {
        if (setTabMethod == null) return;
        try {
            if (java.lang.reflect.Modifier.isStatic(setTabMethod.getModifiers())) {
                setTabMethod.invoke(null, tab);
            } else {
                setTabMethod.invoke(clientInstance, tab);
            }
        } catch (Exception ignored) {
            try { setTabMethod.invoke(clientInstance, tab); } catch (Exception ignored2) {}
            try { setTabMethod.invoke(null, tab); } catch (Exception ignored2) {}
        }
    }

    /** Casts Vengeance via lunar spellbook widget (fallback widget if primary fails). */
    private void castVengeance() {
        if (doActionMethod == null) return;
        ensureMagicTab();
        int[][] tries = {
            { 30306, 315 },
            { 30306, 626 },
            { 30306, 0 },
            { 30064, 315 },
            { 30290, 315 },
            { 30298, 315 },
        };
        for (int[] t : tries) {
            try {
                doActionMethod.invoke(clientInstance, 0, 0, t[0], t[1], -1, 0, "Cast", "Vengeance", -1, -1);
                markVengeanceCast(currentTick);
                lastAction = "VENG@" + currentTick;
                return;
            } catch (Exception ignored) {}
        }
        if (invokeClientAction(0, 30306, SPELL_SELECT_OPCODE, 30306, "Cast", "Vengeance")) {
            markVengeanceCast(currentTick);
            lastAction = "VENG@" + currentTick;
            return;
        }
        FontManager.log("[CombatScript] Vengeance failed all widgets");
    }

    private void markVengeanceCast(int tick) {
        lastVengCastTick = tick;
        lastVengSuccessTick = tick;
        lastHeadlessVengTick = tick;
        FontManager.debug("[CombatScript] Vengeance cast");
    }

    private boolean hasCombatTarget() {
        return targetName != null && !targetName.isEmpty();
    }

    /**
     * Facing never resolves on a combat dummy and flickers in PvP, so a name is
     * not proof of combat. Landing or taking hits is.
     */
    private boolean hasCombatContext() {
        return tookHitRecently(COMBAT_IDLE_TICKS) || dealtDamageRecently();
    }

    private boolean dealtDamageRecently() {
        return hitsplatChangeTick >= 0 && currentTick - hitsplatChangeTick <= COMBAT_IDLE_TICKS;
    }

    private boolean tookHitRecently(int ticks) {
        return incomingChangeTick >= 0 && currentTick - incomingChangeTick <= ticks;
    }

    /**
     * Real PvP fight — hits in either direction, or a live attack anim on a current interactor.
     * Clicking / following / a leftover name does not count.
     */
    public boolean isInActivePvpFight() {
        if (tookHitRecently(DH_FIGHT_IDLE_TICKS)) return true;
        if (lastLocalHpDropTick >= 0 && currentTick - lastLocalHpDropTick <= DH_FIGHT_IDLE_TICKS) {
            return true;
        }
        if (hitsplatChangeTick >= 0 && currentTick - hitsplatChangeTick <= DH_FIGHT_IDLE_TICKS) {
            return true;
        }
        if (lastCombatActivityTick >= 0
                && currentTick - lastCombatActivityTick <= DH_FIGHT_IDLE_TICKS) {
            return true;
        }
        Object recent = recentTarget();
        if (recent != null) {
            if (AnimationDb.isCombatAttackAnimation(localAnim)
                    || AnimationDb.isDharokAnimation(localAnim)
                    || AnimationDb.isSpecAnimation(localAnim)) {
                return true;
            }
            if (AnimationDb.isCombatAttackAnimation(lastTargetAnim)
                    || AnimationDb.isDharokAnimation(lastTargetAnim)
                    || AnimationDb.isSpecAnimation(lastTargetAnim)) {
                return true;
            }
            if (AnimationDb.isConsumeAnimation(lastTargetAnim)) return true;
        }
        if (recent != null && lastCombatActivityTick >= 0
                && currentTick - lastCombatActivityTick <= DH_FIGHT_IDLE_TICKS) {
            return true;
        }
        if (!liveInteractThisTick) return false;
        if (AnimationDb.isCombatAttackAnimation(localAnim)) return true;
        if (AnimationDb.isDharokAnimation(localAnim) || AnimationDb.isSpecAnimation(localAnim)) return true;
        if (AnimationDb.isCombatAttackAnimation(lastTargetAnim)) return true;
        if (AnimationDb.isDharokAnimation(lastTargetAnim) || AnimationDb.isSpecAnimation(lastTargetAnim)) {
            return true;
        }
        return false;
    }

    /** True when we have a target worth running DH logic against. */
    public boolean hasDhCombatTarget() {
        return cachedTarget != null || (targetName != null && !targetName.isEmpty());
    }

    private void noteOpponentAttack(int tick, int anim) {
        if (anim <= 0) {
            lastSeenOppAttackAnim = -1;
            return;
        }
        if (AnimationDb.isConsumeAnimation(anim)) {
            lastSeenOppAttackAnim = -1;
            return;
        }
        boolean attack = AnimationDb.isCombatAttackAnimation(anim)
                || AnimationDb.isDharokAnimation(anim)
                || AnimationDb.isSpecAnimation(anim);
        if (!attack) {
            lastSeenOppAttackAnim = -1;
            return;
        }
        // Rising edge only — holding the same attack pose is not a new swing.
        if (anim != lastSeenOppAttackAnim) {
            lastOppAttackTick = tick;
            lastSeenOppAttackAnim = anim;
        }
    }

    // Post-mortem: rolling window of combat state, dumped to the log on death.
    private static final int TRACE_LEN = 24;
    private final String[] tickTrace = new String[TRACE_LEN];
    private int tickTraceCount = 0;

    private void dumpDeathTrace(int tick) {
        int n = Math.min(tickTraceCount, TRACE_LEN);
        if (n == 0) return;
        FontManager.log("[DEATH] hp hit 0 at tick " + tick + " — last " + n + " ticks:");
        for (int i = 0; i < n; i++) {
            String line = tickTrace[(tickTraceCount - n + i) % TRACE_LEN];
            if (line != null) FontManager.log("[DEATH] " + line);
        }
        tickTraceCount = 0;
    }

    private void noteLocalHpDrop(int tick) {
        int hp = readLocalHp();
        if (hp <= 0) {
            if (lastSeenLocalHp > 0) dumpDeathTrace(tick);
            lastSeenLocalHp = hp;
            return;
        }
        if (dharokEnabled || enabled || nhEnabled) {
            tickTrace[tickTraceCount++ % TRACE_LEN] =
                    "t" + tick + " hp=" + hp + " opp=" + liveTargetHp()
                    + " spec=" + specEnergy
                    + (pendingDhWhipDef ? " whipdef" : "")
                    + (pendingDhGmaulFollow ? " gmaul" : "")
                    + " act=" + lastAction;
        }
        boolean ourOrbOrEat = pendingDhStack || dharokStackArmed
                || tick == lastDhAxeTick || tick == lastDhForceEatTick
                || tick == lastDhOrbTick || tick == lastDhOrbTick + 1;
        if (!ourOrbOrEat && lastSeenLocalHp > 0 && hp < lastSeenLocalHp) {
            // No hitsplat + an orb in the bag means the player is stacking by hand.
            if (dharokEnabled && !isFreshIncomingHit() && findHpReducerSlotPublic() >= 0) {
                int drop = lastSeenLocalHp - hp;
                // Locator orb is ~10% max HP. Tiny drops are hitsplats we missed, not orbs.
                if (drop >= 6) lastSelfOrbTick = tick;
            } else {
                lastLocalHpDropTick = tick;
                lastOppAttackTick = tick;
            }
        }
        lastSeenLocalHp = hp;
    }

    /** Player orbed themselves within the last few ticks — let them swing the axe. */
    public boolean inSelfOrbWindow(int tick) {
        return lastSelfOrbTick >= 0 && tick - lastSelfOrbTick <= SELF_ORB_GRACE_TICKS;
    }

    private static boolean inPostSwingWindow(int eventTick, int now) {
        if (eventTick < 0) return false;
        int since = now - eventTick;
        return since >= 1 && since <= 3;
    }

    /**
     * True when the opponent is locked and cannot land a hit this tick:
     * they just swung (hitsplat / HP drop / attack start), or they are eating.
     */
    public boolean isSafeDhBurstWindow() {
        if (isFreshIncomingHit()) return false;

        if (animationMonitor != null && animationMonitor.isFreshConsume(currentTick)) return true;
        if (AnimationDb.isConsumeAnimation(lastTargetAnim)
                && animationMonitor != null
                && currentTick - animationMonitor.getConsumeStartTick() <= 2) {
            return true;
        }
        if (AnimationDb.isConsumeAnimation(lastTargetAnim)) return true;
        if (inPostSwingWindow(incomingChangeTick, currentTick)) return true;
        if (inPostSwingWindow(lastLocalHpDropTick, currentTick)) return true;
        if (inPostSwingWindow(lastOppAttackTick, currentTick)) return true;
        return false;
    }

    /** Sticky opponent HP for KO math — stale reads do not count as KO range. */
    public int liveTargetHp() {
        if (targetHp > 0) {
            lastKnownTargetHp = targetHp;
            lastKnownTargetHpTick = currentTick;
            return targetHp;
        }
        if (lastKnownTargetHp > 0 && lastKnownTargetHpTick >= 0
                && currentTick - lastKnownTargetHpTick <= STICKY_TARGET_TICKS) {
            return lastKnownTargetHp;
        }
        return -1;
    }

    /** True when we have a recent, trustworthy opponent HP for KO math. */
    public boolean hasReliableTargetHp() {
        return liveTargetHp() > 0;
    }

    public int stackedDhMaxHit() {
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        int stackHp = Math.max(1, (maxHp * Math.max(1, dharokTargetHpPct)) / 100);
        return MaxHitCalculator.dharokMaxHit(readMeleeStr(), stackHp, maxHp);
    }

    public int stackedDhPlusGmaul() {
        return stackedDhMaxHit() + MaxHitCalculator.gmaulSpecMaxHit(readMeleeStr());
    }

    private int readMeleeStr() {
        int str = stateReader != null ? stateReader.getStrength() : 99;
        return str >= 80 ? str : 99;
    }

    /** Axe KO, or axe+gmaul if we have 50% spec. +8 covers formula / gear undercount. */
    public boolean wouldDhKoIfStacked() {
        if (findGreataxe() == null) {
            inKillRange = false;
            return false;
        }
        int opp = liveTargetHp();
        if (opp <= 0) {
            inKillRange = false;
            estimatedOurMaxHit = stackedDhMaxHit();
            return false;
        }
        int dhHit = stackedDhMaxHit();
        int combo = stackedDhPlusGmaul();
        estimatedOurMaxHit = specEnergy >= 50 ? combo : dhHit;
        inKillRange = estimatedOurMaxHit + 8 >= opp;
        return inKillRange;
    }

    public static final int DH_EAT_BAND_MIN = 75;
    public static final int DH_EAT_BAND_MAX = 85;

    public void eatOffDhStack() {
        performDhBandEat("DH_ABORT_EAT", false);
    }

    /** Triple only when actually stacked — not every manual abort. One eat per tick. */
    public void eatOffDhStackForced() {
        if (currentTick == lastDhForceEatTick) return;
        int hp = readLocalHp();
        if (hp >= DH_EAT_BAND_MIN && hp <= DH_EAT_BAND_MAX) return;
        if (hp >= DH_EAT_BAND_MAX + 1 && currentTick != lastDhAxeTick) return;
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        int stackHp = Math.max(1, (maxHp * Math.max(1, dharokTargetHpPct)) / 100);
        boolean stacked = hp <= stackHp + 4 || pendingDhStack || currentTick == lastDhAxeTick;
        if (stacked && hp <= 25) {
            eatDhToBand("DH_FORCE_TRIPLE", Math.max(1, hp), false, true);
        } else {
            eatDhToBand("DH_FORCE_EAT", effectiveDhEatHp(hp), false, true);
        }
    }

    /** HP for eat math — after orb/axe the client field still reads 99 for a tick. */
    private int effectiveDhEatHp(int hp) {
        if (hp <= 0) return 1;
        if (pendingDhStack || dharokStackArmed || currentTick == lastDhAxeTick) {
            int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
            int stackHp = Math.max(1, (maxHp * Math.max(1, dharokTargetHpPct)) / 100);
            if (hp > stackHp + 15) return stackHp;
        }
        return hp;
    }

    public void armDhMustEat() {
        pendingDhMustEat = true;
    }

    /** True only on a real KO spec anim — not greataxe / whip swings. */
    public boolean isOpponentKoAnim() {
        if (lastTargetAnim <= 0) return false;
        if (AnimationDb.isDharokAnimation(lastTargetAnim) && !AnimationDb.isSpecAnimation(lastTargetAnim)) {
            return false;
        }
        return AnimationDb.isSpecAnimation(lastTargetAnim);
    }

    /**
     * Rising edge of their spec. Returns true only when food actually went out
     * (caller should skip orb). A held anim or an already-safe HP does not block KO.
     */
    public boolean tryEatOffOpponentSpec(int tick) {
        if (!isOpponentKoAnim()) {
            lastDhSpecEatAnim = -1;
            return false;
        }
        if (lastTargetAnim == lastDhSpecEatAnim) return false;
        lastDhSpecEatAnim = lastTargetAnim;
        lastDhSpecEatTick = tick;
        if (tick == lastDhAxeTick) return false;
        int hp = readLocalHp();
        int threat = MaxHitCalculator.opponentSpecThreat(lastTargetAnim);
        if (threat <= 0) threat = 60;
        if (hp <= 0 || hp >= 92 || hp >= threat + 8) return false;
        // Already safe vs this spec — do not marlin past the low band.
        if (hp >= threat && hp >= DH_EAT_BAND_MIN) return false;
        pendingDhSpecEat = true;
        eatToSurviveSpec(tick == lastSanfewTick);
        pendingDhSpecEat = false;
        return lastDhForceEatTick == tick;
    }

    /**
     * Smallest eat that lives the spec. AGS from 75 = one marlin. Gmaul from 75 = skip.
     * Double gmaul within 2 ticks is treated as 80. Triple only when HP is stacked
     * or the spec would still kill after a double.
     */
    public void eatToSurviveSpec(boolean foodOnly) {
        int hp = readLocalHp();
        if (hp <= 0) return;
        int threat = MaxHitCalculator.opponentSpecThreat(lastTargetAnim);
        if (AnimationDb.isGmaulSpec(lastTargetAnim)) {
            if (lastGmaulSpecTick >= 0 && currentTick - lastGmaulSpecTick <= 2) threat = 80;
            lastGmaulSpecTick = currentTick;
        }
        if (threat <= 0) threat = 60;
        int target = Math.min(DH_EAT_BAND_MAX, threat + 8);
        if (hp >= 92 && threat < 80) return;
        if (hp >= target) return;
        if (hp >= threat && hp >= DH_EAT_BAND_MIN) return;

        int need = target - hp;
        if (need <= 0) return;

        int[] inv = getInventorySnapshot();
        int marlin = findMarlinSlot(inv, -1);
        if (marlin < 0) marlin = findPrimaryFood(inv, -1);
        int brew = foodOnly ? -1 : findSlotOfKind(inv, 1);
        int hali = findHalibutSlot(inv, marlin);
        if (marlin < 0 && hali < 0 && brew < 0) {
            marlin = findAnyEdibleSlot(inv, -1);
        }
        if (marlin < 0 && hali < 0 && brew < 0) {
            lastAction = "DH_SPEC_EAT_NOFOOD@" + currentTick;
            return;
        }

        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        int tier;
        if (hp <= 12) {
            tier = brew >= 0 && hali >= 0 && marlin >= 0 ? 3 : (hali >= 0 && marlin >= 0 ? 2 : 1);
        } else {
            tier = chooseSpecSurviveTier(need, maxHp, marlin >= 0, brew >= 0, hali >= 0);
        }
        if (tier <= 0) return;
        // One marlin would overshoot way past 85 while already above threat — skip.
        if (tier == 1 && hp >= threat && hp + MaxHitCalculator.MARLIN_HEAL > DH_EAT_BAND_MAX + 5) {
            return;
        }

        lastEatMs = System.currentTimeMillis();
        lastComboTick = currentTick;
        lastHeadlessComboTick = currentTick;
        lastDhForceEatTick = currentTick;
        lastDhEatTier = tier;
        performDhEatTier(tier, inv, marlin, brew, hali);
        lastAction = "DH_SPEC_EAT" + tier + "@" + currentTick
                + " anim=" + lastTargetAnim + " threat=" + threat;
    }

    /** Smallest tier whose heal covers {@code need}. */
    private int chooseSpecSurviveTier(int need, int maxHp,
            boolean hasMarlin, boolean hasBrew, boolean hasHali) {
        if (need <= 0) return 0;
        int brewH = MaxHitCalculator.brewHeal(maxHp);
        int h1 = MaxHitCalculator.MARLIN_HEAL;
        int h2 = h1 + MaxHitCalculator.HALIBUT_HEAL;
        int h3 = h2 + brewH;
        if (need <= h1 && (hasMarlin || hasHali)) return 1;
        if (need <= h2 && hasMarlin && hasHali) return 2;
        if (need <= h3 && hasMarlin && hasBrew && hasHali) return 3;
        if (hasMarlin && hasHali) return 2;
        return (hasMarlin || hasHali) ? 1 : 0;
    }

    /** Tick after a triple (brew): sanfew only. Never same tick as the brew. */
    public boolean pumpDhSanfew(int tick) {
        if (!pendingSanfew) return false;
        if (tick <= lastDhBrewTick) return false;
        if (tick == lastSanfewTick) return false;
        pendingSanfew = false;
        drinkSanfew();
        lastAction = "DH_SANFEW@" + tick;
        FontManager.log("[DH] sanfew after triple");
        return true;
    }

    private void armSanfewNextTick() {
        pendingSanfew = true;
        lastDhBrewTick = currentTick;
    }

    /**
     * One retry only: the tick after an axe, and only if we are still actually stacked.
     * Does not eat on hits, does not eat toward 99.
     */
    public void pumpDhEat(int tick) {
        if (!pendingDhMustEat) return;
        int hp = readLocalHp();
        if (hp <= 0 || hp >= DH_EAT_BAND_MIN) {
            pendingDhMustEat = false;
            return;
        }
        if (tick == lastDhAxeTick || lastDhForceEatTick == tick) return;
        if (lastDhAxeTick < 0 || tick > lastDhAxeTick + 1) {
            pendingDhMustEat = false;
            return;
        }
        // One band eat after axe — never chain into a second triple.
        eatDhToBand("DH_POST_AXE", effectiveDhEatHp(hp), false);
        pendingDhMustEat = false;
    }

    public void performDhSmallEat(String tag) {
        eatDhToBand(tag, 1, false);
    }

    /**
     * Single / double / triple to land in 75–85. Never eat toward 99.
     * 1 = marlin · 2 = marlin+halibut · 3 = marlin+brew+halibut.
     */
    public void performDhBandEat(String tag) {
        performDhBandEat(tag, false);
    }

    public void performDhBandEat(String tag, boolean forceTriple) {
        int hp = effectiveDhEatHp(readLocalHp());
        if (forceTriple && (pendingDhStack || currentTick == lastDhAxeTick)) hp = 1;
        eatDhToBand(tag, hp, forceTriple, false);
    }

    /** Single / double / triple into 75–85. Never eat toward 99. */
    private void eatDhToBand(String tag, int hp, boolean forceTriple) {
        eatDhToBand(tag, hp, forceTriple, false);
    }

    private void eatDhToBand(String tag, int hp, boolean forceTriple, boolean manual) {
        long now = System.currentTimeMillis();
        if (!forceTriple && !manual && now - lastEatMs < 300) return;
        if (hp <= 0 && !forceTriple && !manual) return;
        if (hp >= DH_EAT_BAND_MIN && hp <= DH_EAT_BAND_MAX && !forceTriple) return;
        if (hp > DH_EAT_BAND_MAX && !forceTriple && currentTick != lastDhAxeTick && !pendingDhStack) {
            return;
        }
        if (hp >= 90 && !forceTriple && currentTick != lastDhAxeTick && !pendingDhStack) return;
        // Do not brew the same tick we just sanfewed.
        boolean allowBrew = currentTick != lastSanfewTick;

        int[] inv = getInventorySnapshot();
        int marlin = findMarlinSlot(inv, -1);
        if (marlin < 0) marlin = findPrimaryFood(inv, -1);
        int brew = allowBrew ? findSlotOfKind(inv, 1) : -1;
        int hali = findHalibutSlot(inv, marlin);
        if (marlin < 0 && hali < 0 && brew < 0) {
            marlin = findAnyEdibleSlot(inv, -1);
        }
        if (marlin < 0 && hali < 0 && brew < 0) {
            lastAction = "DH_EAT_NOFOOD@" + currentTick;
            FontManager.log("[DH] no food/brew/halibut in inv — cannot eat");
            return;
        }
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        int tier = forceTriple && hp <= 25
                ? (brew >= 0 && hali >= 0 && marlin >= 0 ? 3 : (hali >= 0 && marlin >= 0 ? 2 : 1))
                : chooseDhEatTier(hp, maxHp, marlin >= 0, brew >= 0, hali >= 0);
        if (tier <= 0) return;

        lastEatMs = now;
        lastComboTick = currentTick;
        lastHeadlessComboTick = currentTick;
        lastDhForceEatTick = currentTick;
        lastDhEatTier = tier;
        performDhEatTier(tier, inv, marlin, brew, hali);
        lastAction = (tag != null ? tag : "DH_EAT") + tier + "@" + currentTick + " hp=" + hp;
    }

    /** 1 = marlin · 2 = marlin+halibut · 3 = marlin+brew+halibut. */
    private int chooseDhEatTier(int hp, int maxHp, boolean hasMarlin, boolean hasBrew, boolean hasHali) {
        if (hp >= DH_EAT_BAND_MIN && hp <= DH_EAT_BAND_MAX) return 0;
        if (!hasMarlin && !hasBrew && !hasHali) return 0;
        if (hp <= 12) {
            if (hasMarlin && hasBrew && hasHali) return 3;
            if (hasMarlin && hasHali) return 2;
            return hasMarlin || hasHali ? 1 : 0;
        }

        int brewH = MaxHitCalculator.brewHeal(maxHp);
        int[] heal = {
            0,
            MaxHitCalculator.MARLIN_HEAL,
            MaxHitCalculator.MARLIN_HEAL + MaxHitCalculator.HALIBUT_HEAL,
            MaxHitCalculator.MARLIN_HEAL + brewH + MaxHitCalculator.HALIBUT_HEAL
        };

        int best = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int t = 1; t <= 3; t++) {
            if (t == 1 && !hasMarlin && !hasHali) continue;
            if (t == 2 && !(hasMarlin && hasHali)) continue;
            if (t == 3 && !(hasMarlin && hasBrew && hasHali)) continue;
            int land = hp + heal[t];
            int score;
            if (land >= DH_EAT_BAND_MIN && land <= DH_EAT_BAND_MAX) {
                score = t;
            } else if (land < DH_EAT_BAND_MIN) {
                score = 100 + (DH_EAT_BAND_MIN - land);
            } else if (land <= DH_EAT_BAND_MAX + 8) {
                score = 50 + (land - DH_EAT_BAND_MAX);
            } else {
                score = 500 + (land - DH_EAT_BAND_MAX);
            }
            if (score < bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (hp >= DH_EAT_BAND_MIN && hp <= DH_EAT_BAND_MAX) return 0;
        if (hp > DH_EAT_BAND_MAX + 5) return 0;
        return best;
    }

    private void performDhEatTier(int tier, int[] inv, int marlin, int brew, int hali) {
        if (tier >= 3 && marlin >= 0 && brew >= 0 && hali >= 0) {
            eatFromSlot(marlin, false);
            Humanizer.sameTickPause();
            eatFromSlot(brew, true);
            Humanizer.sameTickPause();
            eatFromSlot(hali, false);
            armSanfewNextTick();
            lastAction = "DH_EAT3@" + currentTick;
            FontManager.log("[DH] triple marlin+brew+halibut — sanfew next tick");
            return;
        }
        if (tier >= 2 && marlin >= 0 && hali >= 0) {
            eatFromSlot(marlin, false);
            Humanizer.sameTickPause();
            eatFromSlot(hali, false);
            lastAction = "DH_EAT2@" + currentTick;
            FontManager.log("[DH] double marlin+halibut");
            return;
        }
        if (marlin >= 0) eatFromSlot(marlin, false);
        else if (hali >= 0) eatFromSlot(hali, false);
        else if (brew >= 0) eatFromSlot(brew, true);
        lastAction = "DH_EAT1@" + currentTick;
        FontManager.log("[DH] single marlin");
    }

    private static final int COMBAT_IDLE_TICKS = 16;

    private boolean canCastVengeance(int tick, boolean requireTarget) {
        if (!autoVengEnabled) return false;
        if (lastVengCastTick >= 0 && tick - lastVengCastTick < VENG_CAST_COOLDOWN) return false;
        if (requireTarget && !hasCombatTarget() && !hasCombatContext()) return false;
        return true;
    }

    /** Fight opener — veng as soon as we have a target (Odablock NH/DH style). */
    public void tryCastVengeanceEngage() {
        if (!canCastVengeance(currentTick, false)) return;
        castVengeance();
    }

    /** Cast vengeance as part of a spec sequence (ignores vengWithSpecOnly). */
    private void tryCastVengeanceWithSpec() {
        if (!canCastVengeance(currentTick, false)) return;
        castVengeance();
    }

    public void tryCastVengeance() {
        if (!canCastVengeance(currentTick, true)) return;
        castVengeance();
    }

    /** Manual E. Queues the cast on the next game tick so it hits the client thread. */
    public void triggerVengNow() {
        pendingVengCast = true;
        lastAction = "VENG_Q@" + currentTick;
    }

    public boolean isVengeanceReady() { return autoVengEnabled; }

    public boolean isVengeanceLocked() {
        return lastVengCastTick >= 0 && currentTick - lastVengCastTick < VENG_CAST_COOLDOWN;
    }

    public int getVengeanceLockedUntilTick() {
        return lastVengCastTick < 0 ? -999 : lastVengCastTick + VENG_CAST_COOLDOWN;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Actions — Combo Eat / Loadout
    // ════════════════════════════════════════════════════════════════════════

    /**
     * NH brew-heavy eats (A/S/D):
     *   1 = brew sip
     *   2 = marlin + brew (same tick)
     *   3 = marlin + brew + halibut (same tick) → sanfew next tick
     */
    private int dhStackHp(int maxHp) {
        return Math.max(1, (maxHp * dharokTargetHpPct) / 100);
    }

    /** True when we are intentionally low for DH (orb stack bracket). */
    private boolean isDhStackBracket(int hp) {
        if (!dharokEnabled || !dharokUseOrb || hp <= 0) return false;
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        return hp <= dhStackHp(maxHp) + 12;
    }

    /** DH never auto-eats except one combo on the greataxe KO tick. */
    private boolean allowComboEat(EatContext context, int hp) {
        if (dharokEnabled) {
            if (context == EatContext.MANUAL) return true;
            return context == EatContext.KILL && dhPostAxeEatArmed && dharokComboEatAfterAxe;
        }
        if (!isDhStackBracket(hp)) return true;
        if (context == EatContext.MANUAL) return true;
        return context == EatContext.KILL && currentTick == lastDhAxeTick;
    }

    public void executeComboEat() {
        executeComboEat(EatContext.MANUAL);
    }

    public void executeComboEat(EatContext context) {
        if (context == null) context = EatContext.AUTO;
        if (dharokEnabled) {
            if (context == EatContext.MANUAL || context == EatContext.KILL) {
                if (context == EatContext.KILL && !dhPostAxeEatArmed) return;
                eatDhToBand(context == EatContext.KILL ? "DH_AXE_EAT" : "DH_MANUAL",
                        effectiveDhEatHp(readLocalHp()), false, true);
                if (context == EatContext.KILL) dhPostAxeEatArmed = false;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastEatMs < MIN_EAT_GAP_MS) return;

        int ourHp = readLocalHp();
        if (ourHp <= 0) return;
        if (!allowComboEat(context, ourHp)) return;
        int ourMax = stateReader != null ? stateReader.getMaxHp() : 99;
        int[] inv = getInventorySnapshot();
        int foodN = countKind(inv, 0);
        int brewN = countKind(inv, 1);
        int halibutN = countHalibut(inv);

        int tier = chooseEatTier(context, ourHp, ourMax, foodN, brewN, halibutN);
        if (tier <= 0) return;

        lastEatMs = now;
        lastComboTick = currentTick;
        lastHeadlessComboTick = currentTick;
        performNhEatTier(tier, inv);
    }

    /** Manual NH eat keybinds (A/S/D → tiers 1–3). No HP gating. */
    public void executeEatTier(int tier) {
        if (tier < 1) tier = 1;
        if (tier > 4) tier = 4;
        lastEatMs = System.currentTimeMillis();
        lastComboTick = currentTick;
        performNhEatTier(tier, getInventorySnapshot());
    }

    /** Manual PK eat keybinds (1–4). Low HP = raw panic eat; otherwise band into 75–85. */
    public void executePkEatTier(int tier) {
        if (tier < 1) tier = 1;
        if (tier > 4) tier = 4;
        lastComboTick = currentTick;
        if (dharokEnabled) {
            if (tier == 4) {
                lastEatMs = System.currentTimeMillis();
                drinkSanfew();
                lastAction = "DH_SANFEW@" + currentTick;
                return;
            }
            int hp = readLocalHp();
            if (hp <= 25 || hp < DH_EAT_BAND_MIN) {
                lastEatMs = System.currentTimeMillis();
                lastDhForceEatTick = currentTick;
                performPkEatTier(tier, getInventorySnapshot());
                lastAction = "DH_PANIC" + tier + "@" + currentTick + " hp=" + hp;
                return;
            }
            eatDhToBand("PK" + tier, effectiveDhEatHp(hp), false, true);
            return;
        }
        lastEatMs = System.currentTimeMillis();
        performPkEatTier(tier, getInventorySnapshot());
    }

    /**
     * NH brew-heavy eats (A/S/D):
     *   1 = brew · 2 = marlin+brew · 3 = marlin+brew+halibut→sanfew
     */
    private void performNhEatTier(int tier, int[] inv) {
        int brew = findSlotOfKind(inv, 1);
        int food = findPrimaryFood(inv, -1);

        switch (tier) {
            case 1:
                if (brew < 0) {
                    FontManager.log("[CombatScript] Eat 1 skipped: no brew");
                    lastAction = "EAT1_NOBREW@" + currentTick;
                    return;
                }
                eatFromSlot(brew, true);
                lastAction = "EAT1_BREW@" + currentTick;
                FontManager.log("[CombatScript] Eat 1 brew " + describeSlot(brew));
                break;
            case 2:
            case 4:
                if (food < 0 && brew < 0) {
                    FontManager.log("[CombatScript] Eat " + tier + " skipped: no food/brew");
                    lastAction = "EAT" + tier + "_EMPTY@" + currentTick;
                    return;
                }
                if (food >= 0) eatFromSlot(food, false);
                if (brew >= 0) eatFromSlot(brew, true);
                lastAction = "EAT" + tier + "@" + currentTick;
                FontManager.log("[CombatScript] Eat " + tier
                        + (food >= 0 ? " food=" + describeSlot(food) : "")
                        + (brew >= 0 ? " brew=" + describeSlot(brew) : ""));
                break;
            case 3:
                if (!performTripleEat(inv, "EAT3")) {
                    FontManager.debug("[CombatScript] Eat 3 skipped: need marlin+brew+halibut");
                    lastAction = "EAT3_MISS@" + currentTick;
                }
                break;
            default:
                break;
        }
    }

    /**
     * PK brew-first eats (1–4):
     *   1 = Single Brew sip
     *   2 = Marlin ➔ Brew (1-tick)
     *   3 = Marlin ➔ Brew ➔ Halibut (1-tick triple)
     *   4 = Restore / Sanfew
     * Every brew sip is counted; after 3 sips sanfew/restore fires automatically
     * to recover the stat drain, then the counter resets.
     */
    private void performPkEatTier(int tier, int[] inv) {
        int brew = findSlotOfKind(inv, 1);
        if (brew < 0) brew = InventoryTracker.findBrewSlot(inv);
        int marlin = findMarlinSlot(inv, -1);
        if (marlin < 0) marlin = findPrimaryFood(inv, -1);

        switch (tier) {
            case 1:
                if (brew >= 0) {
                    drinkBrew(brew);
                    lastAction = "PK_BREW1@" + currentTick;
                } else if (marlin >= 0) {
                    eatFromSlot(marlin, false);
                    lastAction = "PK_SINGLE@" + currentTick;
                } else {
                    lastAction = "PK_EAT1_MISS@" + currentTick;
                }
                break;
            case 2: {
                int halibut = findHalibutSlot(inv, marlin);
                if (marlin >= 0 && brew >= 0) {
                    eatFromSlot(marlin, false);
                    Humanizer.sameTickPause();
                    drinkBrew(brew);
                    lastAction = "PK_MARLIN_BREW@" + currentTick;
                } else if (brew >= 0) {
                    drinkBrew(brew);
                    lastAction = "PK_DOUBLE_BREW@" + currentTick;
                } else if (marlin >= 0) {
                    eatFromSlot(marlin, false);
                    lastAction = "PK_DOUBLE_MARLIN@" + currentTick;
                } else {
                    lastAction = "PK_EAT2_MISS@" + currentTick;
                }
                break;
            }
            case 3:
                if (marlin >= 0 && brew >= 0) {
                    int halibut = findHalibutSlot(inv, marlin);
                    eatFromSlot(marlin, false);
                    Humanizer.sameTickPause();
                    drinkBrew(brew);
                    if (halibut >= 0) {
                        Humanizer.sameTickPause();
                        eatFromSlot(halibut, false);
                        lastAction = "PK_TRIPLE@" + currentTick;
                    } else {
                        lastAction = "PK_MARLIN_BREW@" + currentTick;
                    }
                } else if (brew >= 0) {
                    drinkBrew(brew);
                    lastAction = "PK_EAT3_BREW@" + currentTick;
                } else {
                    lastAction = "PK_EAT3_MISS@" + currentTick;
                }
                break;
            case 4:
                drinkSanfew();
                lastAction = "PK_RESTORE@" + currentTick;
                break;
            default:
                break;
        }
    }

    /** Drink one brew sip and schedule sanfew/restore after three cumulative sips. */
    private void drinkBrew(int slot) {
        if (slot < 0) return;
        eatFromSlot(slot, true);
        brewSips++;
        lastDhBrewTick = currentTick;
        if (brewSips >= 3) {
            pendingSanfew = true;
            brewSips = 0;
        }
    }

    /** Marlin + Sara Brew + Halibut in that exact sequence in the same tick → sanfew / restore next tick. */
    private boolean performTripleEat(int[] inv, String label) {
        int marlin = findMarlinSlot(inv, -1);
        if (marlin < 0) marlin = findPrimaryFood(inv, -1);
        int brew = findSlotOfKind(inv, 1);
        if (brew < 0) brew = InventoryTracker.findBrewSlot(inv);
        int halibut = findHalibutSlot(inv, marlin);

        if (marlin >= 0 && brew >= 0 && halibut >= 0) {
            eatFromSlot(marlin, false);
            Humanizer.sameTickPause();
            eatFromSlot(brew, true);
            Humanizer.sameTickPause();
            eatFromSlot(halibut, false);
            pendingSanfew = true;
            lastDhBrewTick = currentTick;
            lastAction = label + "_TRIPLE@" + currentTick;
            return true;
        } else if (marlin >= 0 && halibut >= 0) {
            eatFromSlot(marlin, false);
            Humanizer.sameTickPause();
            eatFromSlot(halibut, false);
            lastAction = label + "_DBL_HALI@" + currentTick;
            return true;
        } else if (marlin >= 0 && brew >= 0) {
            eatFromSlot(marlin, false);
            Humanizer.sameTickPause();
            eatFromSlot(brew, true);
            pendingSanfew = true;
            lastDhBrewTick = currentTick;
            lastAction = label + "_DBL_BREW@" + currentTick;
            return true;
        } else if (marlin >= 0) {
            eatFromSlot(marlin, false);
            lastAction = label + "_SINGLE@" + currentTick;
            return true;
        } else if (halibut >= 0) {
            eatFromSlot(halibut, false);
            lastAction = label + "_HALI@" + currentTick;
            return true;
        } else if (brew >= 0) {
            eatFromSlot(brew, true);
            pendingSanfew = true;
            lastDhBrewTick = currentTick;
            lastAction = label + "_BREW@" + currentTick;
            return true;
        }
        return false;
    }

    private int findMarlinSlot(int[] inv, int skip) {
        if (inv == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            if (slot == skip) continue;
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            if (InventoryTracker.isMarlin(id, resolveItemName(id))) return slot;
        }
        return -1;
    }

    private int findHalibutSlot(int[] inv, int skip) {
        if (inv == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            if (slot == skip) continue;
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            // ID-based first (no name resolution needed), then name fallback.
            if (InventoryTracker.containsId(InventoryTracker.HALIBUT_IDS, id)) return slot;
            String name = resolveItemName(id);
            if (InventoryTracker.isHalibut(id, name)) return slot;
        }
        return -1;
    }

    private int countHalibut(int[] inv) {
        if (inv == null) return 0;
        int n = 0;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            if (InventoryTracker.isHalibut(id, resolveItemName(id))) n++;
        }
        return n;
    }

    private int chooseEatTier(EatContext ctx, int hp, int maxHp, int foodN, int brewN, int halibutN) {
        if (dharokEnabled && ctx == EatContext.KILL) {
            return chooseDhEatTier(hp, maxHp, foodN > 0, brewN > 0, halibutN > 0);
        }
        if (ctx == EatContext.MANUAL || ctx == EatContext.KILL) {
            if (foodN > 0 && brewN > 0 && halibutN > 0) return 3;
            if (foodN > 0 && brewN > 0) return 2;
            if (brewN > 0) return 1;
            return foodN > 0 ? 2 : 0;
        }
        if (ctx == EatContext.SAFETY) {
            if (dharokEnabled && isDhStackBracket(hp)) return 0;
            if (inDhDanger || (hp > 0 && hp <= 12)) {
                if (foodN > 0 && brewN > 0 && halibutN > 0) return 3;
                if (foodN > 0 && brewN > 0) return 2;
                if (brewN > 0) return 1;
                return foodN > 0 ? 2 : 0;
            }
            return 0;
        }
        int line = Humanizer.eatThreshold(comboEatHpThreshold);
        if (hp <= 0 || hp > line) return 0;
        int brewLine = Math.max(1, brewPreferAboveHp);
        if (hp > brewLine && brewN > 0) return 1;
        if (hp <= Math.max(10, line / 3) && foodN > 0 && brewN > 0 && halibutN > 0) return 3;
        if (hp <= line && foodN > 0 && brewN > 0) return 2;
        if (brewN > 0) return 1;
        return foodN > 0 ? 2 : 0;
    }

    private int findPrimaryFood(int[] inv, int skip) {
        int shark = -1, any = -1;
        if (inv == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            if (slot == skip) continue;
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isBrew(id, name) || InventoryTracker.isRestore(id, name)
                    || InventoryTracker.isHalibut(id, name)
                    || InventoryTracker.isKaram(id, name)) continue;
            if (InventoryTracker.isMarlin(id, name)) return slot;
            if (shark < 0 && InventoryTracker.isShark(id, name)) shark = slot;
            if (any < 0 && InventoryTracker.isFood(id, name)) any = slot;
        }
        return shark >= 0 ? shark : any;
    }

    /** Last-resort food: anything with an Eat option that is not the orb. */
    private int findAnyEdibleSlot(int[] inv, int skip) {
        if (inv == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            if (slot == skip) continue;
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isLocatorOrb(id, name) || InventoryTracker.isRockCake(id, name)) continue;
            if (InventoryTracker.isSuperRestore(id, name)) continue;
            if (InventoryTracker.isFood(id, name) || InventoryTracker.isHalibut(id, name)
                    || InventoryTracker.isKaram(id, name) || InventoryTracker.isBrew(id, name)
                    || InventoryTracker.isMarlin(id, name) || InventoryTracker.isShark(id, name)) {
                return slot;
            }
            String[] opts = readItemInventoryOptions(id);
            if (opts == null) continue;
            for (String opt : opts) {
                if (opt != null && opt.toLowerCase().contains("eat")) return slot;
            }
        }
        return -1;
    }

    private int findCloserFood(int[] inv, int skip) {
        if (inv == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            if (slot == skip) continue;
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isCloser(id, name)) return slot;
        }
        return -1;
    }

    /** kind: 0=primary food, 1=brew, 2=karam/halibut closer */
    private int findSlotOfKind(int[] inv, int kind) {
        if (kind == 0) return findPrimaryFood(inv, -1);
        if (kind == 2) return findCloserFood(inv, -1);
        return findSlotOfKindExcept(inv, kind, -1);
    }

    private int findSlotOfKindExcept(int[] inv, int kind, int skipSlot) {
        if (inv == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            if (slot == skipSlot) continue;
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (kind == 1 && InventoryTracker.isBrew(id, name)) return slot;
        }
        return -1;
    }

    private int countKind(int[] inv, int kind) {
        if (inv == null) return 0;
        int n = 0;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (kind == 0 && InventoryTracker.isFood(id, name) && !InventoryTracker.isCloser(id, name)) n++;
            if (kind == 1 && InventoryTracker.isBrew(id, name)) n++;
            if (kind == 2 && InventoryTracker.isCloser(id, name)) n++;
        }
        return n;
    }

    private int readLocalHp() {
        int hp = stateReader != null ? stateReader.getCurrentHp() : -1;
        if (hp > 0) return hp;
        if (currentSkillLevelField != null) {
            try {
                int[] s = (int[]) currentSkillLevelField.get(clientInstance);
                if (s != null && s.length > 3 && s[3] > 0) return s[3];
            } catch (Exception ignored) {}
        }
        return hp;
    }

    private String describeSlot(int slot) {
        if (slot < 0) return "none";
        int raw = getInventoryItemId(slot);
        int id = raw > 0 ? raw - 1 : -1;
        return "slot" + slot + ":" + InventoryTracker.stripName(resolveItemName(id)) + "(" + id + ")";
    }

    /**
     * Legacy fast switch — still used by PK paths. NH uses {@link #executeItemLoadout}.
     */
    public void executeLoadoutSwitch(String name, int[] slots) {
        if (slots == null || slots.length == 0) return;
        FontManager.log("[CombatScript] Loadout switch: " + name + " (" + slots.length + " items)");
        for (int slot : slots) {
            if (slot >= 0 && slot < 28) equipFromSlot(slot);
        }
        lastAction = "LOADOUT_" + name + "@" + currentTick;
    }

    /** Find where a loadout piece is right now (re-scan every click). */
    private int findSlotForPiece(NhLoadout.Piece piece) {
        if (piece == null) return -1;
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            if (piece.matches(id, resolveItemName(id))) return slot;
        }
        return -1;
    }

    private boolean isPieceWorn(NhLoadout.Piece piece) {
        if (piece == null) return false;
        int[] eq = readAllEquipmentIds();
        if (eq == null) return false;
        for (int raw : eq) {
            int id = decodeEquipId(raw);
            if (id <= 0) continue;
            if (piece.matches(id, resolveItemName(id))) return true;
        }
        return false;
    }

    private void equipLoadoutPiece(NhLoadout.Piece piece) {
        if (piece == null || doActionMethod == null) return;
        if (isPieceWorn(piece)) return;
        int slot = findSlotForPiece(piece);
        if (slot < 0) return;
        equipFromSlot(slot);
    }

    /**
     * Stealth gear switch: weapon → offhand → armor, one item per packet,
     * slot resolved at click time, skips already-worn pieces.
     * Offensive prayer fires after the main weapon (gsoft: equip then p:).
     */
    public void executeItemLoadout(String label, NhLoadout loadout) {
        executeItemLoadout(label, loadout, null);
    }

    public void executeItemLoadout(String label, NhLoadout loadout, Runnable afterWeapon) {
        executeItemLoadout(label, loadout, afterWeapon, false, true);
    }

    private void executeItemLoadout(String label, NhLoadout loadout, Runnable afterWeapon,
                                    boolean armorOnly, boolean setBusy) {
        if (loadout == null || loadout.isEmpty()) {
            lastAction = "NH_EMPTY_" + label + "@" + currentTick;
            return;
        }
        java.util.List<NhLoadout.Piece> sorted = sortLoadoutWeaponFirst(loadout.pieces());
        long delay = Humanizer.firstEquipDelayMs();
        boolean weaponScheduled = false;
        long weaponAt = delay;

        for (NhLoadout.Piece piece : sorted) {
            if (armorOnly && nhPiecePriority(piece) < 2) continue;
            final NhLoadout.Piece p = piece;
            final boolean isWeapon = nhPiecePriority(piece) == 0;
            UiExecutor.schedule(() -> equipLoadoutPiece(p), delay);
            if (!weaponScheduled && isWeapon) {
                weaponScheduled = true;
                weaponAt = delay;
            }
            delay += Humanizer.invGapMs();
        }

        if (afterWeapon != null) {
            long prayAt = (weaponScheduled ? weaponAt : Humanizer.firstEquipDelayMs())
                    + Humanizer.tickDelayMs();
            UiExecutor.schedule(afterWeapon, prayAt);
        }

        if (setBusy) {
            nhSwitchBusyUntilMs = System.currentTimeMillis() + delay + 80;
        }
        lastAction = "NH_" + label + "@" + currentTick;
    }

    private java.util.List<NhLoadout.Piece> sortLoadoutWeaponFirst(java.util.List<NhLoadout.Piece> pieces) {
        java.util.ArrayList<NhLoadout.Piece> out = new java.util.ArrayList<>(pieces);
        out.sort(java.util.Comparator.comparingInt(this::nhPiecePriority));
        return out;
    }

    private int nhPiecePriority(NhLoadout.Piece piece) {
        if (piece == null) return 99;
        return InventoryTracker.nhEquipPriority(piece.itemId, piece.nameKey);
    }

    public void toggleLoadoutPiece(NhLoadout loadout, InvCell cell) {
        if (loadout == null || cell == null || cell.empty()) return;
        NhLoadout.Piece p = new NhLoadout.Piece(cell.itemId,
                InventoryTracker.stripName(cell.name));
        loadout.toggle(p);
        NhLoadout.saveAll(this);
    }

    public boolean loadoutContains(NhLoadout loadout, InvCell cell) {
        if (loadout == null || cell == null || cell.empty()) return false;
        return loadout.contains(new NhLoadout.Piece(cell.itemId,
                InventoryTracker.stripName(cell.name)));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  NH — ice barrage + staggered gear (mini overlay tab)
    // ════════════════════════════════════════════════════════════════════════

    public void triggerBarrageNow() {
        pendingBarrage = true;
        refreshAttackTarget();
        lastAction = "ICE_Q@" + currentTick;
    }

    public void toggleNhAuto() {
        nhEnabled = !nhEnabled;
        nhPhaseName = nhEnabled ? "AUTO" : "IDLE";
        lastAction = nhEnabled ? "NH_ON" : "NH_OFF";
        FontManager.log("[CombatScript] NH auto " + (nhEnabled ? "ON" : "OFF"));
    }

    public int freezeTicksLeft() {
        return Math.max(0, freezeUntilTick - currentTick);
    }

    public String freezeDisplayLabel() {
        int t = freezeTicksLeft();
        if (t <= 0) return "—";
        return t + "t (" + String.format("%.1f", t * 0.6) + "s)";
    }

    public boolean isNhFrozen() {
        return freezeTicksLeft() > 0;
    }

    public void nhSwitchMage() {
        nhPhaseName = "MAGE";
        executeItemLoadout("G", mageLoadout, this::ensureMysticMight);
    }

    public void nhSwitchRange() {
        nhPhaseName = "RANGE";
        executeItemLoadout("R", rangeLoadout, this::ensureEagleEye);
    }

    public void nhSwitchMelee() {
        nhPhaseName = "MELEE";
        executeItemLoadout("M", meleeLoadout, this::ensurePiety);
    }

    /** E / PK overlay — wield VLS first, spec next tick, armor fills in background. */
    public void triggerVlsSpecNow() {
        pendingVlsFire = false;
        nhPhaseName = "VLS";
        lastAction = "VLS_Q@" + currentTick;

        WeaponRef vls = findGear(InventoryTracker::isVls);
        if (vls == null) {
            lastAction = "NO_VLS@" + currentTick;
            FontManager.debug("[CombatScript] No VLS in inv/equip");
            return;
        }

        int tick = currentTick;
        if (vls.inInventory()) {
            wieldItem(vls.slot, vls.itemId);
            lastAction = "VLS_WIELD@" + tick;
        }
        pendingVlsFire = true;
        vlsWieldTick = tick;
        UiExecutor.schedule(this::wieldDragonDefender, Humanizer.invGapMs());
        UiExecutor.schedule(this::ensurePiety, Humanizer.invGapMs());
        UiExecutor.schedule(
                () -> executeItemLoadout("M", meleeLoadout, null, true, false),
                Humanizer.invGapMs() * 2L);
    }

    public void nhSwitchTank() {
        nhPhaseName = "TANK";
        executeItemLoadout("T", tankLoadout);
    }

    private boolean nhSwitchBusy() {
        return System.currentTimeMillis() < nhSwitchBusyUntilMs;
    }

    private boolean isNhHolding() {
        return nhEnabled && (isNhFrozen() || nhSwitchBusy()
                || (lastBarrageTick >= 0 && currentTick - lastBarrageTick <= BARRAGE_CAST_TICKS + 2));
    }

    private void runNhTick(int tick) {
        if (dmacePhase > 0 || pendingQDump) return;
        if (nhSwitchBusy()) return;

        int left = freezeTicksLeft();
        boolean needFreeze = left <= REFREEZE_LEAD_TICKS;

        if (needFreeze) {
            if (tick - lastBarrageTick < BARRAGE_CAST_TICKS) {
                nhPhaseName = "ICE";
                return;
            }
            if (!hasCombatTarget() && stickyTarget == null) {
                nhPhaseName = "WAIT";
                return;
            }
            if (Humanizer.delayNhRecast() && left > 0) return;
            nhPhaseName = "MAGE";
            executeItemLoadout("G", mageLoadout, this::ensureMysticMight);
            UiExecutor.schedule(this::nhCastBarrage, Math.max(90, (int) (nhSwitchBusyUntilMs - System.currentTimeMillis())));
            lastBarrageTick = tick;
            return;
        }

        nhPhaseName = "FZ" + left;
        if (!nhRangedThisFreeze && tick - lastBarrageTick >= BARRAGE_CAST_TICKS + 1) {
            nhRangedThisFreeze = true;
            nhSwitchRange();
            return;
        }
        if (nhRangedThisFreeze && !nhMeleedThisFreeze) {
            if (targetHp > 0 && targetHp <= nhKoHp) {
                nhMeleedThisFreeze = true;
                nhSwitchMelee();
                nhPhaseName = "KO_MELEE";
                return;
            }
            if (left <= Humanizer.nhMeleePrepTicks()) {
                nhMeleedThisFreeze = true;
                nhSwitchMelee();
                nhPhaseName = "PRE_MELEE";
            }
        }
    }

    /**
     * Ice barrage — MANUAL cast. Arms the spell so the player's left-click on a
     * target casts it (no auto-target). The player attacks via explicit
     * {@code a:last} / {@code a:player} swap commands or their own clicks.
     */
    private void nhCastBarrage() {
        if (doActionMethod == null) {
            lastAction = "ICE_NO_DOACTION@" + currentTick;
            return;
        }
        ensureMagicTab();
        lastBarrageTick = currentTick;
        nhPhaseName = "ICE";
        // Arm Ice Barrage for manual left-click cast — never auto-cast on a target.
        armLeftClickIceBarrage();
        lastAction = "ICE_ARM@" + currentTick;
    }

    /** Second tick after spellbook select — cast on target (opcode 365 / frame 249). */
    private boolean nhFinishBarrageCast() {
        refreshAttackTarget();
        if (cachedAttackId < 0) return false;
        if (!isIceBarrageArmed()) {
            selectIceBarrageSpell();
        }
        if (castIceOnTarget("Ice Barrage")) {
            lastAction = "ICE_CAST@" + currentTick;
            return true;
        }
        if (castIceOnTarget("Ice barrage")) {
            lastAction = "ICE_CAST@" + currentTick;
            return true;
        }
        return false;
    }

    private boolean isIceBarrageArmed() {
        try {
            if (clientSpellSelectedField != null && clientSpellSelectedField.getInt(null) == 1) {
                if (clientSpellIdField != null) {
                    return clientSpellIdField.getInt(null) == iceBarrageWidgetId;
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean needsSpellbookBarrage() {
        int wid = readEquippedWeaponId();
        if (wid > 0) {
            String name = resolveItemName(wid);
            if (InventoryTracker.isNonAutocastStaff(wid, name)) return true;
            if (InventoryTracker.isAutocastStaff(wid, name)) return false;
        }
        int[] inv = getInventorySnapshot();
        for (int raw : inv) {
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isNonAutocastStaff(id, name)) return true;
        }
        return true;
    }

    private static final int[][] ICE_BARRAGE_WIDGETS = {
            {12891, 315}, {12891, 626}, {12891, 0},
            {12855, 75}, {12855, 74}, {12855, 76}, {12855, 77},
            {218, 80}, {218, 79}, {218, 78},
    };

    private boolean selectIceBarrageSpell() {
        ensureMagicTab();
        // Same shape as a working Vengeance click: (widget, child=315, id=-1, "Cast").
        if (clickSpellOnBook(iceBarrageWidgetId, "Ice Barrage")) return true;
        if (clickSpellOnBook(iceBarrageWidgetId, "Ice barrage")) return true;
        int[] found = findSpellWidget("ice barrage");
        if (found != null && clickSpellOnBook(found[0], "Ice Barrage")) return true;
        for (int[] w : ICE_BARRAGE_WIDGETS) {
            if (clickSpellOnBook(w[0], "Ice Barrage")) return true;
        }
        return armIceBarrageSelected();
    }

    /**
     * Click a spell on the book. Roat doAction is
     * (0, slot, widget, option, item, 0, "Cast", name, -1, -1) —
     * option 315 is Cast (same as Vengeance); 626 is OSRS spell-select.
     */
    private boolean clickSpellOnBook(int widget, String name) {
        if (doActionMethod == null || widget <= 0) return false;
        if (name == null || name.isEmpty()) name = "Ice Barrage";
        // 626 is "select spell" (click-cast). 315 is instant-cast buttons like Vengeance
        // and must not short-circuit Ice Barrage.
        int[] options = { SPELL_SELECT_OPCODE, 315, 0, -1 };
        boolean any = false;
        for (int opt : options) {
            try {
                doActionMethod.invoke(clientInstance, 0, 0, widget, opt, widget, 0, "Cast", name, -1, -1);
                any = true;
                if (opt == SPELL_SELECT_OPCODE) break;
            } catch (Exception ignored) {}
            if (invokeClientAction(0, widget, opt, widget, "Cast", name)) {
                any = true;
                if (opt == SPELL_SELECT_OPCODE) break;
            }
        }
        armSpellSelected(widget, name);
        return any || readIntField(clientSpellSelectedField) == 1;
    }

    /** Client.java opcode 626 — selects spell; 365/413 casts on target (frame 249/131). */
    private boolean invokeClientAction(int param0, int param1, int opcode, int id,
                                       String option, String target) {
        if (doActionMethod == null) return false;
        try {
            doActionMethod.invoke(clientInstance, 0, param0, param1, opcode, id, 0,
                    option != null ? option : "", target != null ? target : "", -1, -1);
            return true;
        } catch (Exception e) {
            FontManager.debug("[CombatScript] doAction op=" + opcode + " " + e.getMessage());
            return false;
        }
    }

    /** Mirror Client.spellSelected=1 after opcode 626 (Ice Barrage widget id). */
    private boolean armIceBarrageSelected() {
        return armSpellSelected(iceBarrageWidgetId, "Ice Barrage");
    }

    private boolean castIceOnTarget(String spellLabel) {
        int idx = cachedAttackId;
        if (idx < 0) return false;

        if (cachedAttackIsPlayer) {
            if (invokeClientAction(0, 0, SPELL_ON_PLAYER_OPCODE, idx, "Cast", spellLabel)) {
                return true;
            }
            if (sendIceBarrageOnPlayerPacket(idx)) {
                return true;
            }
        } else {
            if (invokeClientAction(0, 0, SPELL_ON_NPC_OPCODE, idx, "Cast", spellLabel)) {
                return true;
            }
            if (sendIceBarrageOnNpcPacket(idx)) {
                return true;
            }
        }

        // Legacy opcodes (older clients / fallback)
        String tgtName = targetName != null && !targetName.isEmpty() ? targetName : "";
        int[][] tries = cachedAttackIsPlayer
                ? new int[][] { {8, idx}, {51, idx}, {44, idx} }
                : new int[][] { {8, idx}, {44, idx} };
        for (int[] t : tries) {
            for (String label : new String[] { spellLabel, "Ice Barrage", "Ice barrage" }) {
                if (invokeClientAction(0, 0, t[0], t[1], "Cast", label)) return true;
                if (!tgtName.isEmpty() && invokeClientAction(0, 0, t[0], t[1], "Cast", tgtName)) return true;
            }
        }
        return false;
    }

    /** Frame 249 — spell on player (needs spell selected / anInt1137 = widget id). */
    private boolean sendIceBarrageOnPlayerPacket(int playerId) {
        if (bufferField == null) return false;
        try {
            Object buf = bufferField.get(clientInstance);
            if (buf == null) return false;
            ensureBufferWriters(buf);
            if (bufferCreateFrame == null) return false;
            armIceBarrageSelected();
            bufferCreateFrame.invoke(buf, 249);
            if (bufferWriteUnsignedShortAdd != null) {
                bufferWriteUnsignedShortAdd.invoke(buf, playerId);
            } else {
                bufferWriteUnsignedShort.invoke(buf, playerId);
            }
            if (bufferWriteUnsignedShortLE != null) {
                bufferWriteUnsignedShortLE.invoke(buf, iceBarrageWidgetId);
            } else {
                bufferWriteUnsignedShort.invoke(buf, iceBarrageWidgetId);
            }
            lastAction = "ICE_PKT@" + currentTick;
            return true;
        } catch (Exception e) {
            FontManager.debug("[CombatScript] ice player packet: " + e.getMessage());
            return false;
        }
    }

    /** Frame 131 — spell on NPC. */
    private boolean sendIceBarrageOnNpcPacket(int npcId) {
        if (bufferField == null) return false;
        try {
            Object buf = bufferField.get(clientInstance);
            if (buf == null) return false;
            ensureBufferWriters(buf);
            if (bufferCreateFrame == null) return false;
            armIceBarrageSelected();
            bufferCreateFrame.invoke(buf, 131);
            if (bufferWriteUnsignedShortLEAdd != null) {
                bufferWriteUnsignedShortLEAdd.invoke(buf, npcId);
            } else if (bufferWriteUnsignedShortAdd != null) {
                bufferWriteUnsignedShortAdd.invoke(buf, npcId);
            } else {
                bufferWriteUnsignedShort.invoke(buf, npcId);
            }
            if (bufferWriteUnsignedShortAdd != null) {
                bufferWriteUnsignedShortAdd.invoke(buf, iceBarrageWidgetId);
            } else {
                bufferWriteUnsignedShort.invoke(buf, iceBarrageWidgetId);
            }
            lastAction = "ICE_NPC@" + currentTick;
            return true;
        } catch (Exception e) {
            FontManager.debug("[CombatScript] ice npc packet: " + e.getMessage());
            return false;
        }
    }

    void ensureBufferWriters(Object buf) {
        if (bufferCreateFrame != null) return;
        try {
            Class<?> bc = buf.getClass();
            bufferCreateFrame = bc.getMethod("createFrame", int.class);
            bufferWriteUnsignedShort = bc.getMethod("writeUnsignedShort", int.class);
            for (String m : new String[] { "writeUnsignedShortAdd", "writeUnsignedShortLE",
                    "writeUnsignedShortLEAdd" }) {
                try {
                    Method meth = bc.getMethod(m, int.class);
                    if ("writeUnsignedShortAdd".equals(m)) bufferWriteUnsignedShortAdd = meth;
                    if ("writeUnsignedShortLE".equals(m)) bufferWriteUnsignedShortLE = meth;
                    if ("writeUnsignedShortLEAdd".equals(m)) bufferWriteUnsignedShortLEAdd = meth;
                } catch (NoSuchMethodException ignored) {}
            }
            try {
                bufferWriteUnsignedByte = bc.getMethod("writeUnsignedByte", int.class);
            } catch (NoSuchMethodException ignored) {}
        } catch (Exception ignored) {}
    }

    /** Scan spellbook widgets for Ice Barrage (ancient tab). */
    private int[] findSpellWidget(String spellName) {
        if (interfaceCacheField == null || spellName == null) return null;
        String needle = spellName.toLowerCase();
        try {
            Object[] cache = (Object[]) interfaceCacheField.get(null);
            if (cache == null) return null;
            for (int group = 0; group < cache.length; group++) {
                Object iface = cache[group];
                if (iface == null) continue;
                if (ifaceMatchesSpell(iface, needle, group, group)) return new int[] { group, readIfaceInt(iface, "id") };
                String[] actions = readIfaceStringArray(iface, "actions");
                if (actions != null) {
                    for (int i = 0; i < actions.length; i++) {
                        if (actions[i] != null && actions[i].toLowerCase().contains(needle)) {
                            int id = readIfaceInt(iface, "id");
                            if (id < 0) id = i;
                            return new int[] { group, id };
                        }
                    }
                }
                for (String field : new String[] { "spellName", "message", "defaultText", "tooltip" }) {
                    String text = readIfaceString(iface, field);
                    if (text != null && text.toLowerCase().contains(needle)) {
                        int id = readIfaceInt(iface, "id");
                        if (id < 0) id = 0;
                        int parent = readIfaceInt(iface, "parentID");
                        return new int[] { parent > 0 ? parent : group, id };
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean ifaceMatchesSpell(Object iface, String needle, int group, int child) {
        String[] actions = readIfaceStringArray(iface, "actions");
        if (actions != null) {
            for (String a : actions) {
                if (a != null && a.toLowerCase().contains(needle)) return true;
            }
        }
        for (String field : new String[] { "spellName", "message", "defaultText" }) {
            String text = readIfaceString(iface, field);
            if (text != null && text.toLowerCase().contains(needle)) return true;
        }
        return false;
    }

    private static String readIfaceString(Object iface, String field) {
        try {
            Field f = findField(iface.getClass(), field);
            if (f == null) return null;
            Object v = f.get(iface);
            return v != null ? v.toString() : null;
        } catch (Exception e) { return null; }
    }

    private static String[] readIfaceStringArray(Object iface, String field) {
        try {
            Field f = findField(iface.getClass(), field);
            if (f == null) return null;
            return (String[]) f.get(iface);
        } catch (Exception e) { return null; }
    }

    private static int readIfaceInt(Object iface, String field) {
        try {
            Field f = findField(iface.getClass(), field);
            if (f == null) return -1;
            return f.getInt(iface);
        } catch (Exception e) { return -1; }
    }

    private void nhCastOnTarget() {
        castIceOnTarget("Ice Barrage");
    }

    public void equipFromSlot(int slot) {
        if (doActionMethod == null && sendInterfaceItemClickMethod == null) return;
        try {
            int rawId = getInventoryItemId(slot);
            if (rawId <= 0) return;
            int id = rawId - 1;
            // Roat: opcode 454 / 74 wield is the reliable path used by PK gear dumps.
            if (doActionMethod != null) {
                doActionMethod.invoke(clientInstance, 0, slot, 3214, 454, id, 0, "Wield", "", -1, -1);
                doActionMethod.invoke(clientInstance, 0, slot, 3214,  74, id, 0, "Wield", "", -1, -1);
            }
            int row = inventoryActionRow(id, "wield", "wear", "equip");
            if (row < 0) row = 1;
            clickInterfaceItem(3214, slot, id, row);
        } catch (Exception e) {
            FontManager.log("[CombatScript] equipFromSlot error: " + e.getMessage());
        }
    }

    public void eatFromSlot(int slot) {
        eatFromSlot(slot, false);
    }

    public void eatFromSlot(int slot, boolean allowBrew) {
        if (doActionMethod == null && sendInterfaceItemClickMethod == null) return;
        try {
            int rawId = getInventoryItemId(slot);
            if (rawId <= 0) return;
            int id = rawId - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isRestore(id, name)) {
                drinkSanfewSlot(slot, id);
                return;
            }
            if (InventoryTracker.isBrew(id, name) && !allowBrew) {
                FontManager.log("[CombatScript] Brew held for triple eat");
                return;
            }
            String action = (InventoryTracker.isBrew(id, name) && allowBrew) ? "Drink" : "Eat";
            int row = 0;
            String[] opts = readItemInventoryOptions(id);
            if (opts != null) {
                for (int i = 0; i < opts.length; i++) {
                    String opt = opts[i];
                    if (opt == null || opt.isEmpty()) continue;
                    String low = opt.toLowerCase();
                    if (low.contains("eat") || (allowBrew && low.contains("drink"))) {
                        action = opt;
                        row = i;
                        break;
                    }
                }
            }
            // Opcode 74 = inventory Eat/Drink on this client (packet 122). Do not use 447 (Use).
            if (doActionMethod != null) {
                doActionMethod.invoke(clientInstance, 0, slot, 3214, 74, id, 0, action, "", -1, -1);
                return;
            }
            clickInterfaceItem(3214, slot, id, row);
        } catch (Exception e) {
            FontManager.log("[CombatScript] eatFromSlot error: " + e.getMessage());
        }
    }

    /** Sip sanfew or super restore. */
    private void drinkSanfew() {
        if (currentTick == lastSanfewTick) return;
        int[] inv = getInventorySnapshot();
        int slot = InventoryTracker.findRestoreSlot(inv);
        if (slot < 0) {
            for (int s = 0; s < inv.length; s++) {
                int raw = inv[s];
                if (raw <= 0) continue;
                if (InventoryTracker.isRestore(raw - 1, resolveItemName(raw - 1))) {
                    slot = s;
                    break;
                }
            }
        }
        if (slot < 0) return;
        int raw = getInventoryItemId(slot);
        if (raw <= 0) return;
        drinkSanfewSlot(slot, raw - 1);
    }

    private void drinkSanfewSlot(int slot, int itemId) {
        if (doActionMethod == null || slot < 0) return;
        try {
            doActionMethod.invoke(clientInstance, 0, slot, 3214, 74, itemId, 0,
                    "Drink", "", -1, -1);
            lastSanfewTick = currentTick;
            brewSips = 0;
            lastAction = "RESTORE@" + currentTick;
        } catch (Exception e) {
            FontManager.log("[CombatScript] restore drink error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inventory / State readers
    // ════════════════════════════════════════════════════════════════════════

    public int getInventoryItemId(int slot) {
        if (interfaceCacheField == null) return 0;
        try {
            Object[] cache = (Object[]) interfaceCacheField.get(null);
            if (cache == null || cache.length <= 3214 || cache[3214] == null) return 0;
            Object iface = cache[3214];
            Field invF = inventoryItemIdField(iface);
            if (invF == null) return 0;
            int[] ids = (int[]) invF.get(iface);
            if (ids == null || slot < 0 || slot >= ids.length) return 0;
            return ids[slot];
        } catch (Exception e) { return 0; }
    }

    public int[] getInventorySnapshot() {
        if (interfaceCacheField == null) return new int[28];
        try {
            Object[] cache = (Object[]) interfaceCacheField.get(null);
            if (cache == null || cache.length <= 3214 || cache[3214] == null) return new int[28];
            Object iface = cache[3214];
            Field invF = inventoryItemIdField(iface);
            if (invF == null) return new int[28];
            int[] ids = (int[]) invF.get(iface);
            return ids != null ? ids.clone() : new int[28];
        } catch (Exception e) { return new int[28]; }
    }

    /** Cached {@code RSInterface.inventoryItemId} handle — resolved once, reused every call. */
    private Field cachedInventoryItemIdField;
    private Class<?> cachedInventoryItemIdOwner;
    private Field inventoryItemIdField(Object iface) {
        if (cachedInventoryItemIdField != null && cachedInventoryItemIdOwner == iface.getClass()) {
            return cachedInventoryItemIdField;
        }
        try {
            Field f = iface.getClass().getField("inventoryItemId");
            f.setAccessible(true);
            cachedInventoryItemIdField = f;
            cachedInventoryItemIdOwner = iface.getClass();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private int readSequence(Object actor) {
        if (sequenceField == null) return -1;
        try { return sequenceField.getInt(actor); } catch (Exception e) { return -1; }
    }

    @SuppressWarnings("unchecked")
    private void readLatestHitsplat(Object actor, boolean incoming) {
        if (hitSplatsField == null || actor == null) return;
        try {
            ArrayList<?> splats = (ArrayList<?>) hitSplatsField.get(actor);
            if (splats == null || splats.isEmpty()) return;
            Object last = splats.get(splats.size() - 1);
            if (last == null) return;
            if (getDamageMethod  == null) getDamageMethod  = last.getClass().getMethod("getDamage");
            if (getSplatIdMethod == null) getSplatIdMethod = last.getClass().getMethod("getSplatId");
            int dmg = (int) getDamageMethod.invoke(last);
            int type = (int) getSplatIdMethod.invoke(last);
            // Identity, not value: addCombatHit allocates a new HitSplat per hit,
            // so two identical rolls in a row still register as a fresh splat.
            if (incoming) {
                lastIncomingDmg = dmg;
                if (last != lastIncomingSplatRef) {
                    lastIncomingSplatRef = last;
                    prevIncomingDmg = dmg;
                    prevIncomingType = type;
                    incomingChangeTick = currentTick;
                }
            } else {
                lastHitsplatDmg  = dmg;
                lastHitsplatType = type;
                if (last != lastOutgoingSplatRef) {
                    lastOutgoingSplatRef = last;
                    prevSplatDmg = dmg;
                    prevSplatType = type;
                    hitsplatChangeTick = currentTick;
                }
            }
        } catch (Exception ignored) {}
    }

    private String readTargetName(Object actor) {
        try {
            Method m = findMethod(actor.getClass(), "getName", 0);
            if (m != null) return (String) m.invoke(actor);
        } catch (Exception ignored) {}
        return "???";
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Subsystem delegates — used by GearSwapEngine / DharokController
    // ════════════════════════════════════════════════════════════════════════

    public void wieldItemPublic(int slot, int itemId) { wieldItem(slot, itemId); }
    public boolean specAndAttackPublic()              { return specAndAttack(); }
    public void activatePietyPublic()                 { activatePiety(); }
    public String resolveItemNamePublic(int id)       { return resolveItemName(id); }
    public boolean hasCombatContextPublic()           { return hasCombatContext(); }
    public boolean hasCombatTargetPublic()            { return hasCombatTarget(); }
    public boolean isInActivePvpFightPublic()         { return isInActivePvpFight(); }
    public Object getCachedTargetPublic()             { return cachedTarget; }
    public WeaponRef findGreataxePublic()             { return findGreataxe(); }
    public int readLocalHpPublic()                    { return readLocalHp(); }
    public int readMeleeStrPublic()                   { return readMeleeStr(); }
    public boolean isSpecSequenceBusyPublic()         { return isSpecSequenceBusy(); }

    public void useInventoryItemPublic(int slot, String tag) {
        int raw = getInventoryItemId(slot);
        if (raw <= 0) return;
        int id = raw - 1;
        String name = resolveItemName(id);
        if (InventoryTracker.isLocatorOrb(id, name) || InventoryTracker.isRockCake(id, name)) {
            useHpReducer(slot, id, name);
        } else {
            eatFromSlot(slot, true);
        }
        if (tag != null) lastAction = tag + "@" + currentTick;
    }

    /** Locator orb = Feel/Rub; rock cake = Guzzle/Eat. Opcode 74 only (same as food eat). */
    public boolean useHpReducer(int slot, int itemId, String name) {
        if (dharokEnabled && !dharokUseOrb) return false;
        if (doActionMethod == null || slot < 0 || itemId < 0) return false;
        if (!slotLooksLikeHpReducer(slot, itemId, name)) return false;

        int rawId = getInventoryItemId(slot);
        int id = rawId > 0 ? rawId - 1 : itemId;

        String[] opts = readItemInventoryOptions(id);
        if (opts != null) {
            for (String opt : opts) {
                if (opt == null || opt.isEmpty()) continue;
                if (!isHpReducerMenuOption(opt)) continue;
                try {
                    doActionMethod.invoke(clientInstance, 0, slot, 3214, 74, id, 0,
                            opt, "", -1, -1);
                    FontManager.debug("[HP] " + opt + " op=74 id=" + id + " slot=" + slot);
                    return true;
                } catch (Exception ignored) {}
            }
        }

        java.util.LinkedHashSet<String> fallbacks = new java.util.LinkedHashSet<>();
        if (InventoryTracker.isLocatorOrb(itemId, name) || InventoryTracker.isLocatorLikeName(name)) {
            fallbacks.add("Feel");
            fallbacks.add("Rub");
            fallbacks.add("Operate");
        }
        if (InventoryTracker.isRockCake(itemId, name)) {
            fallbacks.add("Guzzle");
            fallbacks.add("Eat");
        }
        if (fallbacks.isEmpty()) {
            fallbacks.add("Feel");
            fallbacks.add("Rub");
        }
        for (String action : fallbacks) {
            try {
                doActionMethod.invoke(clientInstance, 0, slot, 3214, 74, id, 0,
                        action, "", -1, -1);
                FontManager.debug("[HP] " + action + " op=74 id=" + id + " slot=" + slot);
                return true;
            } catch (Exception ignored) {}
        }
        FontManager.log("[CombatScript] HP reducer failed slot=" + slot + " id=" + itemId
                + " name=" + name);
        return false;
    }

    /** Same-tick gmaul punish — no gear-swap delay chain. */
    public boolean fireGmaulPunishPublic() {
        activatePiety();
        return fireGmaulSameTick("PUNISH_GMAUL");
    }

    public int lastDhAxeTickPublic() { return lastDhAxeTick; }

    public void noteDhOrbTick(int tick) {
        lastDhOrbTick = tick;
    }

    /** Greataxe on player — used for manual swap whip+def restore. */
    public boolean isGreataxeEquippedPublic() {
        WeaponRef axe = findGreataxe();
        return axe != null && axe.equipped;
    }

    /**
     * Player wielded greataxe by hand — attack, never rip it off for whip+def.
     * Whip restore only arms after a few ticks or after a bot axe swing.
     */
    public void pumpManualDhAxe(int tick) {
        if (!dharokEnabled || !isInActivePvpFight()) return;
        if (!isGreataxeEquippedPublic()) {
            manualDhAxeSinceTick = -99;
            return;
        }
        // The bot swung this axe and owes a whip+def restore — leave it alone.
        if (pendingDhWhipDef) return;
        if (tick == lastDhAxeTick) return;

        int stackHp = stackTargetHpPublic();
        int hp = readLocalHp();
        boolean stacked = hp <= stackHp + 4;
        boolean imminentKo = stacked && isSafeDhBurstWindow() && inKillRange
                && tick - lastDhAxeTick >= 7;
        // Low and no KO on the table — the controller eats instead of swinging.
        if (hp < DH_EAT_BAND_MIN && !imminentKo && !inSelfOrbWindow(tick)) return;

        if (manualDhAxeSinceTick < 0) manualDhAxeSinceTick = tick;

        pulseSpecOff();
        activatePiety();
        if (reAttackTarget()) {
            lastAction = "DH_MANUAL_AXE@" + tick;
        }

        // Stacked manual KO — fire the full axe+eat path when the window is open.
        if (imminentKo) {
            tryOdablockDhAxe(true);
        }
    }

    /**
     * Greataxe left on by hand with no KO coming: arm the whip+def restore so we
     * do not tank in a 2h. Held off while stacked or inside a manual orb window.
     */
    public void noteManualDhAxeRestore(int tick) {
        if (!dharokEnabled) return;
        if (pendingDhWhipDef || !isGreataxeEquippedPublic()) return;
        if (manualDhAxeSinceTick < 0 || tick - manualDhAxeSinceTick < MANUAL_AXE_HOLD_TICKS) return;
        if (pendingDhStack || dharokStackArmed) return;
        if (inSelfOrbWindow(tick)) return;
        if (readLocalHp() <= stackTargetHpPublic() + 4) return;
        if (tick - lastDhAxeTick < 7) return;
        pendingDhWhipDef = true;
        lastAction = "DH_ARM_WHIP_DEF@" + tick;
    }

    /** Ticks a hand-equipped greataxe may stay on before we swap back to whip. */
    private static final int MANUAL_AXE_HOLD_TICKS = 4;

    private int stackTargetHpPublic() {
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        return Math.max(1, (maxHp * Math.max(1, dharokTargetHpPct)) / 100);
    }

    public boolean tryOdablockDhAxe() {
        return tryOdablockDhAxe(false);
    }

    /**
     * Stacked greataxe (normal hit, spec off) → gmaul spec if they can survive the axe
     * → eat into 75–85. {@code committed} skips the safe-window re-check mid-burst.
     */
    public boolean tryOdablockDhAxe(boolean committed) {
        if (!dharokEnabled) return false;
        if (!isInActivePvpFight()) return false;
        if (currentTick - lastDhAxeTick < 7) return false;
        int hp = readLocalHp();
        int maxHp = stateReader != null ? stateReader.getMaxHp() : 99;
        if (hp <= 0) return false;

        int opp = liveTargetHp();
        WeaponRef axe = findGreataxe();
        if (axe == null) return false;

        int dhHit = MaxHitCalculator.dharokMaxHit(readMeleeStr(), hp, maxHp);
        estimatedOurMaxHit = dhHit;

        lastKillOppHp = opp;
        pendingKillMode = -1;
        pendingDhAxeAfterStack = false;
        activatePiety();
        pulseSpecOff();
        if (!ensureGreataxeEquipped()) {
            lastAction = "DH_AXE_NO_WIELD@" + currentTick;
            FontManager.log("[DH] greataxe not equipped (inv full?) — no whip spec");
            return false;
        }
        Humanizer.sameTickPause();
        lastDhAxeTick = currentTick;
        lastHeadlessSpecTick = currentTick;
        reAttackTarget();
        Humanizer.sameTickPause();
        armDhMustEat();

        if (hp <= 35) {
            dhPostAxeEatArmed = true;
            performDhSmallEat("DH_AXE_EAT");
            dhPostAxeEatArmed = false;
        }

        if (opp > 0 && specEnergy >= 50) {
            pendingDhGmaulFollow = true;
            pendingDhGmaulOppHp = opp;
            pendingDhGmaulDhHit = dhHit;
        }
        lastAction = (pendingDhGmaulFollow ? "DH_AXE_ARM_GMAUL@" : "DH_AXE_SWING@") + currentTick
                + " opp=" + opp + " hit=" + dhHit;
        FontManager.log("[DH] axe swing oppHp=" + opp
                + " ourHp=" + hp + " maxHit=" + dhHit
                + (pendingDhGmaulFollow ? " gmaul+1" : ""));
        pendingDhStack = false;
        dharokStackArmed = false;
        pendingDhWhipDef = true;
        return true;
    }

    /**
     * Re-equip Whip (1h) + Defender together in the same tick / sequence, spec off, re-attack.
     */
    public boolean tryRestoreWhipDefAfterDh(int tick) {
        if (!pendingDhWhipDef) return false;
        if (tick <= lastDhAxeTick) return false;
        if (pendingSanfew || tick <= lastDhBrewTick) return false;
        boolean ok = restoreWhipAndDefNow();
        pendingDhWhipDef = false;
        lastAction = (ok ? "DH_WHIP_DEF@" : "DH_WHIP_DEF_RETRY@") + tick;
        if (ok) FontManager.log("[DH] whip + defender restored");
        return ok;
    }

    public boolean restoreWhipAndDefNow() {
        pulseSpecOff();
        boolean mhWielded = meleeWhipFangEquipped();
        if (!mhWielded) {
            WeaponRef mh = meleeInInventory();
            if (mh != null) {
                wieldItem(mh.slot, mh.itemId);
                mhWielded = true;
            }
        }
        boolean defWielded = defenderEquipped();
        if (!defWielded) {
            WeaponRef def = defenderInInventory();
            if (def != null) {
                Humanizer.sameTickPause();
                wieldItem(def.slot, def.itemId);
                defWielded = true;
            }
        }
        reAttackTarget();
        return whipAndDefEquipped() || (mhWielded && defWielded);
    }

    private WeaponRef meleeInInventory() {
        int[] inv = getInventorySnapshot();
        WeaponRef fang = null;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isWhip(id, name)) return new WeaponRef(slot, id, name, false);
            if (fang == null && InventoryTracker.isFang(id, name)) {
                fang = new WeaponRef(slot, id, name, false);
            }
        }
        return fang;
    }

    private WeaponRef defenderInInventory() {
        int[] inv = getInventorySnapshot();
        WeaponRef any = null;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isDragonDefender(id, name)) {
                return new WeaponRef(slot, id, name, false);
            }
            if (any == null && InventoryTracker.isDefender(id, name)) {
                any = new WeaponRef(slot, id, name, false);
            }
        }
        return any;
    }

    private boolean meleeWhipFangEquipped() {
        int[] eq = readAllEquipmentIds();
        if (eq == null) return false;
        for (int e : eq) {
            int id = decodeEquipId(e);
            if (id <= 0) continue;
            String name = resolveItemName(id);
            if (InventoryTracker.isWhip(id, name) || InventoryTracker.isFang(id, name)) return true;
        }
        return false;
    }

    private boolean defenderEquipped() {
        int[] eq = readAllEquipmentIds();
        if (eq == null) return false;
        for (int e : eq) {
            int id = decodeEquipId(e);
            if (id <= 0) continue;
            String name = resolveItemName(id);
            if (InventoryTracker.isDefender(id, name)) return true;
        }
        return false;
    }

    private boolean whipAndDefEquipped() {
        if (twoHanderStillEquipped()) return false;
        return meleeWhipFangEquipped() && defenderEquipped();
    }

    private boolean twoHanderStillEquipped() {
        WeaponRef axe = findGreataxe();
        if (axe != null && axe.equipped) return true;
        WeaponRef gmaul = findWeapon(true);
        return gmaul != null && gmaul.equipped;
    }

    /**
     * Gmaul finisher on tick+1..+3 after the greataxe — never same tick as the axe.
     * Decides off the LIVE post-hit HP: fires the moment the target survived inside
     * gmaul range, holds while the hitsplat is still in flight, and at 100% spec
     * keeps itself armed so a second gmaul chains the next tick.
     */
    public boolean tryDhGmaulFollow(int tick) {
        if (!pendingDhGmaulFollow) return false;
        if (tick <= lastDhAxeTick) return false;
        if (tick > lastDhAxeTick + 3 || !isInActivePvpFight()) {
            clearPendingDhGmaul();
            return false;
        }
        if (specEnergy >= 0 && specEnergy < 50) {
            clearPendingDhGmaul();
            return false;
        }
        int opp = liveTargetHp();
        if (opp <= 0) {
            // Dead, or the axe hitsplat has not registered yet — hold, re-check next tick.
            lastAction = "DH_GMAUL_HOLD@" + tick;
            return false;
        }
        int gmaulMax = MaxHitCalculator.gmaulSpecMaxHit(readMeleeStr());
        boolean doubleSpec = specEnergy >= 100;
        int finisher = doubleSpec ? gmaulMax * 2 : gmaulMax;
        if (finisher + 8 < opp) {
            if (tick - lastDhAxeTick <= 1) {
                // Tick+1 HP can still be the pre-axe value — wait for the splat before giving up.
                lastAction = "DH_GMAUL_HOLD@" + tick;
                return false;
            }
            clearPendingDhGmaul();
            return false;
        }
        pulseSpecOff();
        boolean ok = fireDhGmaulFollow();
        if (ok) {
            lastAction = "DH_GMAUL_FOLLOW@" + tick + " opp=" + opp;
            pendingDhWhipDef = true;
            if (doubleSpec && tick < lastDhAxeTick + 3) {
                // 100% spec: stay armed — if they live the roll, the second gmaul lands next tick.
                pendingDhGmaulOppHp = opp;
            } else {
                clearPendingDhGmaul();
            }
        }
        // Wield/click miss keeps it armed — retried next tick instead of dropping the kill.
        return ok;
    }

    public boolean hasPendingDhGmaul() { return pendingDhGmaulFollow; }

    public void clearPendingDhGmaul() {
        pendingDhGmaulFollow = false;
        pendingDhGmaulOppHp = -1;
        pendingDhGmaulDhHit = -1;
    }

    /** Gmaul after the greataxe — no whip restore, no spec unless gmaul is on. */
    public boolean fireDhGmaulFollow() {
        if (specEnergy >= 0 && specEnergy < 50) return false;
        if (!ensureGmaulEquipped()) return false;
        specAndAttack();
        lastHeadlessSpecTick = currentTick;
        return true;
    }

    public boolean isBaselineRestorePending() { return pendingWhipDef || pendingDhWhipDef; }

    public int findFoodSlotPublic() {
        int[] inv = getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = resolveItemName(id);
            if (InventoryTracker.isFood(id, name) && !InventoryTracker.isCloser(id, name)) return slot;
        }
        return -1;
    }

    /** True when we are mid DH attack animation or a DH kill-tick is armed. */
    public boolean isAttackCyclePublic() {
        return AnimationDb.isDharokAnimation(localAnim)
                || pendingKillMode == 0
                || lastDhAxeTick == currentTick;
    }

    public boolean isKillTickArmedPublic() { return pendingKillMode == 0; }

    // ════════════════════════════════════════════════════════════════════════
    //  Reflection helpers
    // ════════════════════════════════════════════════════════════════════════

    private boolean isLoggedIn() {
        if (loggedInField == null) return true;
        try {
            return loggedInField.getBoolean(null);
        } catch (Exception e) {
            return true;
        }
    }

    /** Cached reflective handle for Client.isInPvP(). */
    private Method isInPvPMethod;
    private boolean isInPvPMethodResolved;

    /** True when the client reports we are in a PvP (danger) zone. */
    public boolean isInPvP() {
        if (!isInPvPMethodResolved) {
            isInPvPMethodResolved = true;
            try {
                isInPvPMethod = clientInstance.getClass().getMethod("isInPvP");
            } catch (Exception ignored) {
                isInPvPMethod = null;
            }
        }
        if (isInPvPMethod != null) {
            try {
                Object r = isInPvPMethod.invoke(clientInstance);
                return r instanceof Boolean && (Boolean) r;
            } catch (Exception ignored) {}
        }
        // Fallback: static field.
        try {
            Field f = clientInstance.getClass().getField("isInPvP");
            Object v = f.get(clientInstance);
            return v instanceof Boolean && (Boolean) v;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Auto-enable Protect Item when in a danger zone. Fires the prayer once,
     * then throttles re-sends so it doesn't spam. Clears our tracking flag when
     * we leave the zone so it re-arms next time.
     */
    private void tryAutoProtectItem(int tick) {
        if (!autoProtectItemEnabled) return;
        boolean inZone = isInPvP();
        if (!inZone) {
            protectItemActive = false;
            return;
        }
        if (protectItemActive) return;
        // Throttle: at most one Protect Item attempt every 2 ticks while in zone.
        if (tick - lastProtectItemTick < 2) return;
        lastProtectItemTick = tick;
        if (prayer.activateProtectItem()) {
            protectItemActive = true;
            lastAction = "PROTECT_ITEM@" + tick;
        } else {
            lastAction = "PROTECT_ITEM_FAIL@" + tick;
        }
    }

    private static Field getStaticField(Class<?> cls, String name) {
        return Reflect.declaredField(cls, name);
    }

    private static Field getField(Class<?> cls, String name) {
        return Reflect.declaredField(cls, name);
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        return Reflect.method(cls, name, paramCount);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Package-private accessors for PrayerController
    // ════════════════════════════════════════════════════════════════════════

    Object client() { return clientInstance; }
    Method doActionMethod() { return doActionMethod; }
    Field interfaceCacheField() { return interfaceCacheField; }
    Method sendPrayerButtonMethod() { return sendPrayerButtonMethod; }
    Field bufferField() { return bufferField; }
    Method bufferCreateFrame() { return bufferCreateFrame; }
    Method bufferWriteUnsignedShort() { return bufferWriteUnsignedShort; }
    Method bufferWriteUnsignedByte() { return bufferWriteUnsignedByte; }
    int currentTick() { return currentTick; }

    String lastAction() { return lastAction; }
    void lastAction(String v) { lastAction = v; }

    int pendingProtectPrayer() { return pendingProtectPrayer; }
    void pendingProtectPrayer(int v) { pendingProtectPrayer = v; }

    int activeProtectPrayer() { return activeProtectPrayer; }
    void activeProtectPrayer(int v) { activeProtectPrayer = v; }

    int lastProtectSendTick() { return lastProtectSendTick; }
    void lastProtectSendTick(int v) { lastProtectSendTick = v; }

    int lastPrayerSwitchAnim() { return lastPrayerSwitchAnim; }
    void lastPrayerSwitchAnim(int v) { lastPrayerSwitchAnim = v; }

    void lastPrayerSwitchMs(long v) { lastPrayerSwitchMs = v; }

    String pendingNamedPrayer() { return pendingNamedPrayer; }
    void pendingNamedPrayer(String v) { pendingNamedPrayer = v; }

    int lastTargetAnim() { return lastTargetAnim; }
    int lastIncomingDmg() { return lastIncomingDmg; }

    Field myPlayerField() { return myPlayerField; }
    Method getInteractingMethod() { return getInteractingMethod; }
    Object stickyTarget() { return stickyTarget; }
    Object cachedTarget() { return cachedTarget; }
}
