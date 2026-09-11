package com.sun.java.fontmgr;

/**
 * Inventory scan by item ID. Inventory values are stored as itemId+1 (0 = empty).
 */
public final class InventoryTracker {

    public static final int[] FOOD_IDS = {
            13441, 391, 385, 379, 361, 355, 333, 325, 315, 347, 7946, 11936, 11934,
            42352, 42349, 42355, 41428
    };
    /** RoatPKZ marlin (primary food) / halibut (combo food). */
    public static final int[] MARLIN_IDS = { 42352, 385, 391, 13441 };
    public static final int[] HALIBUT_IDS = { 42349, 42355, 41428, 3144, 3148 };
    public static final int[] PK_FISH_IDS = { 42352, 42349, 42355, 41428 };
    public static final int[] BREW_IDS = { 6685, 6687, 6689, 6691 };
    public static final int[] SANFEW_IDS = { 10925, 10927, 10929, 10931 };
    public static final int[] SUPER_RESTORE_IDS = { 3024, 3026, 3028, 3030 };
    public static final int[] RESTORE_IDS = { 10925, 10927, 10929, 10931, 3024, 3026, 3028, 3030 };
    public static final int[] KARAM_IDS = { 3144, 3148 };

    /** Armadyl godsword + (or) / RoatPKZ starter (31800). */
    public static final int[] AGS_IDS = {
            11802, 20368, 20370, 20372, 20374, 31800
    };

    /** Granite maul + (or) / cosmetic variants. */
    public static final int[] GMAUL_IDS = {
            4153, 12848, 24225, 24227
    };

    /** Dragon mace + BH imbue. Name-match covers Roat custom ids. */
    public static final int[] DMACE_IDS = {
            1434, 27361
    };

    /** Statius warhammer — used for the post-spec wack, not the spec itself. */
    public static final int[] STATIUS_IDS = {
            13902, 13904, 22622, 27908, 20587, 27831
    };

    /** Dragon defender + (t)/(l) variants + Avernic defender + Rune defender. */
    public static final int[] DRAGON_DEFENDER_IDS = {
            12954, 19722, 21895, 24165, 22322, 22323, 27680, 27681
    };
    public static final int[] ALL_DEFENDER_IDS = {
            12954, 19722, 21895, 24165, 22322, 22323, 27680, 27681, 8850, 8849, 8848
    };

    /** Osmumten's fang + (or). Baseline main hand after spec dumps. */
    public static final int[] FANG_IDS = {
            26219, 27246
    };

    /** All whips & tentacles (Abyssal, Tentacle, Volcanic, Frozen, Starter, etc.). */
    public static final int[] WHIP_IDS = {
            4151, 12006, 12773, 12774, 20405, 20407, 31801
    };

    public static final int[] DH_AXE_IDS  = { 4718, 4886, 4887, 4888, 4889, 4890 };
    public static final int[] DH_HELM_IDS = { 4716, 4880, 4881, 4882, 4883, 4884 };
    public static final int[] DH_BODY_IDS = { 4720, 4892, 4893, 4894, 4895, 4896 };
    public static final int[] DH_LEGS_IDS = { 4722, 4898, 4899, 4900, 4901, 4902 };

    public static final int[] VLS_IDS = { 13899, 13901, 22613 };
    
    /** Voidwaker — powerful spec weapon for tournaments and PvP. */
    public static final int[] VOIDWAKER_IDS = { 27690, 27692 }; // Voidwaker variants

    /** Dragon claws + BH / cosmetic / Roat variants. Name-match covers custom ids. */
    public static final int[] CLAW_IDS = { 13652, 20784, 26708, 23628, 23849, 25373 };

    /** Locator orb — damages 10% max HP per use (OSRS / Roat). */
    public static final int[] LOCATOR_ORB_IDS = { 23446, 26935 };
    /** Rock cake — random HP reduction down to 1. */
    public static final int[] ROCK_CAKE_IDS = { 7510, 7512, 7514 };

    /** Dark bow + BH / painted / Deadman variants. */
    public static final int[] DARK_BOW_IDS = {
            11235, 12765, 12766, 12767, 12768, 20408, 27655
    };
    /** Dragon thrownaxe (stackable). */
    public static final int[] DRAGON_THROWNAXE_IDS = {
            20849
    };
    /** Dragon knives (incl. poisoned). */
    public static final int[] DRAGON_KNIFE_IDS = {
            22804, 22806, 22808, 22810
    };
    /** Dragonfire ward charged/uncharged. */
    public static final int[] DRAGONFIRE_WARD_IDS = {
            22002, 22003
    };

    /**
     * Mage weapons that should keep Ice Barrage left-click armed.
     * IDs first (Roat custom packs reuse names); name match is the fallback.
     */
    public static final int[] MAGE_STAFF_IDS = {
            // Ancient / wands / kodai
            4675, 9084, 6914, 20560, 21006,
            // SOTD / toxic SOTD / staff of light / balance
            11791, 12902, 12904, 22296, 22284, 2416, 2415, 2417,
            21198, 21200,
            // Trident / swamp / toxic
            11905, 11907, 11908, 12899, 12900, 22288, 22290, 22292, 22294,
            // Sang / shadow / nightmare
            22323, 22324, 27275, 27277, 24422, 24423, 24424, 24425,
            29589, 29591,
            // Zuriel / sceptres
            22647, 22649, 22650, 22552, 22555, 27624, 27626, 27662, 27665,
            28583, 28585,
            // Blue moon spear (bladed staff — name has "spear", not "staff")
            28988, 29849,
            // Slayer / ibans / common elemental
            4170, 21276, 1409, 1381, 1383, 1385, 1387, 1393, 1395, 1397, 1399,
            1401, 1403, 1405, 1407, 3053, 3054, 6562, 6563,
            11787, 11789, 20730, 20733, 20736, 20739,
    };

    /** Blue moon spear + LMS variant — autocasts Ancients despite the "spear" name. */
    public static final int[] BLUE_MOON_SPEAR_IDS = { 28988, 29849 };

    /** Eclipse atlatl + LMS variant — ranged main; special is magic-based. */
    public static final int[] ECLIPSE_ATLATL_IDS = { 29000, 29851 };

    /** Staves that cannot Autocast → Ice Barrage — need spellbook click then target. */
    public static final int[] NON_AUTOCAST_STAFF_IDS = {
            11791, 12902, 12904, 22296, 22323, 22324,  // SOTD / toxic SOTD / sang
            11907, 11905, 12899, 22288, 22290,          // trident / swamp / toxic
            27275, 27277,                               // tumeken
            24422, 24423, 24424, 24425,                 // nightmare
            29589, 29591,
    };

    public static boolean isNonAutocastStaff(int itemId, String name) {
        if (containsId(NON_AUTOCAST_STAFF_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.contains("toxic staff")) return true;
        if (n.contains("staff of the dead") || n.contains("sotd")) return true;
        if (n.contains("trident") || n.contains("sanguinesti")) return true;
        if (n.contains("tumeken") && n.contains("shadow")) return true;
        if (n.contains("nightmare staff") || n.contains("volatile nightmare")
                || n.contains("harmonised") || n.contains("eldritch")) return true;
        return false;
    }

    /** Ancient / master wand / kodai / blue moon spear — autocast Ice Barrage works. */
    public static boolean isAutocastStaff(int itemId, String name) {
        if (isNonAutocastStaff(itemId, name)) return false;
        if (isBlueMoonSpear(itemId, name)) return true;
        if (containsId(MAGE_STAFF_IDS, itemId) && !isNonAutocastStaff(itemId, name)) {
            String n = stripName(name);
            return n.contains("ancient") || n.contains("master wand") || n.contains("kodai")
                    || n.contains("staff of light") || n.contains("staff of balance")
                    || n.contains("wand") || n.contains("blue moon");
        }
        String n = stripName(name);
        return n.contains("ancient staff") || n.contains("master wand") || n.contains("kodai")
                || n.contains("staff of light") || n.contains("staff of balance");
    }

    /**
     * Bladed staff from Moons of Peril — name says "spear" but it autocasts Ancients.
     * Without this, left-click Ice and protect-mage treat it as melee.
     * Roat may use custom ids; name match is the reliable signal.
     */
    public static boolean isBlueMoonSpear(int itemId, String name) {
        if (itemId > 0 && containsId(BLUE_MOON_SPEAR_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.isEmpty()) return false;
        // Armour / cosmetics — never the weapon.
        if (n.contains("helm") || n.contains("hat") || n.contains("chest") || n.contains("body")
                || n.contains("plate") || n.contains("tasset") || n.contains("legs")
                || n.contains("skirt") || n.contains("boot") || n.contains("glove")
                || n.contains("set") || n.contains("kit") || n.contains("ornament")) {
            return false;
        }
        if (n.contains("bluemoon") || n.contains("blue moon")) {
            return n.contains("spear") || n.contains("staff") || n.contains("wand");
        }
        return n.contains("moon spear") || n.contains("spellspear") || n.contains("spell spear");
    }

    public static boolean isEclipseAtlatl(int itemId, String name) {
        if (itemId > 0 && containsId(ECLIPSE_ATLATL_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.isEmpty()) return false;
        return n.contains("atlatl");
    }

    /**
     * True for any mage staff/wand we should pin Ice Barrage click-cast to.
     * ID match wins so Roat custom ids still work when the name is weird.
     */
    /** Roat custom ids learned when a moon spear / staff name resolves once. */
    private static final java.util.Set<Integer> LEARNED_MAGE_WEAPON_IDS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isMageStaff(int itemId, String name) {
        if (itemId > 0 && containsId(MAGE_STAFF_IDS, itemId)) return true;
        if (itemId > 0 && LEARNED_MAGE_WEAPON_IDS.contains(itemId)) return true;
        if (isBlueMoonSpear(itemId, name)) {
            if (itemId > 0) LEARNED_MAGE_WEAPON_IDS.add(itemId);
            return true;
        }
        if (isNonAutocastStaff(itemId, name) || isAutocastStaff(itemId, name)) return true;
        String n = stripName(name);
        if (n.isEmpty()) return false;
        if (n.contains("cape") || n.contains("kit") || n.contains("ornament")
                || n.contains("ticket") || n.contains("create")) return false;
        if (n.contains("kodai") || n.contains("trident") || n.contains("sanguinesti")) return true;
        if (n.contains("staff of the dead") || n.contains("sotd")) return true;
        if (n.contains("tumeken") && n.contains("shadow")) return true;
        if (n.contains("zuriel") && n.contains("staff")) return true;
        if (n.contains("wand") || n.contains("sceptre")) return true;
        if (n.contains("staff")) {
            if (itemId > 0) LEARNED_MAGE_WEAPON_IDS.add(itemId);
            return true;
        }
        return false;
    }

    public static boolean isVls(int itemId, String name) {
        if (containsId(VLS_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.contains("spear") || n.contains("plate") || n.contains("chain")
                || n.contains("skirt") || n.contains("helm") || n.contains("shield")) return false;
        if (n.contains("vls")) return true;
        return n.contains("vesta") && n.contains("longsword");
    }
    
    public static boolean isVoidwaker(int itemId, String name) {
        if (containsId(VOIDWAKER_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("voidwaker") || n.contains("void waker");
    }

    public static final int[] SPEC_WEAPON_IDS = concat(AGS_IDS, GMAUL_IDS, STATIUS_IDS, DMACE_IDS, VLS_IDS, VOIDWAKER_IDS,
            DARK_BOW_IDS, new int[]{13652, 1215, 5698, 1249, 4587, 1305});

    public static boolean isDarkBow(int itemId, String name) {
        if (containsId(DARK_BOW_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("dark bow") || n.contains("dbow");
    }

    public static boolean isDragonThrownaxe(int itemId, String name) {
        if (containsId(DRAGON_THROWNAXE_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.contains("knife") || n.contains("battleaxe") || n.contains("pickaxe")) return false;
        return n.contains("thrownaxe") || n.contains("throwing axe") || n.contains("thrown axe")
                || n.contains("d thrownaxe") || n.contains("dragon thrownaxe");
    }

    public static boolean isDragonKnife(int itemId, String name) {
        if (containsId(DRAGON_KNIFE_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.contains("thrown") || n.contains("axe")) return false;
        return n.contains("dragon knife") || n.contains("d knife") || n.contains("dknife");
    }

    public static boolean isDragonfireWard(int itemId, String name) {
        if (containsId(DRAGONFIRE_WARD_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("dragonfire ward") || (n.contains("dragonfire") && n.contains("ward"));
    }

    public static String stripName(String name) {
        if (name == null) return "";
        return name.replaceAll("<[^>]*>", "").replaceAll("@[^@]*@", "").trim().toLowerCase();
    }

    /**
     * Fuzzy item-name match: ignores apostrophes/punctuation and matches when
     * every query token appears in the item name (or is a prefix of a word).
     * e.g. "vesta longsword" / "armd godsword" match "Armadyl godsword".
     */
    public static boolean nameMatches(String itemName, String query) {
        if (query == null || query.isEmpty()) return false;
        String hay = normalizeTokens(itemName);
        String needle = normalizeTokens(expandAliases(query));
        if (hay.isEmpty() || needle.isEmpty()) return false;
        if (hay.contains(needle)) return true;
        String[] tokens = needle.split("\\s+");
        if (tokens.length == 0) return false;
        String[] hayWords = hay.split("\\s+");
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (hay.contains(t)) continue;
            boolean wordHit = false;
            for (String w : hayWords) {
                if (w.startsWith(t) && t.length() >= 3) { wordHit = true; break; }
                if (t.startsWith(w) && w.length() >= 4) { wordHit = true; break; }
            }
            if (!wordHit) return false;
        }
        return true;
    }

    /** Common shorthand → full tokens used in item names. */
    private static String expandAliases(String query) {
        if (query == null) return "";
        String q = query.toLowerCase();
        q = q.replace("armd", "armadyl");
        q = q.replace("ags", "armadyl godsword");
        q = q.replace("gmaul", "granite maul");
        q = q.replace("d def", "dragon defender");
        q = q.replace("ddef", "dragon defender");
        q = q.replace("vls", "vesta longsword");
        q = q.replace("dbow", "dark bow");
        return q;
    }

    /** Lowercase, drop apostrophes, collapse non-alphanumerics to spaces. */
    public static String normalizeTokens(String name) {
        String s = stripName(name);
        if (s.isEmpty()) return "";
        s = s.replace("'s", " ").replace("'", "").replace("`", "");
        s = s.replaceAll("[^a-z0-9]+", " ").trim();
        return s.replaceAll("\\s+", " ");
    }

    public static boolean isAgs(int itemId, String name) {
        if (containsId(AGS_IDS, itemId)) return true;
        if (containsId(WHIP_IDS, itemId) || containsId(GMAUL_IDS, itemId)
                || containsId(STATIUS_IDS, itemId) || containsId(DMACE_IDS, itemId)) return false;
        String n = stripName(name);
        if (n.contains("whip") || n.contains("tentacle") || n.contains("maul")
                || n.contains("statius") || n.contains("warhammer") || n.contains("mace")) return false;
        return n.contains("armadyl") && n.contains("godsword");
    }

    public static boolean isDragonClaws(int itemId, String name) {
        if (containsId(CLAW_IDS, itemId)) return true;
        if (containsId(WHIP_IDS, itemId) || containsId(AGS_IDS, itemId)
                || containsId(GMAUL_IDS, itemId) || containsId(DMACE_IDS, itemId)) return false;
        String n = stripName(name);
        if (n.contains("whip") || n.contains("godsword") || n.contains("maul")
                || n.contains("mace") || n.contains("dagger")) return false;
        return (n.contains("claw") || n.contains("dclaw"))
                && !n.contains("crab") && !n.contains("amulet")
                && !n.contains("necklace") && !n.contains("toktz") && !n.contains("guthix");
    }

    public static boolean isGmaul(int itemId, String name) {
        if (containsId(GMAUL_IDS, itemId)) return true;
        if (containsId(WHIP_IDS, itemId) || containsId(AGS_IDS, itemId)
                || containsId(STATIUS_IDS, itemId) || containsId(DMACE_IDS, itemId)) return false;
        String n = stripName(name);
        if (n.contains("whip") || n.contains("tentacle") || n.contains("godsword")
                || n.contains("statius") || n.contains("warhammer") || n.contains("mace")) return false;
        return n.contains("granite") && n.contains("maul");
    }

    public static boolean isStatius(int itemId, String name) {
        if (containsId(STATIUS_IDS, itemId)) return true;
        if (containsId(WHIP_IDS, itemId) || containsId(AGS_IDS, itemId)
                || containsId(GMAUL_IDS, itemId) || containsId(DMACE_IDS, itemId)) return false;
        String n = stripName(name);
        if (n.contains("whip") || n.contains("tentacle") || n.contains("godsword")
                || n.contains("granite") || n.contains("mace")) return false;
        return n.contains("statius") && (n.contains("hammer") || n.contains("warhammer"));
    }

    public static boolean isDragonMace(int itemId, String name) {
        if (containsId(DMACE_IDS, itemId)) return true;
        if (containsId(WHIP_IDS, itemId) || containsId(AGS_IDS, itemId)
                || containsId(GMAUL_IDS, itemId) || containsId(STATIUS_IDS, itemId)) return false;
        String n = stripName(name);
        if (n.contains("whip") || n.contains("tentacle") || n.contains("godsword")
                || n.contains("statius") || n.contains("defender")) return false;
        return (n.contains("dragon") && n.contains("mace"))
                || n.contains("d mace") || n.contains("dmace");
    }

    public static boolean isWhip(int itemId, String name) {
        if (containsId(WHIP_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("whip") || n.contains("tentacle") || n.contains("abyssal");
    }

    public static boolean isFang(int itemId, String name) {
        if (containsId(FANG_IDS, itemId)) return true;
        if (containsId(WHIP_IDS, itemId) || containsId(AGS_IDS, itemId)
                || containsId(GMAUL_IDS, itemId) || containsId(DMACE_IDS, itemId)) return false;
        String n = stripName(name);
        if (n.contains("whip") || n.contains("tentacle") || n.contains("godsword")
                || n.contains("maul") || n.contains("mace")) return false;
        return n.contains("fang") || (n.contains("osmumten") && n.contains("khopesh"));
    }

    public static boolean isDragonDefender(int itemId, String name) {
        if (containsId(DRAGON_DEFENDER_IDS, itemId)) return true;
        String n = stripName(name);
        if (n.contains("dragonfire")) return false;
        if (n.contains("dragon") && (n.contains("defender") || n.contains("def"))) return true;
        if (n.contains("avernic") && (n.contains("defender") || n.contains("def"))) return true;
        return n.contains("d defender") || n.contains("d def") || n.contains("drag defender");
    }

    public static boolean isDefender(int itemId, String name) {
        if (containsId(ALL_DEFENDER_IDS, itemId) || isDragonDefender(itemId, name)) return true;
        String n = stripName(name);
        if (n.contains("dragonfire")) return false;
        return n.contains("defender") || n.contains(" def") || n.endsWith("def");
    }

    public static boolean isDefenderOrShield(int itemId, String name) {
        if (isDefender(itemId, name)) return true;
        String n = stripName(name);
        return n.contains("spirit shield") || n.contains("dragonfire")
                || n.contains("wyvern shield") || n.contains("dinh") || n.contains("dfs")
                || isDragonfireWard(itemId, name) || n.contains("buckler") || n.contains("ward")
                || n.contains("shield");
    }

    /** NH switch sort: 0 = main weapon, 1 = offhand, 2 = armor/other. */
    public static int nhEquipPriority(int itemId, String name) {
        if (isNhMainWeapon(itemId, name)) return 0;
        if (isNhOffhand(itemId, name)) return 1;
        return 2;
    }

    /** Ammo must never count as a main-hand (mage/range swaps both e: atlatl darts). */
    public static boolean isAmmo(int itemId, String name) {
        String n = stripName(name);
        if (n.isEmpty()) return false;
        if (n.contains("arrow") || n.contains("bolt") || n.contains("javelin")) return true;
        return n.contains("dart");
    }

    public static boolean isNhMainWeapon(int itemId, String name) {
        if (isAmmo(itemId, name)) return false;
        if (isEclipseAtlatl(itemId, name) || itemId == 28919 || itemId == 28922) return true;
        if (isVls(itemId, name) || isWhip(itemId, name) || isFang(itemId, name)
                || isAgs(itemId, name) || isDragonClaws(itemId, name)
                || isGmaul(itemId, name) || isDragonMace(itemId, name)
                || isDarkBow(itemId, name) || isDragonThrownaxe(itemId, name)
                || isDragonKnife(itemId, name)) return true;
        if (isMageStaff(itemId, name) || isBlueMoonSpear(itemId, name)) return true;
        if (isDharokAxe(itemId, name)) return true;
        String n = stripName(name);
        if (n.contains("atlatl") || n.contains("bow") || n.contains("blowpipe")
                || n.contains("crossbow") || n.contains("ballista")
                || n.contains("chinchompa")) return true;
        if (n.contains("scimitar") || n.contains("claws") || n.contains("dagger")
                || n.contains("sword") || n.contains("mace") || n.contains("maul")
                || n.contains("scythe") || n.contains("rapier") || n.contains("hasta")
                || n.contains("halberd") || n.contains("greataxe")
                || n.contains("warhammer") || n.contains("bludgeon")
                || n.contains("bulwark")) return true;
        return false;
    }

    public static boolean isNhOffhand(int itemId, String name) {
        if (isDragonDefender(itemId, name) || isDefender(itemId, name)) return true;
        String n = stripName(name);
        return n.contains("shield") || n.contains("book") || n.contains("tome")
                || n.contains("buckler") || n.contains("ward") || n.contains("bulwark");
    }

    public static boolean isTankArmor(int itemId, String name) {
        if (isDharokPiece(itemId, name) && !isDharokAxe(itemId, name)) return false;
        String n = stripName(name);
        return n.contains("justiciar") || n.contains("torag") || n.contains("verac")
                || n.contains("karil") || n.contains("fighter torso") || n.contains("fighter hat")
                || n.contains("barrows gloves") || n.contains("ferocious") || n.contains("primordial")
                || n.contains("dinh");
    }

    public static boolean isDharokAxe(int itemId, String name) {
        if (containsId(DH_AXE_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("dharok") && (n.contains("axe") || n.contains("greataxe"));
    }

    public static boolean isDharokPiece(int itemId, String name) {
        if (containsId(DH_AXE_IDS, itemId) || containsId(DH_HELM_IDS, itemId)
                || containsId(DH_BODY_IDS, itemId) || containsId(DH_LEGS_IDS, itemId)) {
            return true;
        }
        return stripName(name).contains("dharok");
    }

    public static boolean isLocatorOrb(int itemId, String name) {
        if (containsId(LOCATOR_ORB_IDS, itemId)) return true;
        return isLocatorLikeName(name);
    }

    /** Roat / custom orb names that are not always "locator orb". */
    public static boolean isLocatorLikeName(String name) {
        if (name == null) return false;
        String n = stripName(name);
        if (n.contains("rock cake") || n.contains("rockcake")) return false;
        if (n.contains("locator") && n.contains("orb")) return true;
        if (n.equals("locator") || n.contains("locator orb")) return true;
        if (n.contains("hitpoint") && n.contains("orb")) return true;
        if (n.contains("hp orb") || n.contains("hp locator")) return true;
        return n.contains("orb") && (n.contains("feel") || n.contains("damage") || n.contains("drain"));
    }

    public static boolean isRockCake(int itemId, String name) {
        if (containsId(ROCK_CAKE_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("rock cake") || n.contains("rockcake")
                || n.contains("dwarven rock");
    }

    /** Locator orb, rock cake, or any item whose menu has Feel/Rub/Guzzle. */
    public static boolean isHpReducer(int itemId, String name) {
        return isLocatorOrb(itemId, name) || isRockCake(itemId, name);
    }

    /** How many locator Feel clicks to reach target HP (10% max HP damage each). */
    public static int locatorClicksToBracket(int hp, int maxHp, int targetHp) {
        if (hp <= targetHp || maxHp <= 0) return 0;
        int step = Math.max(1, maxHp / 10);
        int cur = hp;
        int n = 0;
        while (cur > targetHp && n < 12) {
            cur = Math.max(1, cur - step);
            n++;
        }
        return Math.max(1, n);
    }

    public static final class ComboSlots {
        public final int food, brew, karam;
        public ComboSlots(int food, int brew, int karam) {
            this.food = food; this.brew = brew; this.karam = karam;
        }
    }

    private InventoryTracker() {}

    public static int findSlot(int[] inv, int[] itemIds) {
        if (inv == null || itemIds == null) return -1;
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            for (int want : itemIds) {
                if (id == want) return slot;
            }
        }
        return -1;
    }

    public static boolean containsId(int[] ids, int itemId) {
        if (ids == null) return false;
        for (int id : ids) if (id == itemId) return true;
        return false;
    }

    public static boolean isMarlin(int itemId, String name) {
        if (itemId == 42352) return true;
        return stripName(name).contains("marlin");
    }

    public static boolean isShark(int itemId, String name) {
        if (itemId == 385) return true;
        String n = stripName(name);
        return n.contains("shark") && !n.contains("raw");
    }

    public static boolean isHalibut(int itemId, String name) {
        String n = stripName(name);
        if (n.contains("halibut")) return true;
        // ID-based fallback (covers Roat PKz halibut even when name lookup fails).
        if (containsId(HALIBUT_IDS, itemId)) return !isMarlin(itemId, name);
        return false;
    }

    public static boolean isFood(int itemId, String name) {
        if (isCloser(itemId, name) || isBrew(itemId, name) || isRestore(itemId, name)) return false;
        if (isMarlin(itemId, name) || isShark(itemId, name)) return true;
        if (containsId(FOOD_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("manta") || n.contains("angler") || n.contains("dark crab")
                || n.contains("monkfish") || n.contains("swordfish") || n.contains("lobster")
                || n.contains("rocktail") || n.contains("sea turtle") || n.contains("tuna potato")
                || n.contains("pineapple pizza") || n.contains("summer pie");
    }

    public static boolean isKaram(int itemId, String name) {
        if (containsId(KARAM_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("karambwan") && !n.contains("raw");
    }

    public static boolean isCloser(int itemId, String name) {
        return isKaram(itemId, name) || isHalibut(itemId, name);
    }

    public static boolean isBrew(int itemId, String name) {
        if (containsId(BREW_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("sara") && n.contains("brew");
    }

    public static boolean isSanfew(int itemId, String name) {
        if (containsId(SANFEW_IDS, itemId)) return true;
        return stripName(name).contains("sanfew");
    }

    public static boolean isSuperRestore(int itemId, String name) {
        if (containsId(SUPER_RESTORE_IDS, itemId)) return true;
        String n = stripName(name);
        return n.contains("super restore") || (n.contains("restore potion") && !n.contains("sanfew"));
    }

    /** Any restore-like potion (Sanfew or Super Restore). */
    public static boolean isRestore(int itemId, String name) {
        return isSanfew(itemId, name) || isSuperRestore(itemId, name);
    }

    public static int findMarlinSlot(int[] inv) {
        int marlin = findSlot(inv, MARLIN_IDS);
        if (marlin >= 0) return marlin;
        return findFoodSlot(inv);
    }

    public static int findHalibutSlot(int[] inv) {
        int hali = findSlot(inv, HALIBUT_IDS);
        if (hali >= 0) return hali;
        return findKaramSlot(inv);
    }

    public static int findFoodSlot(int[] inv) {
        int pk = findSlot(inv, PK_FISH_IDS);
        if (pk >= 0) return pk;
        return findSlot(inv, FOOD_IDS);
    }

    /** Brew only — saradomin brew (4-1). */
    public static int findBrewSlot(int[] inv) { return findSlot(inv, BREW_IDS); }
    public static int findSanfewSlot(int[] inv) { return findSlot(inv, SANFEW_IDS); }
    public static int findSuperRestoreSlot(int[] inv) { return findSlot(inv, SUPER_RESTORE_IDS); }
    public static int findRestoreSlot(int[] inv) {
        int sanfew = findSanfewSlot(inv);
        if (sanfew >= 0) return sanfew;
        return findSuperRestoreSlot(inv);
    }
    public static int findKaramSlot(int[] inv) { return findSlot(inv, KARAM_IDS); }
    public static int findAgsSlot(int[] inv) { return findSlot(inv, AGS_IDS); }
    public static int findGmaulSlot(int[] inv) { return findSlot(inv, GMAUL_IDS); }
    public static int findSpecWeaponSlot(int[] inv) { return findSlot(inv, SPEC_WEAPON_IDS); }

    public static ComboSlots resolveComboEat(int[] inv, int fallbackFood, int fallbackBrew, int fallbackKaram) {
        int food  = findMarlinSlot(inv);
        int karam = findHalibutSlot(inv);
        if (food < 0) food = fallbackFood;
        if (karam < 0) karam = fallbackKaram;
        return new ComboSlots(food, -1, karam);
    }

    private static int[] concat(int[]... arrays) {
        int n = 0;
        for (int[] a : arrays) n += a.length;
        int[] out = new int[n];
        int i = 0;
        for (int[] a : arrays) {
            System.arraycopy(a, 0, out, i, a.length);
            i += a.length;
        }
        return out;
    }
}
