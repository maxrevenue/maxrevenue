package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * In-game prayer orchestration: offensive prayers (Piety/Rigour/Augury/etc.),
 * protect-prayer switching, and the {@code ::prayer} / Advanced-Swapper
 * {@code p:} command surface.
 *
 * <p>Extracted from {@link CombatScript} to keep the fighting state machines
 * readable. Holds a back-reference to its {@link CombatScript} so packet
 * helpers, inventory/interface caches and the live {@code lastAction} /
 * {@code currentTick} telemetry stay in one place.
 */
public final class PrayerController {

    private final CombatScript script;

    // Def-prayer hysteresis: consecutive ticks the detector must agree before we
    // switch AWAY from a live overhead (kills tribrid flip-flop / prayer churn).
    private AnimationDb.AttackStyle defVoteStyle = AnimationDb.AttackStyle.UNKNOWN;
    private int defStyleVotes = 0;
    private int lastOffensiveSendTick = -10;
    private String lastOffensiveSent = null;

    /** Weapon must sit unchanged this many ticks before weapon-only prayer reads. */
    private static final int WEAPON_STABLE_TICKS = 2;
    /** Min gap between overhead swaps (server + misclick margin). */
    private static final int MIN_OVERHEAD_SWITCH_GAP = 1;
    /** OSRS accepts one overhead action per game tick (~600 ms). */
    private static final long MIN_PROTECT_SEND_GAP_MS = 540;
    private long lastProtectAttemptMs = 0;
    /** Votes to put up first overhead from bare. */
    private static final int BARE_OVERHEAD_VOTES = 1;
    /** Votes to swap one live overhead for another. */
    private static final int OVERHEAD_SWITCH_VOTES = 2;

    private Object lastDefTarget;
    private AnimationDb.AttackStyle lastRawWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
    private AnimationDb.AttackStyle prevRawWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
    private AnimationDb.AttackStyle stableWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
    private int weaponStableTicks = 0;
    private int lastWeaponChangeTick = -99;
    private AnimationDb.AttackStyle lastConfirmedHitStyle = AnimationDb.AttackStyle.UNKNOWN;
    private int lastConfirmedHitTick = -99;

    // ── (#2) Gear-corroborated switch detection ──────────────────────────────
    /**
     * Opponent loadout as of the previous tick, for detecting an armour change.
     * Only touched when {@link CombatScript#gearCorroboratedDefPrayer} is on.
     */
    private OpponentLoadout prevOpponentLoadout;
    /** Tick {@link #prevOpponentLoadout} was captured on, so corroboration only ever
     *  compares adjacent ticks and cannot fire across a gap in observation. */
    private int prevOpponentLoadoutTick = -99;
    /** Tick {@link #gearCorroboratedThisTick} was computed for, so repeat calls in
     *  one tick (the NH path calls the detector more than once) cannot double-advance it. */
    private int gearCorroborationTick = -99;
    /**
     * True when the opponent's armour changed on this tick, i.e. the weapon read
     * is a real switch rather than a weapon-only bait flick. Always false while the
     * flag is off, which is what keeps the flag-off path identical to the old one.
     */
    private boolean gearCorroboratedThisTick = false;

    public PrayerController(CombatScript script) {
        this.script = script;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Offensive prayers
    // ════════════════════════════════════════════════════════════════════════

    public void ensurePiety()       { ensureOffensivePrayer(AnimationDb.AttackStyle.MELEE); }
    public void ensureMysticMight() { ensureOffensivePrayer(AnimationDb.AttackStyle.MAGIC); }
    public void ensureEagleEye()    { ensureOffensivePrayer(AnimationDb.AttackStyle.RANGED); }

    public boolean isPietyActive()  { return isPrayerActive("PIETY"); }

    public void ensureOffensivePrayer(AnimationDb.AttackStyle style) {
        AnimationDb.AttackStyle resolved = style == null ? AnimationDb.AttackStyle.MELEE : style;
        if (resolved == AnimationDb.AttackStyle.MAGIC) {
            script.releaseIceBlockForMagePublic();
        } else if (resolved == AnimationDb.AttackStyle.MELEE
                || resolved == AnimationDb.AttackStyle.RANGED) {
            // Piety/Eagle while still on staff must not kill left-click Ice.
            if (!script.isMageStaffEquippedPublic()) {
                script.dropIceForMeleeOrRangePublic();
            }
        }
        if (anyOffensiveActive(resolved)) return;
        if (script.lastProtectSendTick() == script.currentTick()) return;
        String[] candidates = offensiveCandidates(resolved);
        String styleName = AnimationDb.offensivePrayerName(resolved);
        // 1) Already running a prayer of this style? Leave it alone.
        for (String enumName : candidates) {
            if (isPrayerDisabled(enumName)) continue;
            if (isPrayerActive(enumName)) return;
        }
        // 2) Enable the first prayer this book actually has (Eagle / Mystic
        //    before Rigour / Augury — tournament worlds lock the 99 prayers).
        for (String enumName : candidates) {
            if (isPrayerDisabled(enumName)) continue;
            if (enumName.equals(lastOffensiveSent)
                    && script.currentTick() - lastOffensiveSendTick <= 3) {
                return;
            }
            activateOffensivePrayer(enumName,
                    livePrayerId(enumName, AnimationDb.offensiveIdForEnum(enumName)),
                    AnimationDb.offensivePrayerLabel(enumName, styleName),
                    AnimationDb.offensiveWidgetForEnum(enumName));
            return;
        }
    }

    /** Tournament first: Eagle Eye / Mystic Might, then unlocked 99 prayers. */
    private static String[] offensiveCandidates(AnimationDb.AttackStyle style) {
        switch (style == null ? AnimationDb.AttackStyle.MELEE : style) {
            case MAGIC:
                return new String[] { "MYSTIC_MIGHT", "MYSTIC_VIGOUR", "AUGURY" };
            case RANGED:
                return new String[] { "EAGLE_EYE", "DEADEYE", "RIGOUR" };
            default:
                return new String[] { "PIETY", "CHIVALRY" };
        }
    }

    /** True when the server locked this prayer, or the live book does not have it. */
    private boolean isPrayerDisabled(String enumName) {
        if (enumName == null) return false;
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            Object prayer = p.getField(enumName).get(null);
            if (p.getField("isDisabledByServer").getBoolean(prayer)) return true;
            Object book = p.getField("currentPrayerBook").get(null);
            if (book instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) book;
                if (!list.isEmpty() && !list.contains(prayer)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Send one offensive prayer through the same robust ladder protect prayers
     * use: enum/packet send, frame-186 buffer, then the real PrayerButton click
     * (located by enum, not widget offset). Widget click is the last resort and
     * resolves the current book slot dynamically.
     */
    /** One-time + per-attempt diagnostics for the prayer debug file. */
    private static boolean prayEnvLogged = false;

    private String prayEnv() {
        StringBuilder b = new StringBuilder();
        try {
            Object helper = script.livePacketHelper();
            b.append("helper=").append(helper != null);
            b.append(" prayBtn=").append(script.sendPrayerButtonMethod() != null);
            if (helper != null) {
                try {
                    Object st = helper.getClass().getMethod("getStream").invoke(helper);
                    b.append(" stream=").append(st != null ? "ok" : "null");
                } catch (Exception e) { b.append(" stream=err"); }
            }
            java.lang.reflect.Field bf = script.bufferField();
            b.append(" bufField=").append(bf != null);
            if (bf != null) {
                try {
                    Object buf = bf.get(script.client());
                    b.append(" buf=").append(buf != null ? "ok" : "null");
                } catch (Exception e) { b.append(" buf=err"); }
            }
        } catch (Exception e) { b.append(" envErr=").append(e); }
        return b.toString();
    }

    private String prayState(String... enums) {
        StringBuilder b = new StringBuilder();
        for (String e : enums) {
            if (e == null) continue;
            b.append(' ').append(e).append("(d=").append(isPrayerDisabled(e) ? 1 : 0)
             .append(",a=").append(isPrayerActive(e) ? 1 : 0).append(')');
        }
        return b.toString();
    }

    private void schedulePrayerVerify(String enumName, String tag) {
        if (enumName == null) return;
        try {
            UiExecutor.schedule(() -> {
                try {
                    if (!isPrayerActive(enumName)) {
                        setPrayerActive(enumName, false);
                        FontManager.prayLog("VERIFY " + tag + " (" + enumName
                                + ") is OFF 0.8s after send -> server rejected/dropped");
                    } else {
                        FontManager.debug("[Prayer] " + tag + " (" + enumName + ") verified ON");
                    }
                } catch (Exception ignored) {}
            }, 800);
        } catch (Exception ignored) {}
    }

    private void logPrayEnvOnce() {
        if (prayEnvLogged) return;
        prayEnvLogged = true;
        FontManager.prayLog("ENV " + prayEnv());
        FontManager.prayLog("STATE magic" + prayState("MYSTIC_MIGHT", "MYSTIC_VIGOUR", "AUGURY")
                + " range" + prayState("RIGOUR", "EAGLE_EYE", "DEADEYE")
                + " melee" + prayState("PIETY", "CHIVALRY"));
    }

    private void activateOffensivePrayer(String enumName, int prayerId, String label, int staticWidget) {
        activateOffensivePrayer(enumName, prayerId, label, staticWidget, true);
    }

    /**
     * @param allowTab when false (mage spell / click-cast flow) the prayer is sent
     *                 ONLY over the packet/buffer/PrayerButton paths. Switching to
     *                 the prayer tab or widget-clicking would CANCEL the spell that
     *                 is currently armed, so neither happens — a failed send simply
     *                 retries on a later tick.
     */
    private void activateOffensivePrayer(String enumName, int prayerId, String label, int staticWidget,
                                         boolean allowTab) {
        logPrayEnvOnce();
        AnimationDb.AttackStyle offStyle = offensiveStyleOf(enumName);
        if (offStyle == AnimationDb.AttackStyle.MAGIC) {
            script.releaseIceBlockForMagePublic();
        } else if ((offStyle == AnimationDb.AttackStyle.MELEE
                || offStyle == AnimationDb.AttackStyle.RANGED)
                && !script.isMageStaffEquippedPublic()) {
            script.dropIceForMeleeOrRangePublic();
        }
        boolean sent = false;
        String path = "none";
        boolean spellArmed = script.isSpellArmedPublic();
        int widget = staticWidget > 0 ? staticWidget : AnimationDb.offensiveWidgetForEnum(enumName);

        // Tournament: click the real PrayerButton first when safe. With Ice armed,
        // widget/tab clicks cancel the selected spell — use packets only.
        if (!spellArmed) {
            if (enumName != null && (sent = invokePrayerButtonClick(enumName))) path = "prayerButton";
        }
        if (!sent && enumName != null && (sent = trySendPrayerEnumByName(enumName))) path = "enumSend";
        if (!sent && (sent = trySendPrayerPacket(prayerId))) path = "packet";
        if (!sent && (sent = trySendPrayerViaMap(prayerId))) path = "viaMap";
        if (!sent && (sent = sendPrayerBufferFallback(prayerId))) path = "buffer186";
        if (!sent && allowTab && !spellArmed && widget > 0) {
            ensurePrayerTab();
            clickOffensivePrayerWidget(widget, label != null ? label : "Prayer");
            sent = true;
            path = "click" + widget;
        }
        if (sent) {
            lastOffensiveSendTick = script.currentTick();
            lastOffensiveSent = enumName;
        }
        FontManager.prayLog("ON " + (enumName != null ? enumName : "?") + " id=" + prayerId
                + " path=" + path + " allowTab=" + allowTab + " spellArmed=" + spellArmed
                + " env[" + prayEnv() + "]"
                + " state" + prayState(enumName) + " -> activeNow=" + isPrayerActive(enumName));
        if (sent) schedulePrayerVerify(enumName, "ON " + enumName);
    }

    /**
     * Mage click-cast flow — never leaves the magic tab and never widget-clicks,
     * otherwise the armed Ice Barrage gets cancelled.
     */
    public void ensureMysticMightNoTab() {
        // Ensures SOME magic attack prayer is up. If the player already keeps
        // Augury / Mystic Might on, it is left untouched (no tug-of-war).
        ensureOffensivePrayerNoTab(AnimationDb.AttackStyle.MAGIC);
    }

    private void ensureOffensivePrayerNoTab(AnimationDb.AttackStyle style) {
        if (anyOffensiveActive(style)) return;
        if (script.lastProtectSendTick() == script.currentTick()) return;
        String[] candidates = offensiveCandidates(style);
        for (String enumName : candidates) {
            if (isPrayerDisabled(enumName)) continue;
            if (isPrayerActive(enumName)) return;
        }
        for (String enumName : candidates) {
            if (isPrayerDisabled(enumName)) continue;
            if (enumName.equals(lastOffensiveSent)
                    && script.currentTick() - lastOffensiveSendTick <= 3) {
                return;
            }
            activateOffensivePrayer(enumName,
                    livePrayerId(enumName, AnimationDb.offensiveIdForEnum(enumName)),
                    AnimationDb.offensivePrayerLabel(enumName, styleName(style)),
                    AnimationDb.offensiveWidgetForEnum(enumName), false);
            return;
        }
    }

    private String styleName(AnimationDb.AttackStyle style) {
        return AnimationDb.offensivePrayerName(style == null ? AnimationDb.AttackStyle.MELEE : style);
    }

    /** True when any prayer of this style is already lit (Augury counts as magic, etc.). */
    private boolean anyOffensiveActive(AnimationDb.AttackStyle style) {
        for (String enumName : offensiveCandidates(style)) {
            if (isPrayerDisabled(enumName)) continue;
            if (isPrayerActive(enumName)) return true;
        }
        return false;
    }

    public boolean isPrayerActive(String enumName) {
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            Object prayer = p.getField(enumName).get(null);
            return p.getField("isActive").getBoolean(prayer);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean trySendPrayerEnumByName(String enumName) {
        if (enumName == null) return false;
        Object helper = script.livePacketHelper();
        Method meth = script.sendPrayerButtonMethod();
        if (helper != null) {
            Method live = RtLookup.method(helper.getClass(), "sendPrayerButton", 1);
            if (live != null) meth = live;
        }
        if (helper == null || meth == null) return false;
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            Object prayer = p.getField(enumName).get(null);
            int id = (int) p.getMethod("getId").invoke(prayer);
            Method getStream = helper.getClass().getMethod("getStream");
            if (getStream.invoke(helper) == null) return false;
            meth.invoke(helper, id);
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    boolean trySendPrayerEnumByNamePublic(String enumName) {
        return trySendPrayerEnumByName(enumName);
    }

    private void clickOffensivePrayerWidget(int widget, String name) {
        clickPrayerWidget(widget, name);
    }

    /**
     * Click a prayer widget exactly like a human click: opcode 315
     * (WIDGET_TYPE_1), target = widget. This runs through the client's menu
     * action processing — the same reliable path gear swaps and spell selects
     * use — instead of writing frame-186 into the packet buffer from the agent
     * thread (which the game thread can clobber, dropping ~random prayers).
     */
    private void clickPrayerWidget(int widget, String name) {
        if (script.doActionMethod() == null || widget <= 0) return;
        if (name == null) name = "Prayer";
        // Shapes observed on real human clicks: (param0=-1, opcode 315, cmd=-1).
        int[][] tries = {
                { -1, 315, -1 },
                { 0, 315, widget },
                { 0, 315, -1 },
                { 0, 0, -1 },
                { 0, -1, -1 },
        };
        boolean any = false;
        for (int[] t : tries) {
            try {
                script.doActionMethod().invoke(script.client(),
                        0, t[0], widget, t[1], t[2], 0, "Activate", name, -1, -1);
                any = true;
                break;
            } catch (Exception ignored) {}
        }
        if (any) {
            try { script.sendClickingButton(widget); } catch (Exception ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Protect prayers (Z/X/C hotkeys + auto-def)
    // ════════════════════════════════════════════════════════════════════════

    public void triggerProtectMagic()  { fireProtectNow(AnimationDb.PROTECT_MAGIC_PRAYER_ID); }
    public void triggerProtectRange()  { fireProtectNow(AnimationDb.PROTECT_RANGE_PRAYER_ID); }
    public void triggerProtectMelee()  { fireProtectNow(AnimationDb.PROTECT_MELEE_PRAYER_ID); }

    void fireProtectNow(int prayerId) {
        script.lastAction("PROT_NOW_" + prayerId + "@" + script.currentTick());
        ClientThreadGuard.get().invokeLater(() ->
                activateProtectPrayer(prayerId, AnimationDb.protectPrayerName(prayerId), true));
    }

    public void queueProtectPrayer(int prayerId) {
        script.pendingProtectPrayer(prayerId);
        script.lastAction("PROT_Q_" + prayerId + "@" + script.currentTick());
    }

    public synchronized void activateProtectPrayer(int prayerId, String label) {
        activateProtectPrayer(prayerId, label, false);
    }

    private synchronized void activateProtectPrayer(int prayerId, String label, boolean manualHotkey) {
        logPrayEnvOnce();
        String enumName = AnimationDb.protectPrayerEnumName(prayerId);
        int liveId = livePrayerId(enumName, prayerId);
        int tick = script.currentTick();
        long now = System.currentTimeMillis();

        if (enumName != null && isPrayerActive(enumName)) {
            script.activeProtectPrayer(prayerId);
            script.pendingProtectPrayer(-1);
            return;
        }

        if (!manualHotkey && lastProtectAttemptMs > 0
                && (now - lastProtectAttemptMs) < MIN_PROTECT_SEND_GAP_MS) {
            if (script.activeProtectPrayer() == prayerId) {
                script.pendingProtectPrayer(-1);
                return;
            }
            script.pendingProtectPrayer(prayerId);
            script.lastAction("PROT_COOLDOWN_" + prayerId + "@" + tick);
            return;
        }

        if (script.lastProtectSendTick() == tick) {
            if (script.activeProtectPrayer() == prayerId) {
                script.pendingProtectPrayer(-1);
                return;
            }
            script.pendingProtectPrayer(prayerId);
            script.lastAction("PROT_HOLD_" + prayerId + "@" + tick);
            FontManager.prayLog("PROT HOLD " + label + " id=" + prayerId
                    + " (already sent this tick)");
            return;
        }

        // Claim tick + wall-clock slot before sending so bursty tick reads cannot spam.
        script.lastProtectSendTick(tick);
        lastProtectAttemptMs = now;

        boolean sent = false;
        boolean spellArmed = script.isSpellArmedPublic();

        // Spell armed: packet paths only — PrayerButton / tab switch cancels Ice.
        if (spellArmed) {
            if (enumName != null) sent = trySendPrayerEnumByName(enumName);
            if (!sent) sent = trySendPrayerPacket(liveId);
            if (!sent) sent = trySendPrayerViaMap(liveId);
            if (!sent) sent = sendPrayerBufferFallback(liveId);
        } else {
            if (enumName != null) sent = invokePrayerButtonClick(enumName);
            if (!sent && enumName != null) sent = trySendPrayerEnumByName(enumName);
            if (!sent) sent = trySendPrayerPacket(liveId);
            if (!sent) sent = trySendPrayerViaMap(liveId);
            if (!sent) sent = sendPrayerBufferFallback(liveId);
            if (!sent) {
                ensurePrayerTab();
                sent = clickProtectWidgetStatic(prayerId);
            }
        }

        if (sent) {
            script.pendingProtectPrayer(-1);
            markProtectActivatedPublic(prayerId, label, now);
            FontManager.log("[CombatScript] Protect ON " + label + " id=" + liveId);
            FontManager.prayLog("PROT ON " + label + " id=" + prayerId + " liveId=" + liveId
                    + " env[" + prayEnv() + "] state" + prayState(enumName));
            schedulePrayerVerify(enumName, "PROT " + label);
            return;
        }
        script.lastProtectSendTick(tick - 1);
        lastProtectAttemptMs = 0;
        script.lastAction("PROT_FAIL_" + prayerId + "@" + script.currentTick());
        FontManager.log("[CombatScript] Protect prayer failed id=" + liveId + " (" + label + ")");
        FontManager.prayLog("PROT FAIL id=" + prayerId + " (" + label + ") env[" + prayEnv() + "]");
    }

    boolean invokePrayerButtonClick(String enumName) {
        if (enumName == null || script.interfaceCacheField() == null) return false;
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) return false;
            Object want = p.getField(enumName).get(null);
            if (want == null) return false;
            if (p.getField("isActive").getBoolean(want)) return true;

            Object cacheObj = Modifier.isStatic(script.interfaceCacheField().getModifiers())
                    ? script.interfaceCacheField().get(null) : script.interfaceCacheField().get(script.client());
            if (!(cacheObj instanceof Object[])) return false;
            Object[] cache = (Object[]) cacheObj;
            Class<?> btnCls = Class.forName("com.roatpkz.client.game.cache.graphics.types.PrayerButton");
            Field prayerField = btnCls.getDeclaredField("currentPrayer");
            prayerField.setAccessible(true);
            Method click = btnCls.getMethod("onButtonClick");
            for (int id = 50351; id < 50351 + 40 && id < cache.length; id++) {
                Object iface = cache[id];
                if (iface == null || !btnCls.isInstance(iface)) continue;
                Object cur = prayerField.get(iface);
                if (cur != want) continue;
                Object ok = click.invoke(iface);
                return ok instanceof Boolean ? (Boolean) ok : true;
            }
            for (Object iface : cache) {
                if (iface == null || !btnCls.isInstance(iface)) continue;
                Object cur = prayerField.get(iface);
                if (cur != want) continue;
                Object ok = click.invoke(iface);
                return ok instanceof Boolean ? (Boolean) ok : true;
            }
        } catch (Exception e) {
            FontManager.debug("[CombatScript] PrayerButton click: " + e.getMessage());
        }
        return false;
    }

    int livePrayerId(String enumName, int fallback) {
        if (enumName == null) return fallback;
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) return fallback;
            Object prayer = p.getField(enumName).get(null);
            return (int) p.getMethod("getId").invoke(prayer);
        } catch (Exception ignored) {}
        return fallback;
    }

    public void setPrayerActive(String enumName, boolean on) {
        if (enumName == null) return;
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) return;
            Object prayer = p.getField(enumName).get(null);
            p.getField("isActive").setBoolean(prayer, on);
        } catch (Exception ignored) {}
    }

    void deactivateOtherProtectOverheads(String keepEnum) {
        for (String n : new String[] {
                "PROTECT_FROM_MAGIC", "PROTECT_FROM_MISSILES", "PROTECT_FROM_MELEE" }) {
            if (keepEnum != null && keepEnum.equals(n)) continue;
            setPrayerActive(n, false);
        }
    }

    void markProtectActivatedPublic(int prayerId, String label, long now) {
        script.activeProtectPrayer(prayerId);
        script.lastPrayerSwitchMs(now);
        script.lastAction(label.replace(' ', '_').toUpperCase() + "@" + script.currentTick());
    }

    boolean trySendPrayerPacket(int prayerId) {
        Object helper = script.livePacketHelper();
        Method meth = script.sendPrayerButtonMethod();
        if (helper != null) {
            Method live = RtLookup.method(helper.getClass(), "sendPrayerButton", 1);
            if (live != null) meth = live;
        }
        if (helper == null || meth == null) return false;
        try {
            Method getStream = helper.getClass().getMethod("getStream");
            if (getStream.invoke(helper) == null) return false;
            meth.invoke(helper, prayerId);
            return true;
        } catch (Exception e) {
            FontManager.debug("[CombatScript] sendPrayerButton(" + prayerId + ") " + e.getMessage());
            return false;
        }
    }

    boolean trySendPrayerViaMap(int prayerId) {
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            // Roat Prayer exposes prayerIdToPrayer (HashMap<Integer,Prayer>),
            // not getPrayerWithId(). Resolve the enum from the map, then send.
            Object map = p.getField("prayerIdToPrayer").get(null);
            Object prayer = map.getClass().getMethod("get", Object.class).invoke(map, prayerId);
            if (prayer == null) return false;
            int id = (int) p.getMethod("getId").invoke(prayer);
            return trySendPrayerPacket(id);
        } catch (Exception ignored) {}
        return false;
    }

    int resolvePrayerWidgetId(int prayerId) {
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            Object map = p.getField("prayerIdToPrayer").get(null);
            Object prayer = map.getClass().getMethod("get", Object.class).invoke(map, prayerId);
            if (prayer != null) {
                Object book = p.getField("currentPrayerBook").get(null);
                if (book instanceof java.util.List) {
                    int idx = ((java.util.List<?>) book).indexOf(prayer);
                    if (idx >= 0) return 50351 + idx;
                }
                Object[] base = (Object[]) p.getField("BASE_PRAYER_BOOK").get(null);
                for (int i = 0; i < base.length; i++) {
                    if (base[i] == prayer) return 50351 + i;
                }
            }
        } catch (Exception ignored) {}
        return AnimationDb.counterPrayerWidget(prayerIdToStyle(prayerId));
    }

    /** Click a protect prayer at its BASE book slot (buttons are laid out there at load). */
    boolean clickProtectWidgetStatic(int prayerId) {
        if (script.doActionMethod() == null) return false;
        int widget = AnimationDb.counterPrayerWidget(prayerIdToStyle(prayerId));
        if (widget <= 0) return false;
        for (String name : AnimationDb.protectPrayerNameVariants(prayerId)) {
            clickPrayerWidget(widget, name);
        }
        return true;
    }

    boolean sendPrayerBufferFallback(int prayerId) {
        Field bufferField = script.bufferField();
        if (bufferField == null) return false;
        try {
            Object buf = bufferField.get(script.client());
            if (buf == null) return false;
            script.ensureBufferWriters(buf);
            Method createFrame = script.bufferCreateFrame();
            if (createFrame == null) return false;
            createFrame.invoke(buf, 186);
            Method byteWriter = script.bufferWriteUnsignedByte();
            Method shortWriter = script.bufferWriteUnsignedShort();
            if (byteWriter != null) {
                byteWriter.invoke(buf, prayerId);
            } else {
                shortWriter.invoke(buf, prayerId);
            }
            return true;
        } catch (Exception e) {
            FontManager.debug("[CombatScript] prayer buffer fallback: " + e.getMessage());
            return false;
        }
    }

    static AnimationDb.AttackStyle prayerIdToStyle(int id) {
        if (id == AnimationDb.PROTECT_MAGIC_PRAYER_ID) return AnimationDb.AttackStyle.MAGIC;
        if (id == AnimationDb.PROTECT_RANGE_PRAYER_ID) return AnimationDb.AttackStyle.RANGED;
        return AnimationDb.AttackStyle.MELEE;
    }

    public void ensureProtectFromStyle(AnimationDb.AttackStyle style) {
        if (style == null || style == AnimationDb.AttackStyle.UNKNOWN) return;
        queueProtectPrayer(AnimationDb.protectPrayerId(style));
    }

    /** Switch protect when the target's attack style changes or they hit us. */
    public void runAutoDefPrayer(int tick) {
        if (script.lastProtectSendTick() == tick) return;

        Object target = script.resolveDefTarget();
        if (target == null) {
            if (lastDefTarget != null) {
                lastDefTarget = null;
                resetWeaponBelief();
            }
            return;
        }

        AnimationDb.AttackStyle style = detectDefPrayStyle(target, tick);
        if (style == null || style == AnimationDb.AttackStyle.UNKNOWN) {
            return;
        }

        int needId = AnimationDb.protectPrayerId(style);
        String enumName = AnimationDb.protectPrayerEnumName(needId);
        if (enumName == null) return;

        if (isPrayerActive(enumName)) {
            defStyleVotes = 0;
            defVoteStyle = AnimationDb.AttackStyle.UNKNOWN;
            script.activeProtectPrayer(needId);
            script.lastPrayerSwitchAnim(script.lastTargetAnim());
            script.pendingProtectPrayer(-1);
            return;
        }

        int liveProtect = liveProtectPrayerId();
        boolean overheadUp = liveProtect >= 0;
        boolean switching = overheadUp && liveProtect != needId;
        boolean bare = !overheadUp;
        // (#2) Corroborated gear switches skip the stability wait, same as the
        // detector. False while the flag is off, so these two gates are unchanged.
        boolean weaponTrusted = weaponStableTicks >= WEAPON_STABLE_TICKS
                || gearCorroboratedThisTick;

        int anim = script.lastTargetAnim();
        AnimationDb.AttackStyle animStyle = animToStyle(anim);
        boolean urgentSwing = animStyle == style
                && (AnimationDb.isStrongDefAnim(anim)
                || AnimationDb.isCombatAttackAnimation(anim));
        if (urgentSwing) {
            defStyleVotes = 0;
            defVoteStyle = AnimationDb.AttackStyle.UNKNOWN;
            commitDefPrayer(tick, needId, style);
            return;
        }

        // Mage staff equip is the one weapon read we trust immediately: a staff
        // or wand can only cast (bash anim 393 is handled by the urgent-swing
        // path above), so protect-magic goes up the same tick the staff appears.
        if (style == AnimationDb.AttackStyle.MAGIC
                && lastRawWeaponStyle == AnimationDb.AttackStyle.MAGIC
                && !AnimationDb.isStaffBash(anim)) {
            defStyleVotes = 0;
            defVoteStyle = AnimationDb.AttackStyle.UNKNOWN;
            FontManager.prayLog("DEF_FAST_MAGE staff equip@" + tick
                    + " anim=" + anim + " style=" + style);
            commitDefPrayer(tick, needId, style);
            return;
        }

        // 1-tick weapon bait: flicked to a new style but hasn't sat for 2 ticks
        // and they aren't mid-swing — keep the overhead we already have.
        if (switching && !weaponTrusted) {
            AnimationDb.AttackStyle held = prayerIdToStyle(liveProtect);
            if (held != style && lastRawWeaponStyle != AnimationDb.AttackStyle.UNKNOWN
                    && lastRawWeaponStyle != held) {
                script.lastAction("DEF_HOLD@" + tick + " flick=" + lastRawWeaponStyle
                        + " keep=" + held);
                return;
            }
        }

        if (defVoteStyle == style) {
            defStyleVotes++;
        } else {
            defVoteStyle = style;
            defStyleVotes = 1;
        }

        int needVotes;
        if (bare) {
            needVotes = 1;
        } else if (switching) {
            // A weapon that has sat unchanged for 2 ticks is a real switch, not
            // a 1-tick bait — commit on the first vote instead of waiting 3.
            needVotes = weaponTrusted ? 1 : OVERHEAD_SWITCH_VOTES;
            if (tick - script.lastProtectSendTick() < MIN_OVERHEAD_SWITCH_GAP) {
                return;
            }
        } else {
            needVotes = 1;
        }

        if (defStyleVotes >= needVotes) {
            commitDefPrayer(tick, needId, style);
            defStyleVotes = 0;
            defVoteStyle = AnimationDb.AttackStyle.UNKNOWN;
        } else {
            script.lastAction("DEF_VOTE@" + tick + " " + style + "=" + defStyleVotes
                    + "/" + needVotes + " wpn=" + lastRawWeaponStyle
                    + " stable=" + weaponStableTicks);
        }
    }

    private int liveProtectPrayerId() {
        if (isPrayerActive("PROTECT_FROM_MAGIC")) return AnimationDb.PROTECT_MAGIC_PRAYER_ID;
        if (isPrayerActive("PROTECT_FROM_MISSILES")) return AnimationDb.PROTECT_RANGE_PRAYER_ID;
        if (isPrayerActive("PROTECT_FROM_MELEE")) return AnimationDb.PROTECT_MELEE_PRAYER_ID;
        return script.activeProtectPrayer();
    }

    private void commitDefPrayer(int tick, int needId, AnimationDb.AttackStyle style) {
        script.lastPrayerSwitchAnim(script.lastTargetAnim());
        activateProtectPrayer(needId, AnimationDb.protectPrayerName(needId));
    }

    /**
     * Resolve which style to protect against. Returns a style every tick when
     * their weapon is readable; {@link #runAutoDefPrayer} applies anti-bait votes.
     */
    public AnimationDb.AttackStyle detectDefPrayStyle() {
        Object target = script.resolveDefTarget();
        if (target == null) return AnimationDb.AttackStyle.UNKNOWN;
        return detectDefPrayStyle(target, script.currentTick());
    }

    private AnimationDb.AttackStyle detectDefPrayStyle(Object target, int tick) {
        ensureDefTarget(target);

        AnimationDb.AttackStyle rawWeapon = script.targetWeaponStyle(target);
        tickWeaponStability(tick, rawWeapon);
        updateGearCorroboration(tick);

        int anim = script.lastTargetAnim();
        AnimationDb.AttackStyle animStyle = animToStyle(anim);
        boolean combatAnim = AnimationDb.isCombatAttackAnimation(anim);
        int liveProtect = liveProtectPrayerId();
        AnimationDb.AttackStyle held = liveProtect >= 0 ? prayerIdToStyle(liveProtect)
                : AnimationDb.AttackStyle.UNKNOWN;

        // A corroborated gear switch is trusted without the 2-tick stability wait.
        // This can only ever SHORTEN the wait: the first clause already covers every
        // read that was stable, and gearCorroboratedThisTick is false while the flag
        // is off — so the animation paths below behave exactly as before.
        boolean weaponTrusted = weaponStableTicks >= WEAPON_STABLE_TICKS
                || gearCorroboratedThisTick;

        if (script.isFreshIncomingHit() && animStyle != AnimationDb.AttackStyle.UNKNOWN) {
            noteConfirmedStyle(tick, animStyle);
            return trace("hit", animStyle);
        }

        // Mid-swing always beats equipment (real attack style this tick).
        if (combatAnim && animStyle != AnimationDb.AttackStyle.UNKNOWN) {
            return trace("anim", animStyle);
        }

        // Mage staff equip is the one weapon read we trust immediately: a staff
        // or wand can only cast (bash anim 393 is handled above), so protect-magic
        // goes up the same tick the staff appears — no 2-tick stability wait.
        if (rawWeapon == AnimationDb.AttackStyle.MAGIC
                && !AnimationDb.isStaffBash(anim)) {
            return trace("gear-mage", AnimationDb.AttackStyle.MAGIC);
        }

        if (weaponTrusted && rawWeapon != AnimationDb.AttackStyle.UNKNOWN) {
            if (gearCorroboratedThisTick && weaponStableTicks < WEAPON_STABLE_TICKS) {
                FontManager.prayLog("DEF_GEAR_CORR@" + tick + " armour changed -> " + rawWeapon
                        + " (skipped " + WEAPON_STABLE_TICKS + "-tick wait, w="
                        + script.opponentLoadout().weaponId() + ")");
                return trace("gear-corr", rawWeapon);
            }
            return trace("gear-stable", rawWeapon);
        }

        // Unstable weapon that disagrees with live overhead = likely 1-tick bait.
        if (held != AnimationDb.AttackStyle.UNKNOWN && rawWeapon != AnimationDb.AttackStyle.UNKNOWN
                && rawWeapon != held && !weaponTrusted) {
            return trace("bait-hold", held);
        }

        if (tick - lastConfirmedHitTick <= 6
                && lastConfirmedHitStyle != AnimationDb.AttackStyle.UNKNOWN) {
            return trace("hit-mem", lastConfirmedHitStyle);
        }

        if (rawWeapon != AnimationDb.AttackStyle.UNKNOWN) {
            return trace("raw", rawWeapon);
        }

        if (held != AnimationDb.AttackStyle.UNKNOWN) {
            return trace("held", held);
        }

        return trace("none", AnimationDb.AttackStyle.UNKNOWN);
    }

    /** Records the branch taken and returns the style, keeping the returns readable. */
    private AnimationDb.AttackStyle trace(String branch, AnimationDb.AttackStyle style) {
        script.defPrayTrace = branch;
        return style;
    }

    /**
     * (#2) Sets {@link #gearCorroboratedThisTick} from the opponent loadout captured
     * for this tick — true when a non-weapon slot changed since the previous tick.
     *
     * <p>Computes at most once per tick, because the detector runs more than once
     * per tick on the NH path and the comparison advances
     * {@link #prevOpponentLoadout}. Does nothing at all when the flag is off, so the
     * position and ordering of the old weapon-stability logic is unchanged.
     */
    private void updateGearCorroboration(int tick) {
        if (!script.gearCorroboratedDefPrayer) {
            gearCorroboratedThisTick = false;
            return;
        }
        if (tick == gearCorroborationTick) return;
        gearCorroborationTick = tick;

        OpponentLoadout now = script.opponentLoadout();
        OpponentLoadout prev = prevOpponentLoadout;
        int prevTick = prevOpponentLoadoutTick;
        prevOpponentLoadout = now;
        prevOpponentLoadoutTick = tick;
        // Only adjacent ticks: after a gap (overheads toggled off, target reacquired)
        // a difference is not evidence of a switch happening *now*.
        if (now == null || now.isEmpty() || prev == null || tick - prevTick != 1) {
            gearCorroboratedThisTick = false;
            return;
        }
        int slots = Math.max(prev.slotCount(), now.slotCount());
        for (int slot = 0; slot < slots; slot++) {
            if (slot == OpponentLoadout.SLOT_WEAPON) continue;   // the thing being corroborated
            if (prev.itemId(slot) != now.itemId(slot)) {
                gearCorroboratedThisTick = true;
                return;
            }
        }
        gearCorroboratedThisTick = false;
    }

    private void ensureDefTarget(Object target) {
        if (target == lastDefTarget) return;
        lastDefTarget = target;
        resetWeaponBelief();
    }

    private void resetWeaponBelief() {
        lastRawWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
        prevRawWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
        stableWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
        weaponStableTicks = 0;
        lastWeaponChangeTick = -99;
        lastConfirmedHitStyle = AnimationDb.AttackStyle.UNKNOWN;
        lastConfirmedHitTick = -99;
        defStyleVotes = 0;
        defVoteStyle = AnimationDb.AttackStyle.UNKNOWN;
        prevOpponentLoadout = null;
        prevOpponentLoadoutTick = -99;
        gearCorroborationTick = -99;
        gearCorroboratedThisTick = false;
    }

    private void tickWeaponStability(int tick, AnimationDb.AttackStyle raw) {
        lastRawWeaponStyle = raw;
        if (raw == AnimationDb.AttackStyle.UNKNOWN) {
            weaponStableTicks = 0;
            prevRawWeaponStyle = AnimationDb.AttackStyle.UNKNOWN;
            return;
        }
        if (raw == prevRawWeaponStyle) {
            weaponStableTicks++;
        } else {
            weaponStableTicks = 1;
            lastWeaponChangeTick = tick;
        }
        prevRawWeaponStyle = raw;
        if (weaponStableTicks >= WEAPON_STABLE_TICKS) {
            stableWeaponStyle = raw;
        }
    }

    private void noteConfirmedStyle(int tick, AnimationDb.AttackStyle style) {
        lastConfirmedHitStyle = style;
        lastConfirmedHitTick = tick;
        stableWeaponStyle = style;
        prevRawWeaponStyle = style;
        lastRawWeaponStyle = style;
        weaponStableTicks = WEAPON_STABLE_TICKS;
    }

    private static AnimationDb.AttackStyle animToStyle(int anim) {
        if (anim <= 0 || AnimationDb.isConsumeAnimation(anim)) {
            return AnimationDb.AttackStyle.UNKNOWN;
        }
        if (AnimationDb.isIceCast(anim)) {
            return AnimationDb.AttackStyle.MAGIC;
        }
        AnimationDb.AnimInfo info = AnimationDb.lookup(anim);
        if (info != null && info.style != AnimationDb.AttackStyle.UNKNOWN) {
            return info.style;
        }
        return AnimationDb.AttackStyle.UNKNOWN;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Named prayers (Advanced Swapper p:) + id/name mapping
    // ════════════════════════════════════════════════════════════════════════

    /** Activate prayer by friendly name (piety, protect item, protect from melee, …). */
    public boolean activatePrayerNamed(String prayerName) {
        if (prayerName == null || prayerName.trim().isEmpty()) return false;
        script.pendingNamedPrayer(prayerName.trim());
        script.lastAction("PRAY_Q@" + script.currentTick());
        FontManager.log("[Swapper] queue prayer: " + script.pendingNamedPrayer());
        return true;
    }

    public boolean fireNamedPrayer(String prayerName) {
        int id = resolvePrayerIdByName(prayerName);
        Object enumObj = resolvePrayerEnum(prayerName, id);
        if (enumObj != null) {
            try { id = (int) enumObj.getClass().getMethod("getId").invoke(enumObj); }
            catch (Exception ignored) {}
        }
        if (id < 0 && enumObj == null) {
            FontManager.log("[Swapper] unknown prayer: " + prayerName);
            return false;
        }
        if (id == AnimationDb.PROTECT_MAGIC_PRAYER_ID
                || id == AnimationDb.PROTECT_RANGE_PRAYER_ID
                || id == AnimationDb.PROTECT_MELEE_PRAYER_ID) {
            activateProtectPrayer(id, AnimationDb.protectPrayerName(id));
            return true;
        }
        String enumName = prayerEnumName(id);

        // Offensive prayers (piety/rigour/eagle/mystic/augury/chivalry/...) go
        // through the SAME tuned activation as the auto-prayer: skips disabled
        // prayers (falls back to the account's real variant), sends once through
        // the packet/buffer ladder, and verifies the server keeps it on.
        AnimationDb.AttackStyle offStyle = offensiveStyleOf(enumName);
        if (offStyle != AnimationDb.AttackStyle.UNKNOWN) {
            FontManager.log("[Swapper] prayer -> " + enumName + " (style "
                    + offStyle + ")");
            ensureOffensivePrayer(offStyle);
            script.lastAction("PRAY_" + enumName + "@" + script.currentTick());
            return true;
        }

        if (enumName != null && isPrayerActive(enumName)) {
            script.lastAction("PRAY_ON@" + script.currentTick());
            return true;
        }
        boolean sent = false;
        if (enumName != null) sent |= trySendPrayerEnumByName(enumName);
        if (!sent && id >= 0) sent |= trySendPrayerPacket(id);
        if (!sent && id >= 0) sent |= trySendPrayerViaMap(id);
        if (!sent && id >= 0) sent |= sendPrayerBufferFallback(id);
        if (!sent && enumName != null) sent |= invokePrayerButtonClick(enumName);
        if (!sent) ensurePrayerTab();
        if (enumObj != null) {
            try {
                String display = enumObj.getClass().getMethod("getName").invoke(enumObj).toString();
                int widget = id >= 0 ? resolvePrayerWidgetId(id) : AnimationDb.AUGURY_WIDGET;
                clickOffensivePrayerWidget(widget, display);
                if (widget > 0) script.sendClickingButton(widget);
                setPrayerActive(enumName, true);
            } catch (Exception ignored) {
                setPrayerActive(enumName, true);
            }
        } else {
            clickOffensivePrayerWidget(AnimationDb.AUGURY_WIDGET, prayerName);
            setPrayerActive(enumName, true);
        }
        script.lastAction((sent || isPrayerActive(enumName) ? "PRAY_" : "PRAY_TRY_")
                + (enumName != null ? enumName : prayerName) + "@" + script.currentTick());
        FontManager.log("[Swapper] prayer " + prayerName + " id=" + id + " sent=" + sent
                + " active=" + isPrayerActive(enumName));
        return sent || isPrayerActive(enumName) || enumObj != null;
    }

    /**
     * Turn on Protect Item (prayer id 11) once. Returns true if the prayer is
     * now active. Uses the same send paths as {@link #fireNamedPrayer} but with
     * no {@code lastAction} spam — intended for the auto-danger-zone loop.
     */
    public boolean activateProtectItem() {
        if (isPrayerActive("PROTECT_ITEM")) return true;
        boolean sent = false;
        sent |= trySendPrayerEnumByName("PROTECT_ITEM");
        if (!sent) sent |= trySendPrayerPacket(AnimationDb.PROTECT_ITEM_PRAYER_ID);
        if (!sent) sent |= trySendPrayerViaMap(AnimationDb.PROTECT_ITEM_PRAYER_ID);
        if (!sent) sent |= sendPrayerBufferFallback(AnimationDb.PROTECT_ITEM_PRAYER_ID);
        if (!sent) {
            int widget = resolvePrayerWidgetId(AnimationDb.PROTECT_ITEM_PRAYER_ID);
            if (widget <= 0) widget = 50361; // PROTECT_ITEM at index 10 of the base book
            ensurePrayerTab();
            clickOffensivePrayerWidget(widget, "Protect Item");
        }
        if (sent || isPrayerActive("PROTECT_ITEM")) {
            setPrayerActive("PROTECT_ITEM", true);
            return true;
        }
        return false;
    }

    private static AnimationDb.AttackStyle offensiveStyleOf(String enumName) {
        if (enumName == null) return AnimationDb.AttackStyle.UNKNOWN;
        switch (enumName) {
            case "PIETY": case "CHIVALRY": return AnimationDb.AttackStyle.MELEE;
            case "RIGOUR": case "EAGLE_EYE": case "DEADEYE": return AnimationDb.AttackStyle.RANGED;
            case "AUGURY": case "MYSTIC_MIGHT": case "MYSTIC_VIGOUR": return AnimationDb.AttackStyle.MAGIC;
            default: return AnimationDb.AttackStyle.UNKNOWN;
        }
    }

    private Object resolvePrayerEnum(String prayerName, int fallbackId) {
        return doResolvePrayerEnum(prayerName, fallbackId);
    }

    Object resolvePrayerEnumPublic(String prayerName, int fallbackId) {
        return doResolvePrayerEnum(prayerName, fallbackId);
    }

    private Object doResolvePrayerEnum(String prayerName, int fallbackId) {
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) return null;
            String enumName = fallbackId >= 0 ? prayerEnumName(fallbackId) : null;
            if (enumName == null) {
                String n = prayerName.toLowerCase().replaceAll("[^a-z0-9]", "");
                if (n.equals("augury")) enumName = "AUGURY";
                else if (n.equals("piety")) enumName = "PIETY";
                else if (n.equals("rigour") || n.equals("rigor")) enumName = "RIGOUR";
                else if (n.equals("mysticmight")) enumName = "MYSTIC_MIGHT";
                else if (n.equals("mysticvigour") || n.equals("mysticvigor")) enumName = "MYSTIC_VIGOUR";
                else if (n.equals("eagleeye")) enumName = "EAGLE_EYE";
                else if (n.equals("deadeye")) enumName = "DEADEYE";
                else if (n.equals("chivalry")) enumName = "CHIVALRY";
            }
            if (enumName != null) {
                try { return p.getField(enumName).get(null); } catch (Exception ignored) {}
            }
            if (fallbackId >= 0) {
                return p.getMethod("getPrayerWithId", int.class).invoke(null, fallbackId);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Best-effort: turn off piety/rigour/augury/mystic/eagle via toggle packet. */
    public boolean disableOffensivePrayers() {
        int[] ids = {
                AnimationDb.PIETY_PRAYER_ID, AnimationDb.RIGOUR_PRAYER_ID,
                AnimationDb.AUGURY_PRAYER_ID, AnimationDb.MYSTIC_MIGHT_PRAYER_ID,
                AnimationDb.EAGLE_EYE_PRAYER_ID, AnimationDb.CHIVALRY_PRAYER_ID,
                AnimationDb.DEADEYE_PRAYER_ID, AnimationDb.MYSTIC_VIGOUR_PRAYER_ID
        };
        boolean any = false;
        for (int id : ids) {
            String enumName = prayerEnumName(id);
            if (enumName != null && isPrayerActive(enumName)) {
                if (trySendPrayerPacket(id)) any = true;
            }
        }
        return any;
    }

    /**
     * Turn a named prayer off. Frame 186 is a toggle — only send if it is on,
     * otherwise {@code p:disable:piety} would turn piety <em>on</em>.
     */
    public boolean disablePrayerNamed(String prayerName) {
        if (prayerName == null || prayerName.trim().isEmpty()) return false;
        int id = resolvePrayerIdByName(prayerName);
        Object enumObj = doResolvePrayerEnum(prayerName, id);
        if (enumObj != null) {
            try { id = (int) enumObj.getClass().getMethod("getId").invoke(enumObj); }
            catch (Exception ignored) {}
        }
        String enumName = id >= 0 ? prayerEnumName(id) : null;
        if (enumName != null && !isPrayerActive(enumName)) return true;
        if (id < 0) return false;
        return trySendPrayerPacket(id);
    }

    public static int resolvePrayerIdByName(String raw) {
        if (raw == null) return -1;
        String n = raw.toLowerCase().replaceAll("[^a-z0-9]", "");
        switch (n) {
            case "protectitem": case "protitem": case "pi":
                return AnimationDb.PROTECT_ITEM_PRAYER_ID;
            case "protectfrommagic": case "protectmagic": case "protmagic":
            case "protmage": case "mage": case "magic": case "praymage":
                return AnimationDb.PROTECT_MAGIC_PRAYER_ID;
            case "protectfrommissiles": case "protectfrommissile": case "protectfromrange":
            case "protectfromranged": case "protectmissiles": case "protectrange":
            case "protrange": case "range": case "ranged": case "prayrange":
                return AnimationDb.PROTECT_RANGE_PRAYER_ID;
            case "protectfrommelee": case "protectmelee": case "protmelee":
            case "melee": case "praymelee":
                return AnimationDb.PROTECT_MELEE_PRAYER_ID;
            case "eagleeye":
                return AnimationDb.EAGLE_EYE_PRAYER_ID;
            case "deadeye":
                return AnimationDb.DEADEYE_PRAYER_ID;
            case "mysticmight":
                return AnimationDb.MYSTIC_MIGHT_PRAYER_ID;
            case "mysticvigour": case "mysticvigor":
                return AnimationDb.MYSTIC_VIGOUR_PRAYER_ID;
            case "chivalry":
                return AnimationDb.CHIVALRY_PRAYER_ID;
            case "piety":
                return AnimationDb.PIETY_PRAYER_ID;
            case "rigour": case "rigor":
                return AnimationDb.RIGOUR_PRAYER_ID;
            case "augury":
                return AnimationDb.AUGURY_PRAYER_ID;
            case "smite":
                return 24;
            case "redemption":
                return 23;
            case "retribution":
                return 22;
            case "preserve":
                return 25;
            case "rapidheal":
                return 10;
            case "rapidrestore":
                return 9;
            default:
                return -1;
        }
    }

    public static String prayerEnumName(int prayerId) {
        if (prayerId == AnimationDb.PROTECT_ITEM_PRAYER_ID) return "PROTECT_ITEM";
        if (prayerId == AnimationDb.PROTECT_MAGIC_PRAYER_ID) return "PROTECT_FROM_MAGIC";
        if (prayerId == AnimationDb.PROTECT_RANGE_PRAYER_ID) return "PROTECT_FROM_MISSILES";
        if (prayerId == AnimationDb.PROTECT_MELEE_PRAYER_ID) return "PROTECT_FROM_MELEE";
        if (prayerId == AnimationDb.EAGLE_EYE_PRAYER_ID) return "EAGLE_EYE";
        if (prayerId == AnimationDb.DEADEYE_PRAYER_ID) return "DEADEYE";
        if (prayerId == AnimationDb.MYSTIC_MIGHT_PRAYER_ID) return "MYSTIC_MIGHT";
        if (prayerId == AnimationDb.MYSTIC_VIGOUR_PRAYER_ID) return "MYSTIC_VIGOUR";
        if (prayerId == AnimationDb.CHIVALRY_PRAYER_ID) return "CHIVALRY";
        if (prayerId == AnimationDb.PIETY_PRAYER_ID) return "PIETY";
        if (prayerId == AnimationDb.RIGOUR_PRAYER_ID) return "RIGOUR";
        if (prayerId == AnimationDb.AUGURY_PRAYER_ID) return "AUGURY";
        if (prayerId == 24) return "SMITE";
        if (prayerId == 23) return "REDEMPTION";
        if (prayerId == 22) return "RETRIBUTION";
        if (prayerId == 25) return "PRESERVE";
        if (prayerId == 10) return "RAPID_HEAL";
        if (prayerId == 9) return "RAPID_RESTORE";
        return null;
    }

    private void ensurePrayerTab() {
        script.invokeSetTab(5);
    }
}
