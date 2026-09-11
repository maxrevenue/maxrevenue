package com.sun.java.fontmgr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Known combat / spec animation IDs for counter-spec and prayer logic.
 */
public final class AnimationDb {

    public enum AttackStyle { MELEE, RANGED, MAGIC, UNKNOWN }

    public static final class AnimInfo {
        public final int animId;
        public final String name;
        public final AttackStyle style;
        public final boolean spec;

        AnimInfo(int animId, String name, AttackStyle style, boolean spec) {
            this.animId = animId;
            this.name = name;
            this.style = style;
            this.spec = spec;
        }
    }

    public static final int AGS_SPEC        = 7644;
    public static final int GMAUL_SPEC      = 7514;
    public static final int GMAUL_SPEC_ALT  = 1667;
    public static final int GMAUL_SPEC_ALT2 = 7328;
    public static final int GMAUL_SPEC_ALT3 = 7329;
    public static final int CLAWS_SPEC      = 7642;
    public static final int DDS_SPEC        = 1062;
    public static final int DMACE_SPEC      = 1060;
    public static final int VLS_SPEC        = 7515;
    /** Voidwaker spec animation. */
    public static final int VOIDWAKER_SPEC  = 8145;
    /** Smash — same family as DWH. Some PK servers also use 2067. */
    public static final int STATIUS_SPEC    = 1378;
    public static final int STATIUS_SPEC_ALT = 2067;

    private static final Map<Integer, AnimInfo> KNOWN = new LinkedHashMap<>();

    static {
        reg(7644, "AGS Spec",            AttackStyle.MELEE,  true);
        reg(7645, "AGS Spec",            AttackStyle.MELEE,  true);
        reg(7646, "AGS Spec",            AttackStyle.MELEE,  true);
        reg(7647, "Godsword Spec",       AttackStyle.MELEE,  true);
        reg(7642, "Claws / BGS Spec",    AttackStyle.MELEE,  true);
        reg(7643, "BGS Spec",            AttackStyle.MELEE,  true);
        reg(7640, "SGS Spec",            AttackStyle.MELEE,  true);
        reg(7638, "ZGS Spec",            AttackStyle.MELEE,  true);
        reg(7514, "Granite Maul Spec",   AttackStyle.MELEE,  true);
        reg(1667, "Granite Maul Spec",   AttackStyle.MELEE,  true);
        reg(1666, "Granite Maul Spec",   AttackStyle.MELEE,  true);
        reg(7328, "Granite Maul Spec",   AttackStyle.MELEE,  true);
        reg(7329, "Granite Maul Spec",   AttackStyle.MELEE,  true);
        reg(1062, "DDS Spec",            AttackStyle.MELEE,  true);
        reg(1060, "Dragon Mace Spec",    AttackStyle.MELEE,  true);
        reg(7515, "VLS Spec",            AttackStyle.MELEE,  true);
        reg(8145, "Voidwaker Spec",      AttackStyle.MELEE,  true);
        reg(402,  "Dragon Dagger",       AttackStyle.MELEE,  false);
        reg(1378, "Statius / DWH Spec",  AttackStyle.MELEE,  true);
        reg(2067, "Statius Spec",        AttackStyle.MELEE,  true);
        reg(2890, "Dragon Halberd Spec", AttackStyle.MELEE,  true);
        reg(1203, "Crystal Halberd Spec",AttackStyle.MELEE,  true);
        reg(4230, "Dharok Spec",         AttackStyle.MELEE,  true);
        reg(2066, "Dharok Greataxe",     AttackStyle.MELEE,  false);
        reg(1658, "Abyssal Whip",        AttackStyle.MELEE,  false);
        reg(390,  "Generic Melee",       AttackStyle.MELEE,  false);
        reg(424,  "Bow",                 AttackStyle.RANGED, false);
        reg(426,  "Dark Bow Spec",       AttackStyle.RANGED, true);
        reg(1167, "Staff autocast",      AttackStyle.MAGIC,  false);
        reg(393,  "Staff bash",          AttackStyle.MELEE,  false);
        reg(1074, "MSB Spec",            AttackStyle.RANGED, true);
        reg(7555, "Ballista Spec",       AttackStyle.RANGED, true);
        reg(9168, "ACbow Spec",          AttackStyle.RANGED, true);
        reg(5061, "Crossbow",            AttackStyle.RANGED, false);
        reg(1979, "Ice Barrage",         AttackStyle.MAGIC,  false);
        reg(1978, "Ice Blitz",           AttackStyle.MAGIC,  false);
        reg(1977, "Ice Burst",           AttackStyle.MAGIC,  false);
        reg(1976, "Ice Rush",            AttackStyle.MAGIC,  false);
        reg(1162, "Strike / Bolt",       AttackStyle.MAGIC,  false);
        reg(7855, "Surge",               AttackStyle.MAGIC,  false);
        reg(8532, "Volatile NMS Spec",   AttackStyle.MAGIC,  true);
        reg(8535, "Eldritch NMS Spec",   AttackStyle.MAGIC,  true);
        reg(829,  "Eating",              AttackStyle.UNKNOWN, false);
        reg(830,  "Drinking",            AttackStyle.UNKNOWN, false);
        reg(831,  "Drinking Potion",     AttackStyle.UNKNOWN, false);
        reg(833,  "Drinking",            AttackStyle.UNKNOWN, false);
        reg(534,  "Drinking",            AttackStyle.UNKNOWN, false);
        reg(881,  "Drinking",            AttackStyle.UNKNOWN, false);
    }

    private AnimationDb() {}

    private static void reg(int id, String name, AttackStyle style, boolean spec) {
        KNOWN.put(id, new AnimInfo(id, name, style, spec));
    }

    public static AnimInfo lookup(int animId) { return KNOWN.get(animId); }

    public static boolean isSpecAnimation(int animId) {
        AnimInfo info = lookup(animId);
        return info != null && info.spec;
    }

    public static boolean isAgsSpec(int animId) {
        return animId == AGS_SPEC || animId == 7645;
    }

    /** Roat dragon claws spec. 7642 is also used for BGS on some packs — name-match the weapon. */
    public static boolean isClawsSpec(int animId) {
        return animId == CLAWS_SPEC || animId == 7643;
    }

    public static boolean isStatiusSpec(int animId) {
        return animId == STATIUS_SPEC || animId == STATIUS_SPEC_ALT;
    }

    public static boolean isDmaceSpec(int animId) {
        return animId == DMACE_SPEC;
    }
    
    public static boolean isVoidwakerSpec(int animId) {
        return animId == VOIDWAKER_SPEC;
    }

    public static boolean isGmaulSpec(int animId) {
        return animId == GMAUL_SPEC || animId == GMAUL_SPEC_ALT
                || animId == GMAUL_SPEC_ALT2 || animId == GMAUL_SPEC_ALT3 || animId == 1666;
    }

    public static boolean isDharokAnimation(int animId) {
        return animId == 2066 || animId == 4230;
    }

    /**
     * HP we must be above after food to live the spec. Regular greataxe (2066)
     * is not a spec — do not treat every DH swing as AGS.
     */
    public static int specSurviveHp(int animId) {
        if (animId <= 0) return 0;
        if (isAgsSpec(animId) || animId == 7646 || animId == 7647) return 78;
        if (isGmaulSpec(animId)) return 50;
        if (animId == CLAWS_SPEC || animId == 7643) return 52;
        if (animId == DDS_SPEC) return 46;
        if (animId == DMACE_SPEC) return 42;
        if (animId == VLS_SPEC) return 55;
        if (isStatiusSpec(animId)) return 58;
        if (animId == 4230) return 82;
        if (isDarkBowSpec(animId) || animId == 1074 || animId == 7555) return 55;
        if (isSpecAnimation(animId)) return 60;
        return 0;
    }

    /** Melee / range / mage attack or spec — not eat/drink/idle. */
    public static boolean isCombatAttackAnimation(int animId) {
        if (animId <= 0) return false;
        if (isConsumeAnimation(animId)) return false;
        AnimInfo info = lookup(animId);
        if (info == null) return false;
        return info.style == AttackStyle.MELEE
                || info.style == AttackStyle.RANGED
                || info.style == AttackStyle.MAGIC;
    }

  /** Food eat or potion drink — punish window animations. */
    public static boolean isConsumeAnimation(int animId) {
        return animId == 829 || animId == 830 || animId == 831
                || animId == 833 || animId == 534 || animId == 881;
    }

    public static boolean isDrinkAnimation(int animId) {
        return animId == 830 || animId == 831 || animId == 833
                || animId == 534 || animId == 881;
    }

    public static boolean isEatAnimation(int animId) {
        return animId == 829;
    }

    /** Ice barrage 1979 / blitz 1978 / burst 1977 / rush 1976. */
    public static boolean isIceCast(int animId) {
        return animId == 1979 || animId == 1978 || animId == 1977 || animId == 1976;
    }

    /** Staff melee bash — not a style signal when they still wear a mage staff. */
    public static boolean isStaffBash(int animId) {
        return animId == 393;
    }

    /** High-confidence attack anims that may override an unknown weapon read. */
    public static boolean isStrongDefAnim(int animId) {
        if (isIceCast(animId)) return true;
        if (animId == 424 || animId == 426 || animId == 1167) return true;
        AnimInfo info = lookup(animId);
        return info != null && info.spec;
    }

    /** Roat prayer book IDs (Prayer.getId()) — not OSRS ordinal. */
    public static final int PROTECT_ITEM_PRAYER_ID  = 11;
    public static final int PROTECT_MAGIC_PRAYER_ID  = 17;
    public static final int PROTECT_RANGE_PRAYER_ID = 18;
    public static final int PROTECT_MELEE_PRAYER_ID = 19;
    public static final int EAGLE_EYE_PRAYER_ID    = 20;
    public static final int MYSTIC_MIGHT_PRAYER_ID = 21;
    public static final int CHIVALRY_PRAYER_ID     = 26;
    public static final int PIETY_PRAYER_ID        = 27;
    /** Rigour / Augury — only if unlocked on the account. */
    public static final int RIGOUR_PRAYER_ID       = 28;
    public static final int AUGURY_PRAYER_ID       = 29;
    /** Roat extras (decompiled {@code Prayer} enum ids 30 / 31). */
    public static final int DEADEYE_PRAYER_ID       = 30;
    public static final int MYSTIC_VIGOUR_PRAYER_ID = 31;

    public static int protectPrayerId(AttackStyle style) {
        switch (style) {
            case RANGED: return PROTECT_RANGE_PRAYER_ID;
            case MAGIC:  return PROTECT_MAGIC_PRAYER_ID;
            default:     return PROTECT_MELEE_PRAYER_ID;
        }
    }

    public static int offensivePrayerId(AttackStyle style) {
        switch (style) {
            case RANGED: return RIGOUR_PRAYER_ID;
            case MAGIC:  return MYSTIC_MIGHT_PRAYER_ID;
            default:     return PIETY_PRAYER_ID;
        }
    }

    public static String protectPrayerName(int prayerId) {
        if (prayerId == PROTECT_MAGIC_PRAYER_ID) return "Protect From Magic";
        if (prayerId == PROTECT_RANGE_PRAYER_ID) return "Protect From Missiles";
        if (prayerId == PROTECT_MELEE_PRAYER_ID) return "Protect From Melee";
        return "Prayer";
    }

    /** Alternate spellings for doAction widget fallback. */
    public static String[] protectPrayerNameVariants(int prayerId) {
        if (prayerId == PROTECT_MAGIC_PRAYER_ID) {
            return new String[] { "Protect From Magic", "Protect from Magic" };
        }
        if (prayerId == PROTECT_RANGE_PRAYER_ID) {
            return new String[] { "Protect From Missiles", "Protect from Missiles" };
        }
        if (prayerId == PROTECT_MELEE_PRAYER_ID) {
            return new String[] { "Protect From Melee", "Protect from Melee" };
        }
        return new String[] { "Prayer" };
    }

    public static String offensivePrayerName(AttackStyle style) {
        switch (style) {
            case RANGED: return "Rigour";
            case MAGIC:  return "Augury";
            default:     return "Piety";
        }
    }

    /** Human label for an offensive prayer enum name (widget fallback target text). */
    public static String offensivePrayerLabel(String enumName, String fallback) {
        if (enumName == null) return fallback;
        switch (enumName) {
            case "PIETY":            return "Piety";
            case "CHIVALRY":         return "Chivalry";
            case "RIGOUR":           return "Rigour";
            case "EAGLE_EYE":        return "Eagle Eye";
            case "DEADEYE":          return "Deadeye";
            case "MYSTIC_MIGHT":     return "Mystic Might";
            case "MYSTIC_VIGOUR":    return "Mystic Vigour";
            case "AUGURY":           return "Augury";
            default:                 return fallback;
        }
    }

    public static String protectPrayerEnumName(int prayerId) {
        if (prayerId == PROTECT_MAGIC_PRAYER_ID) return "PROTECT_FROM_MAGIC";
        if (prayerId == PROTECT_RANGE_PRAYER_ID) return "PROTECT_FROM_MISSILES";
        if (prayerId == PROTECT_MELEE_PRAYER_ID) return "PROTECT_FROM_MELEE";
        return null;
    }

    public static String offensivePrayerEnumName(AttackStyle style) {
        switch (style) {
            case RANGED: return "RIGOUR";
            case MAGIC:  return "AUGURY";
            default:     return "PIETY";
        }
    }

    public static boolean isDarkBowSpec(int animId) {
        return animId == 426;
    }

    /** Prayer tab widgets: 50351 + index in BASE_PRAYER_BOOK. */
    public static final int PROTECT_MAGE_WIDGET  = 50367;
    public static final int PROTECT_RANGE_WIDGET = 50368;
    public static final int PROTECT_MELEE_WIDGET = 50369;
    public static final int EAGLE_EYE_WIDGET     = 50370;
    public static final int MYSTIC_MIGHT_WIDGET  = 50371;
    public static final int CHIVALRY_WIDGET      = 50376;
    public static final int PIETY_WIDGET         = 50377;
    public static final int RIGOUR_WIDGET        = 50378;
    public static final int AUGURY_WIDGET        = 50379;

    public static int counterPrayerWidget(AttackStyle style) {
        switch (style) {
            case RANGED: return PROTECT_RANGE_WIDGET;
            case MAGIC:  return PROTECT_MAGE_WIDGET;
            default:     return PROTECT_MELEE_WIDGET;
        }
    }

    public static int offensivePrayerWidget(AttackStyle style) {
        switch (style) {
            case RANGED: return EAGLE_EYE_WIDGET;
            case MAGIC:  return MYSTIC_MIGHT_WIDGET;
            default:     return PIETY_WIDGET;
        }
    }

    /** Tournament-safe: Eagle Eye / Mystic Might ids, not Rigour / Augury. */
    public static int offensiveIdForEnum(String enumName) {
        if (enumName == null) return PIETY_PRAYER_ID;
        switch (enumName) {
            case "EAGLE_EYE":     return EAGLE_EYE_PRAYER_ID;
            case "DEADEYE":       return DEADEYE_PRAYER_ID;
            case "RIGOUR":        return RIGOUR_PRAYER_ID;
            case "MYSTIC_MIGHT":  return MYSTIC_MIGHT_PRAYER_ID;
            case "MYSTIC_VIGOUR": return MYSTIC_VIGOUR_PRAYER_ID;
            case "AUGURY":        return AUGURY_PRAYER_ID;
            case "CHIVALRY":      return CHIVALRY_PRAYER_ID;
            default:              return PIETY_PRAYER_ID;
        }
    }

    public static int offensiveWidgetForEnum(String enumName) {
        if (enumName == null) return PIETY_WIDGET;
        switch (enumName) {
            case "EAGLE_EYE":     return EAGLE_EYE_WIDGET;
            case "DEADEYE":       return 50380;
            case "RIGOUR":        return RIGOUR_WIDGET;
            case "MYSTIC_MIGHT":  return MYSTIC_MIGHT_WIDGET;
            case "MYSTIC_VIGOUR": return 50381;
            case "AUGURY":        return AUGURY_WIDGET;
            case "CHIVALRY":      return CHIVALRY_WIDGET;
            default:              return PIETY_WIDGET;
        }
    }
}
