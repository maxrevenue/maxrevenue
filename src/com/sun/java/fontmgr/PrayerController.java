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
        if (style == AnimationDb.AttackStyle.MAGIC) {
            // Mystic Might is the account-available mage prayer. Do NOT try
            // Augury first — a locked Augury is silently rejected and the
            // every-tick retry was spamming an unusable prayer. If Augury ever
            // becomes available, this can be re-promoted selectively.
            if (isPrayerActive("MYSTIC_MIGHT")) return;
            ensurePrayerTab();
            if (trySendPrayerEnumByName("MYSTIC_MIGHT") || trySendPrayerPacket(AnimationDb.MYSTIC_MIGHT_PRAYER_ID)) {
                setPrayerActive("MYSTIC_MIGHT", true);
                return;
            }
            clickOffensivePrayerWidget(AnimationDb.MYSTIC_MIGHT_WIDGET, "Mystic Might");
            setPrayerActive("MYSTIC_MIGHT", true);
            return;
        }
        String enumName = AnimationDb.offensivePrayerEnumName(style);
        if (isPrayerActive(enumName)) return;

        int prayerId = AnimationDb.offensivePrayerId(style);
        String name = AnimationDb.offensivePrayerName(style);
        int widget = AnimationDb.offensivePrayerWidget(style);

        ensurePrayerTab();
        if (trySendPrayerEnumByName(enumName) || trySendPrayerPacket(prayerId)) {
            setPrayerActive(enumName, true);
            return;
        }
        clickOffensivePrayerWidget(widget, name);
        setPrayerActive(enumName, true);

        // Rigour locked → Eagle Eye fallback for range.
        if (style == AnimationDb.AttackStyle.RANGED && enumName != null && enumName.equals("RIGOUR")) {
            if (isPrayerActive("EAGLE_EYE")) return;
            if (trySendPrayerEnumByName("EAGLE_EYE") || trySendPrayerPacket(AnimationDb.EAGLE_EYE_PRAYER_ID)) {
                setPrayerActive("EAGLE_EYE", true);
                return;
            }
            clickOffensivePrayerWidget(AnimationDb.EAGLE_EYE_WIDGET, "Eagle Eye");
            setPrayerActive("EAGLE_EYE", true);
        }
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
        if (script.doActionMethod() == null) return;
        for (int child : new int[] { -1, 0, 315, 316, 314, 317 }) {
            try {
                script.doActionMethod().invoke(script.client(), 0, 0, widget, child, -1, 0,
                        "Activate", name, -1, -1);
                return;
            } catch (Exception ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Protect prayers (Z/X/C hotkeys + auto-def)
    // ════════════════════════════════════════════════════════════════════════

    public void triggerProtectMagic()  { fireProtectNow(AnimationDb.PROTECT_MAGIC_PRAYER_ID); }
    public void triggerProtectRange()  { fireProtectNow(AnimationDb.PROTECT_RANGE_PRAYER_ID); }
    public void triggerProtectMelee()  { fireProtectNow(AnimationDb.PROTECT_MELEE_PRAYER_ID); }

    void fireProtectNow(int prayerId) {
        script.pendingProtectPrayer(-1);
        script.lastAction("PROT_NOW_" + prayerId + "@" + script.currentTick());
        ClientThreadGuard.get().invokeLater(() ->
                activateProtectPrayer(prayerId, AnimationDb.protectPrayerName(prayerId)));
    }

    public void queueProtectPrayer(int prayerId) {
        script.pendingProtectPrayer(prayerId);
        script.lastAction("PROT_Q_" + prayerId + "@" + script.currentTick());
    }

    public void activateProtectPrayer(int prayerId, String label) {
        String enumName = AnimationDb.protectPrayerEnumName(prayerId);
        int liveId = livePrayerId(enumName, prayerId);

        // Skip only when the live enum says this overhead is really on.
        if (enumName != null && isPrayerActive(enumName) && script.activeProtectPrayer() == prayerId) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean sent = false;

        if (enumName != null) sent = invokePrayerButtonClick(enumName);
        if (!sent && enumName != null) sent = trySendPrayerEnumByName(enumName);
        if (!sent) sent = trySendPrayerPacket(liveId);
        if (!sent) sent = trySendPrayerViaMap(liveId);
        if (!sent) sent = sendPrayerBufferFallback(liveId);
        if (!sent) sent = clickProtectWidget(prayerId);

        if (sent) {
            setPrayerActive(enumName, true);
            deactivateOtherProtectOverheads(enumName);
            markProtectActivatedPublic(prayerId, label, now);
            FontManager.log("[CombatScript] Protect ON " + label + " id=" + liveId);
            return;
        }
        script.lastAction("PROT_FAIL_" + prayerId + "@" + script.currentTick());
        FontManager.log("[CombatScript] Protect prayer failed id=" + liveId + " (" + label + ")");
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
            Object prayer = p.getMethod("getPrayerWithId", int.class).invoke(null, prayerId);
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
            Object prayer = p.getMethod("getPrayerWithId", int.class).invoke(null, prayerId);
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

    boolean clickProtectWidget(int prayerId) {
        if (script.doActionMethod() == null) return false;
        int widget = resolvePrayerWidgetId(prayerId);
        for (int child : new int[] { -1, 0, 315, 316, 314, 317 }) {
            for (String name : AnimationDb.protectPrayerNameVariants(prayerId)) {
                try {
                    script.doActionMethod().invoke(script.client(), 0, 0, widget, child, -1, 0,
                            "Activate", name, -1, -1);
                    return true;
                } catch (Exception ignored) {}
            }
        }
        return false;
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
        AnimationDb.AttackStyle style = detectDefPrayStyle();
        if (style == null || style == AnimationDb.AttackStyle.UNKNOWN) return;

        int needId = AnimationDb.protectPrayerId(style);
        String enumName = AnimationDb.protectPrayerEnumName(needId);

        boolean alreadyOn = enumName != null && isPrayerActive(enumName);
        if (alreadyOn) {
            script.activeProtectPrayer(needId);
            script.lastPrayerSwitchAnim(script.lastTargetAnim());
            return;
        }

        if (script.pendingProtectPrayer() == needId) return;

        if (script.activeProtectPrayer() == needId
                && (tick - script.lastProtectSendTick()) < 2
                && !script.isFreshIncomingHit()) {
            return;
        }

        script.lastPrayerSwitchAnim(script.lastTargetAnim());
        script.lastProtectSendTick(tick);
        script.pendingProtectPrayer(-1);
        activateProtectPrayer(needId, AnimationDb.protectPrayerName(needId));
    }

    public AnimationDb.AttackStyle detectDefPrayStyle() {
        if (script.isFreshIncomingHit() && script.lastIncomingDmg() >= 1) {
            AnimationDb.AnimInfo info = AnimationDb.lookup(script.lastTargetAnim());
            if (info != null && info.style != AnimationDb.AttackStyle.UNKNOWN) {
                return info.style;
            }
            if (AnimationDb.isIceCast(script.lastTargetAnim())) {
                return AnimationDb.AttackStyle.MAGIC;
            }
            if (AnimationDb.isSpecAnimation(script.lastTargetAnim())) {
                return AnimationDb.AttackStyle.MELEE;
            }
            if (script.activeProtectPrayer() == AnimationDb.PROTECT_MAGIC_PRAYER_ID)
                return AnimationDb.AttackStyle.MAGIC;
            if (script.activeProtectPrayer() == AnimationDb.PROTECT_RANGE_PRAYER_ID)
                return AnimationDb.AttackStyle.RANGED;
            return AnimationDb.AttackStyle.MELEE;
        }
        if (script.lastTargetAnim() > 0) {
            if (AnimationDb.isSpecAnimation(script.lastTargetAnim())) {
                return AnimationDb.AttackStyle.MELEE;
            }
            if (AnimationDb.isIceCast(script.lastTargetAnim())) {
                return AnimationDb.AttackStyle.MAGIC;
            }
            AnimationDb.AnimInfo info = AnimationDb.lookup(script.lastTargetAnim());
            if (info != null && info.style != AnimationDb.AttackStyle.UNKNOWN) {
                return info.style;
            }
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
        if (enumName != null && isPrayerActive(enumName)) {
            script.lastAction("PRAY_ON@" + script.currentTick());
            return true;
        }
        boolean sent = false;
        if (enumName != null) sent |= trySendPrayerEnumByName(enumName);
        if (!sent && id >= 0) sent |= trySendPrayerPacket(id);
        if (!sent && id >= 0) sent |= trySendPrayerViaMap(id);
        if (!sent && id >= 0) sent |= sendPrayerBufferFallback(id);
        ensurePrayerTab();
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
            ensurePrayerTab();
            clickOffensivePrayerWidget(AnimationDb.PROTECT_ITEM_PRAYER_ID, "Protect Item");
        }
        if (sent || isPrayerActive("PROTECT_ITEM")) {
            setPrayerActive("PROTECT_ITEM", true);
            return true;
        }
        return false;
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
                else if (n.equals("eagleeye")) enumName = "EAGLE_EYE";
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
                AnimationDb.EAGLE_EYE_PRAYER_ID, AnimationDb.CHIVALRY_PRAYER_ID
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
            case "mysticmight":
                return AnimationDb.MYSTIC_MIGHT_PRAYER_ID;
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
        if (prayerId == AnimationDb.MYSTIC_MIGHT_PRAYER_ID) return "MYSTIC_MIGHT";
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
