package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Staff left-click cast (LCC): weapon tracking, spell pin state, and the
 * {@code MouseHandler} bytecode hook that selects a spell <em>before</em> the
 * client builds the click menu.
 *
 * <p>Extracted from {@link CombatScript} so weapon-swap lag, ice blocks, and
 * pinned spell identity are reviewable in one place.
 */
public final class LeftClickCast {

    private static final int ICE_SWAP_BLOCK_TICKS = 12;
    private static final int LEFT_CLICK_ARM_TICKS = 10;
    private static final int HOVER_OPCODE_PLAYER = 0;

    private static volatile LeftClickCast instance;

    private final CombatScript script;

    /** Last main-hand we actually wielded (1688 lags a tick). */
    private int lastMainWeaponId = -1;
    /** Range/melee swap: keep spell off until a staff is really wielded. */
    private volatile boolean iceBlockedUntilStaffWield = false;
    private int iceBlockSetTick = -999;
    private int pendingSwapNonStaffId = -1;
    /**
     * Mage swap named a staff (often by name only on Roat). The next weapon
     * that actually lands in the hand is learned as a mage id so Ice works
     * even when item-def names fail for custom pack ids.
     */
    private volatile boolean expectMageWield = false;
    private int expectMageUntilTick = -999;

    private volatile boolean armed;
    private volatile String pinnedName;
    private volatile int pinnedWidget = -1;
    private int armedTick = -99;
    private long lastHoverCheckMs = 0L;

    private Field playerVisibleField;
    private Field modelObjectsHoveringField;
    private Field modelHoveringObjectsField;
    private Method objectKeyGetOpcodeMethod;
    private Method objectKeyGetIdMethod;
    private Method mouseIn3dScreenMethod;
    /** Reflection results — when UNKNOWN we must fail open (not block arming). */
    private boolean hoverResolved = false;
    private boolean in3dResolved = false;

    LeftClickCast(CombatScript script) {
        this.script = script;
        instance = this;
        resolveHoverReflection();
    }

    /** Called from {@link ClientHooks} via {@code MouseHandler} bytecode inject. */
    static void onClientMousePressed() {
        LeftClickCast lcc = instance;
        if (lcc == null) return;
        try {
            lcc.prepareForWorldClick();
        } catch (Throwable t) {
            FontManager.debug("[LCC] mousePressed: " + t.getMessage());
        }
    }

    /** Called from {@link ClientHooks} via {@code MouseHandler} bytecode inject. */
    static void onClientMouseMoved() {
        LeftClickCast lcc = instance;
        if (lcc == null) return;
        try {
            lcc.prepareForWorldHover();
        } catch (Throwable t) {
            FontManager.debug("[LCC] mouseMoved: " + t.getMessage());
        }
    }

    // ── Public surface (CombatScript delegates here) ───────────────────────────

    public boolean staffLcCastEnabled() {
        return script.staffLcCast;
    }

    public boolean isArmed() {
        return armed;
    }

    public String pinnedName() {
        return pinnedName;
    }

    public int pinnedWidget() {
        return pinnedWidget;
    }

    public void setPinned(String name, int widget, int tick) {
        pinnedName = name;
        pinnedWidget = widget;
        armed = widget > 0;
        armedTick = tick;
    }

    public void clearPin() {
        armed = false;
        pinnedName = null;
        pinnedWidget = -1;
    }

    public boolean isSpellArmedPublic() {
        try {
            if (script.isIceBarrageFullyArmed()) return true;
            if (armed && pinnedWidget > 0
                    && script.currentTick() - armedTick <= LEFT_CLICK_ARM_TICKS) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public boolean isIceSelectionActive() {
        if (armed && pinnedWidget == script.iceBarrageWidgetId()) return true;
        return script.clientHasIceSelected();
    }

    public void clearArmPublic() {
        clearPin();
        script.clearClientSpellFields();
    }

    public void noteIncomingMainHand(int itemId, String name, int tick) {
        if (InventoryTracker.isAmmo(itemId, name)) return;
        boolean staff = InventoryTracker.isMageStaff(itemId, name)
                || InventoryTracker.isBlueMoonSpear(itemId, name);
        boolean main = staff || InventoryTracker.isNhMainWeapon(itemId, name);
        if (!main && !staff) {
            // Name-only mage line (id unknown): still expect a mage wield.
            if (InventoryTracker.looksLikeMageWeaponName(name)) {
                staff = true;
                main = true;
            } else {
                return;
            }
        }
        iceBlockSetTick = tick;
        if (staff) {
            pendingSwapNonStaffId = -1;
            iceBlockedUntilStaffWield = false;
            expectMageWield = true;
            expectMageUntilTick = tick + 8;
            if (itemId > 0) InventoryTracker.learnMageWeaponId(itemId);
            FontManager.log("[LCC] mage incoming: " + InventoryTracker.stripName(name)
                    + " id=" + itemId + " (will learn live id)");
        } else {
            expectMageWield = false;
            pendingSwapNonStaffId = itemId > 0 ? itemId : -1;
            iceBlockedUntilStaffWield = true;
            clearArmPublic();
        }
    }

    public void dropForMeleeOrRange(int tick) {
        iceBlockedUntilStaffWield = true;
        iceBlockSetTick = tick;
        clearArmPublic();
    }

    public void releaseBlockForMage() {
        if (script.isMageStaffEquipped()) {
            int live = script.equippedWeaponId();
            if (live > 0) lastMainWeaponId = live;
            pendingSwapNonStaffId = -1;
        }
        iceBlockedUntilStaffWield = false;
    }

    public void syncAfterWield(int itemId, int tick) {
        String name = itemId > 0 ? script.resolveItemNamePublic(itemId) : "";
        if (InventoryTracker.isAmmo(itemId, name)) return;

        // Mage swap said "Blue moon spear" by name; this is the live Roat id.
        if (expectMageWield && itemId > 0 && tick <= expectMageUntilTick
                && !InventoryTracker.isKnownMeleeOrRangeWeapon(itemId, name)) {
            InventoryTracker.learnMageWeaponId(itemId);
            expectMageWield = false;
            pendingSwapNonStaffId = -1;
            iceBlockedUntilStaffWield = false;
            lastMainWeaponId = itemId;
            clearArmPublic();
            FontManager.log("[LCC] learned mage weapon id=" + itemId
                    + " name=" + InventoryTracker.stripName(name));
            return;
        }

        boolean staff = InventoryTracker.isMageStaff(itemId, name)
                || InventoryTracker.isBlueMoonSpear(itemId, name);
        if (staff || InventoryTracker.isNhMainWeapon(itemId, name)) {
            lastMainWeaponId = itemId;
        }
        if (staff) {
            if (itemId > 0) InventoryTracker.learnMageWeaponId(itemId);
            pendingSwapNonStaffId = -1;
            iceBlockedUntilStaffWield = false;
            expectMageWield = false;
            clearArmPublic();
            FontManager.log("[LCC] mage wielded: " + InventoryTracker.stripName(name)
                    + " id=" + itemId);
            return;
        }
        if (InventoryTracker.isNhMainWeapon(itemId, name)
                || InventoryTracker.isKnownMeleeOrRangeWeapon(itemId, name)) {
            expectMageWield = false;
            pendingSwapNonStaffId = -1;
            iceBlockedUntilStaffWield = true;
            iceBlockSetTick = tick;
            clearArmPublic();
        }
    }

    /** Death, logout, or empty weapon — drop stale staff memory. */
    public void resetWeaponState() {
        lastMainWeaponId = -1;
        pendingSwapNonStaffId = -1;
        iceBlockedUntilStaffWield = false;
        iceBlockSetTick = -999;
        expectMageWield = false;
        expectMageUntilTick = -999;
        clearArmPublic();
    }

    public void onTick(int tick) {
        if (!script.isLoggedIn()) {
            resetWeaponState();
            return;
        }
        int live = script.equippedWeaponId();
        if (live <= 0 && !iceBlockedUntilStaffWield) {
            lastMainWeaponId = -1;
        }
        if (!script.staffLcCast) {
            if (isIceSelectionActive()) clearArmPublic();
            return;
        }
        if (!staffForIce() && isIceSelectionActive()) {
            clearArmPublic();
        }
        if (armed && !script.staffLcCast) {
            clearArmPublic();
        }
        // Keep Ice SELECTED whenever a mage weapon is on so the first click
        // already casts. prepareForWorldClick clears the arm when the cursor
        // is not over a player (walk / bank / inv stay usable).
        if (staffForIce()) {
            int widget = pinnedWidget > 0 ? pinnedWidget : script.iceBarrageWidgetId();
            String name = pinnedName != null ? pinnedName : "Ice Barrage";
            if (!script.clientSpellSelected() || pinnedWidget != widget) {
                if (script.currentTick() - armedTick >= 2) {
                    if (script.ensureSpellArmed(widget, name, false)) {
                        setPinned(name, widget, script.currentTick());
                    }
                    armedTick = script.currentTick();
                }
            }
        }
    }

    public boolean staffForIce() {
        if (!script.staffLcCast) return false;

        int live = script.equippedWeaponId();
        String liveName = live > 0 ? script.resolveItemNamePublic(live) : null;

        if (live <= 0) {
            if (!iceBlockedUntilStaffWield) lastMainWeaponId = -1;
            return false;
        }

        // Learn Roat custom id right after a mage swap named the weapon.
        if (expectMageWield && script.currentTick() <= expectMageUntilTick
                && !InventoryTracker.isKnownMeleeOrRangeWeapon(live, liveName)) {
            InventoryTracker.learnMageWeaponId(live);
            expectMageWield = false;
            FontManager.log("[LCC] staffForIce learned id=" + live
                    + " name=" + InventoryTracker.stripName(liveName));
        }

        if (InventoryTracker.isMageStaff(live, liveName)
                || InventoryTracker.isBlueMoonSpear(live, liveName)
                || InventoryTracker.isLearnedMageWeapon(live)) {
            lastMainWeaponId = live;
            pendingSwapNonStaffId = -1;
            iceBlockedUntilStaffWield = false;
            return true;
        }

        // Off-by-one / appearance lag: try neighbouring ids for moon spear names.
        if (live > 1) {
            String n1 = script.resolveItemNamePublic(live + 1);
            String n2 = script.resolveItemNamePublic(live - 1);
            if (InventoryTracker.isBlueMoonSpear(live + 1, n1)
                    || InventoryTracker.isBlueMoonSpear(live - 1, n2)
                    || InventoryTracker.looksLikeMageWeaponName(n1)
                    || InventoryTracker.looksLikeMageWeaponName(n2)
                    || InventoryTracker.looksLikeMageWeaponName(liveName)) {
                InventoryTracker.learnMageWeaponId(live);
                lastMainWeaponId = live;
                pendingSwapNonStaffId = -1;
                iceBlockedUntilStaffWield = false;
                return true;
            }
        }

        if (isNonStaffMainHand(live, liveName)
                || InventoryTracker.isKnownMeleeOrRangeWeapon(live, liveName)) {
            lastMainWeaponId = live;
            pendingSwapNonStaffId = -1;
            expectMageWield = false;
            return false;
        }

        if (iceBlockedUntilStaffWield) return false;

        if (lastMainWeaponId > 0) {
            String lastName = script.resolveItemNamePublic(lastMainWeaponId);
            if (isNonStaffMainHand(lastMainWeaponId, lastName)) return false;
            return InventoryTracker.isMageStaff(lastMainWeaponId, lastName)
                    || InventoryTracker.isBlueMoonSpear(lastMainWeaponId, lastName)
                    || InventoryTracker.isLearnedMageWeapon(lastMainWeaponId);
        }

        return false;
    }

    /** HUD / debug: why Ice is or isn't ready. */
    public String iceStatusLine() {
        if (!script.staffLcCast) return "Ice LC: OFF";
        int live = script.equippedWeaponId();
        String name = live > 0 ? InventoryTracker.stripName(script.resolveItemNamePublic(live)) : "—";
        if (name.isEmpty()) name = "id=" + live;
        if (staffForIce()) {
            return "Ice LC: READY · " + name + (live > 0 ? " (" + live + ")" : "");
        }
        return "Ice LC: blocked · " + name + (live > 0 ? " (" + live + ")" : "");
    }

    /** Fight-tab button: pin whatever is currently equipped as the Ice staff. */
    public void learnCurrentWeaponAsMage() {
        int live = script.equippedWeaponId();
        if (live <= 0) {
            FontManager.log("[LCC] learn failed — no weapon equipped");
            return;
        }
        InventoryTracker.learnMageWeaponId(live);
        expectMageWield = false;
        iceBlockedUntilStaffWield = false;
        pendingSwapNonStaffId = -1;
        lastMainWeaponId = live;
        FontManager.log("[LCC] pinned mage weapon id=" + live
                + " name=" + InventoryTracker.stripName(script.resolveItemNamePublic(live)));
    }

    // ── MouseHandler hook targets ────────────────────────────────────────────

    /**
     * Staff + player under cursor: 626 the pinned spell (not always Ice).
     * Walk / bank / inv / ground: clear spellSelected so menus work.
     */
    private void prepareForWorldClick() {
        if (!script.staffLcCast) {
            if (armed || script.clientSpellSelected()) clearArmPublic();
            return;
        }
        int live = script.equippedWeaponId();
        String liveName = live > 0 ? script.resolveItemNamePublic(live) : null;
        if (live > 0 && isNonStaffMainHand(live, liveName)) {
            clearArmPublic();
            return;
        }
        if (!staffForIce()) {
            clearArmPublic();
            return;
        }
        if (!isMouseIn3dScreen() || !isMouseOverHoverPlayer()) {
            clearArmPublic();
            return;
        }
        int widget = pinnedWidget;
        String name = pinnedName;
        if (widget <= 0) {
            if (!script.staffLcCast) {
                clearArmPublic();
                return;
            }
            widget = script.iceBarrageWidgetId();
            name = "Ice Barrage";
        }
        script.ensureSpellArmed(widget, name, true);
    }

    /**
     * MOUSE_MOVED / MOUSE_DRAGGED fast path: arm the pinned spell as soon as the
     * mouse is over a player, so the client builds the click menu with
     * "Cast Ice Barrage ->" before the user clicks. Throttled because mouse
     * motion fires far more often than the game tick; the first click is then
     * an immediate cast instead of a selection-then-cast two-step.
     */
    private void prepareForWorldHover() {
        if (!script.staffLcCast) return;
        int widget = pinnedWidget > 0 ? pinnedWidget : script.iceBarrageWidgetId();
        boolean already = widget == script.iceBarrageWidgetId()
                ? script.clientHasIceSelected()
                : script.clientSpellSelected();
        if (already) return;
        long now = System.currentTimeMillis();
        if (now - lastHoverCheckMs < 40) return;
        lastHoverCheckMs = now;
        if (!staffForIce()) return;
        if (!isMouseIn3dScreen() || !isMouseOverHoverPlayer()) return;
        String name = pinnedName != null ? pinnedName : "Ice Barrage";
        if (script.ensureSpellArmed(widget, name, false)) {
            setPinned(name, widget, script.currentTick());
        }
    }

    private boolean isIceSwapBlockActive() {
        if (!iceBlockedUntilStaffWield) return false;
        if (script.currentTick() - iceBlockSetTick > ICE_SWAP_BLOCK_TICKS) return false;
        if (pendingSwapNonStaffId > 0) return true;
        if (lastMainWeaponId > 0) {
            String n = script.resolveItemNamePublic(lastMainWeaponId);
            return isNonStaffMainHand(lastMainWeaponId, n);
        }
        return false;
    }

    private boolean isNonStaffMainHand(int itemId, String name) {
        if (itemId <= 0) return false;
        if (InventoryTracker.isAmmo(itemId, name)) return false;
        if (InventoryTracker.isMageStaff(itemId, name)
                || InventoryTracker.isBlueMoonSpear(itemId, name)) return false;
        return InventoryTracker.isNhMainWeapon(itemId, name);
    }

    private boolean isMouseIn3dScreen() {
        if (mouseIn3dScreenMethod == null) return true;
        try {
            Object r = mouseIn3dScreenMethod.invoke(script.client());
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception ignored) {}
        return true;
    }

    private boolean isMouseOverHoverPlayer() {
        if (!hoverResolved) {
            // Reflection incomplete — do not block staff cast on player clicks.
            return true;
        }
        if (modelObjectsHoveringField == null || modelHoveringObjectsField == null
                || objectKeyGetOpcodeMethod == null || objectKeyGetIdMethod == null) {
            return true;
        }
        try {
            Field playerArrayField = script.playerArrayField();
            Field myPlayerField = script.myPlayerField();
            if (playerArrayField == null) return false;
            int count = modelObjectsHoveringField.getInt(null);
            if (count <= 0) return false;
            long[] objs = (long[]) modelHoveringObjectsField.get(null);
            if (objs == null) return false;
            Object[] players = (Object[]) playerArrayField.get(script.client());
            if (players == null) return false;
            Object me = myPlayerField != null ? myPlayerField.get(null) : null;
            long prev = -1L;
            for (int k = 0; k < count; k++) {
                long current = objs[k];
                if (current == prev) continue;
                prev = current;
                int opcode = (Integer) objectKeyGetOpcodeMethod.invoke(null, current);
                if (opcode != HOVER_OPCODE_PLAYER) continue;
                int uid = (Integer) objectKeyGetIdMethod.invoke(null, current);
                if (uid < 0 || uid >= players.length) continue;
                Object player = players[uid];
                if (player == null || player == me) continue;
                if (playerVisibleField != null) {
                    try {
                        if (!playerVisibleField.getBoolean(player)) continue;
                    } catch (Exception ignored) {}
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void resolveHoverReflection() {
        try {
            Object client = script.client();
            if (client == null) return;
            Class<?> clientClass = client.getClass();
            ClassLoader cl = clientClass.getClassLoader();
            Class<?> modelClass = null;
            for (String modelName : new String[] {
                    "com.roatpkz.client.game.entity.model.Model",
                    "com.roatpkz.client.game.entity.Model" }) {
                try {
                    modelClass = Class.forName(modelName, false, cl);
                    break;
                } catch (ClassNotFoundException ignored) {}
            }
            if (modelClass != null) {
                try { modelObjectsHoveringField = modelClass.getField("objectsHovering"); }
                catch (NoSuchFieldException e) {
                    modelObjectsHoveringField = modelClass.getDeclaredField("objectsHovering");
                    modelObjectsHoveringField.setAccessible(true);
                }
                try { modelHoveringObjectsField = modelClass.getField("hoveringObjects"); }
                catch (NoSuchFieldException e) {
                    modelHoveringObjectsField = modelClass.getDeclaredField("hoveringObjects");
                    modelHoveringObjectsField.setAccessible(true);
                }
            }
            Class<?> oku = Class.forName(
                    "com.roatpkz.client.game.misc.ObjectKeyUtil", false, cl);
            objectKeyGetOpcodeMethod = oku.getMethod("getObjectOpcode", long.class);
            objectKeyGetIdMethod = oku.getMethod("getObjectId", long.class);
            try { mouseIn3dScreenMethod = clientClass.getMethod("mouseIn3dScreen"); }
            catch (NoSuchMethodException e) { mouseIn3dScreenMethod = null; }
            Class<?> actorClass = RtLookup.actor();
            if (actorClass == null) {
                try {
                    actorClass = Class.forName("com.roatpkz.client.game.entity.Player", false, cl);
                } catch (ClassNotFoundException ignored) {}
            }
            if (actorClass != null) {
                for (String vis : new String[] { "visible", "isVisible" }) {
                    try {
                        playerVisibleField = actorClass.getDeclaredField(vis);
                        playerVisibleField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                }
            }
            hoverResolved = modelObjectsHoveringField != null && modelHoveringObjectsField != null
                    && objectKeyGetOpcodeMethod != null && objectKeyGetIdMethod != null;
            in3dResolved = mouseIn3dScreenMethod != null;
        } catch (Exception ignored) {}
        FontManager.prayLog("LCC reflect hover=" + hoverResolved + " in3d=" + in3dResolved);
    }
}
