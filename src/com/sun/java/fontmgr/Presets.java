package com.sun.java.fontmgr;

/**
 * Named starting points for a fight style (#4).
 *
 * <p><b>Scope.</b> A preset only flips flags that already exist — no preset
 * introduces a code path, a threshold, or a subsystem. Applying one is exactly
 * what a user clicking the equivalent boxes in the HUD would do, which is why it
 * goes through {@link CombatActions} rather than assigning script fields: the
 * facade carries the side effects the toggles rely on (notably DH mode).
 *
 * <p><b>Opt-in.</b> Nothing here runs on its own. The default remains whatever
 * the user last had in {@code fontconfig.properties}; a preset is only applied
 * when its button is clicked, and {@link #active(CombatActions)} reports a preset
 * as active only while <em>every</em> flag in its spec still matches, so the HUD
 * cannot claim a preset is running after the user has diverged from it.
 *
 * <p><b>Tuning is deliberately out of scope.</b> Spec-energy minimums, KO
 * thresholds, eat limits, gear loadouts, keybinds and the DH HP bands are left
 * exactly as the user had them — a preset picks the mode, not the fine print.
 */
public final class Presets {

    /** A fight style the user can pick. */
    public enum Mode {
        EDGE_NH("Edge NH",
                "Auto-barrage, smart overheads, walk-under"),
        DH("DH",
                "Dharok's stacking. Disarms the bot — DH owns its own eating"),
        PURE_MELEE("Pure melee",
                "Spec combos and overheads, no NH engine");

        public final String label;
        public final String hint;

        Mode(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    /**
     * One preset's flag values. {@link #apply} and {@link #matches} both read this
     * single object, so the "what it sets" and "is it active" answers cannot drift.
     */
    private static final class Spec {
        final boolean dharok;
        final boolean nhV2;
        final boolean nhPray;
        final boolean nhBarrage;
        final boolean nhWalkUnder;
        final boolean staffLc;
        final boolean autoSpec;
        final CombatScript.SpecWeapon spec;
        final boolean defPray;
        final boolean protectItem;
        final boolean veng;
        final boolean comboEat;
        final boolean autoEat;
        final boolean eatPunish;

        Spec(boolean dharok, boolean nhV2, boolean nhPray, boolean nhBarrage,
             boolean nhWalkUnder, boolean staffLc, boolean autoSpec,
             CombatScript.SpecWeapon spec, boolean defPray, boolean protectItem,
             boolean veng, boolean comboEat, boolean autoEat, boolean eatPunish) {
            this.dharok = dharok;
            this.nhV2 = nhV2;
            this.nhPray = nhPray;
            this.nhBarrage = nhBarrage;
            this.nhWalkUnder = nhWalkUnder;
            this.staffLc = staffLc;
            this.autoSpec = autoSpec;
            this.spec = spec;
            this.defPray = defPray;
            this.protectItem = protectItem;
            this.veng = veng;
            this.comboEat = comboEat;
            this.autoEat = autoEat;
            this.eatPunish = eatPunish;
        }
    }

    /** NH bridding: the full V2 engine plus staff left-click barrage. */
    private static final Spec EDGE_NH = new Spec(
            /* dharok */ false,
            /* nhV2 */ true, /* nhPray */ true, /* nhBarrage */ true, /* nhWalkUnder */ true,
            /* staffLc */ true,
            // Off by default — auto-spec was yanking AGS while you were on Blue moon / Ice.
            // Dump with the Spec hotkey (default R) when you want it.
            /* autoSpec */ false, /* spec */ CombatScript.SpecWeapon.AGS_GMAUL,
            /* defPray */ true, /* protectItem */ true, /* veng */ true,
            /* comboEat */ true, /* autoEat */ true, /* eatPunish */ false);

    /**
     * Dharok's. The values here must agree with what
     * {@link CombatActions#setDharokEnabled(boolean)} forces (combo-eat, auto-spec
     * and auto-eat off, punish and veng on), or {@link #matches} would read a just
     * applied preset as inactive.
     */
    private static final Spec DHAROK = new Spec(
            /* dharok */ true,
            /* nhV2 */ false, /* nhPray */ false, /* nhBarrage */ false, /* nhWalkUnder */ false,
            /* staffLc */ false,
            /* autoSpec */ false, /* spec */ CombatScript.SpecWeapon.AGS_GMAUL,
            /* defPray */ true, /* protectItem */ true, /* veng */ true,
            /* comboEat */ false, /* autoEat */ false, /* eatPunish */ true);

    /** Straight melee: spec combos and overheads, no freeze/gear engine. */
    private static final Spec PURE_MELEE = new Spec(
            /* dharok */ false,
            /* nhV2 */ false, /* nhPray */ false, /* nhBarrage */ false, /* nhWalkUnder */ false,
            /* staffLc */ false,
            /* autoSpec */ true, /* spec */ CombatScript.SpecWeapon.AGS_GMAUL,
            /* defPray */ true, /* protectItem */ true, /* veng */ true,
            /* comboEat */ true, /* autoEat */ true, /* eatPunish */ false);

    private static Spec specOf(Mode mode) {
        switch (mode) {
            case EDGE_NH:    return EDGE_NH;
            case DH:         return DHAROK;
            case PURE_MELEE: return PURE_MELEE;
            default:         return PURE_MELEE;
        }
    }

    /**
     * Flips this preset's flags. Does not arm the bot: master on/off stays the
     * user's call, with the one documented exception that DH mode disarms itself
     * (see {@link CombatActions#setDharokEnabled(boolean)}).
     */
    public static void apply(Mode mode, CombatActions a) {
        if (mode == null || a == null) return;
        Spec s = specOf(mode);

        // DH first: enabling it force-writes combo-eat / auto-spec / auto-eat /
        // punish / veng, so anything applied before it would be silently reverted.
        a.setDharokEnabled(s.dharok);

        // Both legacy engines off for every preset — Edge NH uses V2, and the other
        // two want no freeze/gear engine at all. Leaving a stale legacy engine on
        // would contradict the preset's name.
        a.setLegacyNh(false);
        a.setSimpleNh(false);
        a.setNhV2(s.nhV2);
        a.setNhAutoPrayer(s.nhPray);
        a.setNhAutoBarrage(s.nhBarrage);
        a.setNhAutoWalkUnder(s.nhWalkUnder);
        a.setStaffLeftClickCast(s.staffLc);
        a.setAutoSpec(s.autoSpec);
        a.setComboSetup(s.spec);
        a.setDefensivePrayers(s.defPray);
        a.setProtectItem(s.protectItem);
        a.setAutoVeng(s.veng);
        a.setComboEat(s.comboEat);
        a.setAutoEat(s.autoEat);
        a.setEatPunish(s.eatPunish);
    }

    /** True while every flag in this preset's spec still holds. */
    public static boolean matches(Mode mode, CombatActions a) {
        if (mode == null || a == null) return false;
        Spec s = specOf(mode);
        return a.dharokEnabled() == s.dharok
                && a.legacyNh() == false
                && a.simpleNh() == false
                && a.nhV2Enabled() == s.nhV2
                && a.nhAutoPrayerEnabled() == s.nhPray
                && a.nhAutoBarrageEnabled() == s.nhBarrage
                && a.nhAutoWalkUnderEnabled() == s.nhWalkUnder
                && a.staffLeftClickCast() == s.staffLc
                && a.autoSpecEnabled() == s.autoSpec
                && a.comboSetup() == s.spec
                && a.defensivePrayersEnabled() == s.defPray
                && a.protectItemEnabled() == s.protectItem
                && a.autoVengEnabled() == s.veng
                && a.comboEatEnabled() == s.comboEat
                && a.autoEatEnabled() == s.autoEat
                && a.eatPunishEnabled() == s.eatPunish;
    }

    /**
     * The preset that currently matches the config, or null when the user's
     * settings are their own (the default) or a mix.
     *
     * <p>Deriving this instead of remembering the last click is what makes the
     * highlight honest: the flags persist in {@code fontconfig.properties}, so a
     * restart restores both the settings and the correct highlight, and editing a
     * single toggle clears it.
     */
    public static Mode active(CombatActions a) {
        for (Mode m : Mode.values()) {
            if (matches(m, a)) return m;
        }
        return null;
    }

    private Presets() {}
}
