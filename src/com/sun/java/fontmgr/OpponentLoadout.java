package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable per-tick read-model of the opponent's worn equipment.
 *
 * <p><b>Why.</b> {@link CombatScript} read the opponent's 14 equipment ids in
 * two separate places ({@code targetLooksLikeDharok}, {@code targetWeaponStyle},
 * plus a third copy in {@link StateReader}), each repeating the same reflection
 * dance and each throwing away everything except a single derived boolean or
 * enum. The agent therefore decided almost entirely from observed
 * <em>animations</em>, which are by definition a tick late — by the time the
 * swing is playing, the overhead should already have been up.
 *
 * <p>This type captures the read once per tick and <em>keeps the whole
 * loadout</em>, so decisions can later be made from what the opponent is
 * actually wearing rather than from what they already swung. It is a pure value
 * object: no reflection, no caching, no reference to the script.
 * {@link CombatScript} resolves ids to names (it owns both the field handles and
 * the item-name cache) and hands them in.
 *
 * <p><b>Behavior preservation.</b> The two existing consumers now delegate here
 * and the logic was moved verbatim: {@link #looksLikeDharok()} is the old
 * {@code targetLooksLikeDharok} rule ({@code axe || pieces >= 3}) and
 * {@link #weaponStyle()} is the old {@code targetWeaponStyle} name chain. Both
 * returned a neutral value ({@code false} / {@link AnimationDb.AttackStyle#UNKNOWN})
 * when the equipment array was unreadable, which is what {@link #empty()} yields.
 */
public final class OpponentLoadout {

    /** Slot indices in the client's equipment array. */
    public static final int SLOT_HELM   = 0;
    public static final int SLOT_CAPE   = 1;
    public static final int SLOT_AMULET = 2;
    public static final int SLOT_WEAPON = 3;
    public static final int SLOT_BODY   = 4;
    public static final int SLOT_SHIELD = 5;
    public static final int SLOT_LEGS   = 7;
    public static final int SLOT_GLOVES = 9;
    public static final int SLOT_BOOTS  = 10;
    public static final int SLOT_RING   = 12;
    public static final int SLOT_AMMO   = 13;

    /** Resolves an item id to its display name; may return null for unknown ids. */
    public interface NameResolver {
        String nameOf(int itemId);
    }

    private static final OpponentLoadout EMPTY =
            new OpponentLoadout(new int[0], new String[0]);

    /** Loadout for "no target / equipment unreadable". */
    public static OpponentLoadout empty() {
        return EMPTY;
    }

    private final int[]    ids;
    private final String[] names;

    // Derived once at capture time — the loadout never changes afterwards.
    private final int    dharokPieces;
    private final boolean dharokAxe;
    private final AnimationDb.AttackStyle weaponStyle;

    private OpponentLoadout(int[] ids, String[] names) {
        this.ids = ids;
        this.names = names;
        int pieces = 0;
        boolean axe = false;
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (id <= 0) continue;
            String name = names[i];
            if (InventoryTracker.isDharokAxe(id, name)) axe = true;
            if (InventoryTracker.isDharokPiece(id, name)) pieces++;
        }
        this.dharokPieces = pieces;
        this.dharokAxe = axe;
        this.weaponStyle = classifyWeapon();
    }

    /**
     * Captures a raw client equipment array. Encodes the old id convention
     * ({@code raw > 0 ? raw : empty}) and resolves names through the caller's
     * cache, so this does no reflection of its own.
     *
     * @param rawIds   raw equipment ids as the client stores them; null or empty
     *                 yields {@link #empty()}
     * @param resolver item-name lookup, or null to capture ids without names
     */
    public static OpponentLoadout capture(int[] rawIds, NameResolver resolver) {
        if (rawIds == null || rawIds.length == 0) return EMPTY;
        int[] ids = new int[rawIds.length];
        String[] names = new String[rawIds.length];
        for (int i = 0; i < rawIds.length; i++) {
            int id = rawIds[i] > 0 ? rawIds[i] : 0;
            ids[i] = id;
            if (id <= 0) {
                names[i] = "";
                continue;
            }
            String n = resolver != null ? resolver.nameOf(id) : null;
            names[i] = n != null ? n : "";
        }
        return new OpponentLoadout(ids, names);
    }

    // ── Raw access ───────────────────────────────────────────────────────────

    /** Number of equipment slots the client reported (0 when unreadable). */
    public int slotCount() {
        return ids.length;
    }

    /** Decoded item id in {@code slot}, or 0 when empty / out of range. */
    public int itemId(int slot) {
        if (slot < 0 || slot >= ids.length) return 0;
        return ids[slot];
    }

    /** Display name in {@code slot}, or "" when empty / out of range. Never null. */
    public String name(int slot) {
        if (slot < 0 || slot >= names.length) return "";
        String n = names[slot];
        return n != null ? n : "";
    }

    /** True when nothing is worn (or the client read failed). */
    public boolean isEmpty() {
        for (int id : ids) if (id > 0) return false;
        return true;
    }

    /** Copy of the decoded ids, one entry per slot. */
    public int[] itemIds() {
        return ids.clone();
    }

    /** Worn pieces in slot order, reusing the codebase's gear-piece type. */
    public List<CombatScript.EquippedPiece> worn() {
        List<CombatScript.EquippedPiece> out = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] <= 0) continue;
            out.add(new CombatScript.EquippedPiece(i, ids[i], names[i]));
        }
        return Collections.unmodifiableList(out);
    }

    /** True when the opponent is wearing {@code itemId} anywhere. */
    public boolean isWearing(int itemId) {
        if (itemId <= 0) return false;
        for (int id : ids) if (id == itemId) return true;
        return false;
    }

    // ── Weapon ───────────────────────────────────────────────────────────────

    /** Worn weapon id, or 0 when the weapon slot is empty/unreadable. */
    public int weaponId() {
        return itemId(SLOT_WEAPON);
    }

    /** Worn weapon name, or "" when unknown. Never null. */
    public String weaponName() {
        return name(SLOT_WEAPON);
    }

    /**
     * Attack style implied by the worn weapon. {@link AnimationDb.AttackStyle#UNKNOWN}
     * while the weapon slot is empty (e.g. mid-swap), which is deliberate: an
     * empty slot must never be misread as a helm or cape.
     */
    public AnimationDb.AttackStyle weaponStyle() {
        return weaponStyle;
    }

    private AnimationDb.AttackStyle classifyWeapon() {
        if (ids.length < SLOT_WEAPON + 1) return AnimationDb.AttackStyle.UNKNOWN;
        int wid = ids[SLOT_WEAPON];
        if (wid <= 0) return AnimationDb.AttackStyle.UNKNOWN;
        String name = names[SLOT_WEAPON];
        String n = InventoryTracker.stripName(name);
        if (n.contains("bow") || n.contains("crossbow") || n.contains("ballista")
                || n.contains("atlatl") || n.contains("thrownaxe") || n.contains("knife")
                || n.contains("javelin") || n.contains("chinchompa") || n.contains("blowpipe")
                || n.contains("dart")) {
            return AnimationDb.AttackStyle.RANGED;
        }
        if (InventoryTracker.isNonAutocastStaff(wid, name) || InventoryTracker.isAutocastStaff(wid, name)
                || n.contains("staff") || n.contains("wand") || n.contains("trident")
                || n.contains("sanguinesti") || n.contains("sceptre")) {
            return AnimationDb.AttackStyle.MAGIC;
        }
        return AnimationDb.AttackStyle.MELEE;
    }

    // ── Dharok's ─────────────────────────────────────────────────────────────

    /** Number of worn Dharok's pieces (the axe counts as a piece). */
    public int dharokPieces() {
        return dharokPieces;
    }

    /** True when the greataxe itself is worn. */
    public boolean hasDharokAxe() {
        return dharokAxe;
    }

    /**
     * True when the opponent should be treated as a Dharok's threat: the axe is
     * worn, or three or more pieces are. Verbatim the old
     * {@code targetLooksLikeDharok} rule — a partial set piecemeal in the bag
     * does not count, because only worn gear moves the max hit.
     */
    public boolean looksLikeDharok() {
        return dharokAxe || dharokPieces >= 3;
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    /** Compact one-line summary for HUD / debug / socket use. Never null. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(i).append(':').append(InventoryTracker.stripName(names[i]));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "OpponentLoadout[" + describe() + "]";
    }
}
