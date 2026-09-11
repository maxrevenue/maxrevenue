package com.sun.java.fontmgr;

/**
 * Action and configuration facade over {@link CombatScript}, for consumers that
 * are not the tick thread: the Swing HUD, the command socket, the swap panel.
 *
 * <p>Two responsibilities are deliberately split apart:
 * <ul>
 *   <li>{@link CombatState} is the immutable per-tick <em>read</em> model, so a
 *       reader can never observe a half-updated tick.</li>
 *   <li>this class is every <em>write</em> the outside world is allowed to make:</li>
 * </ul>
 * toggles, one-shot commands and master switches. Callers no longer reach into
 * CombatScript fields, and no longer need the {@code *Public()} accessor
 * wrappers (those stay only for the handful of in-package collaborators that
 * predate this class).
 *
 * <p>Pure delegation: no combat decision is taken here, and nothing here sits on
 * the tick decision path. The ordering-critical sequencing (axe/orb/eat, DH
 * bands, spec dumps) stays entirely inside {@link CombatScript}.
 */
public final class CombatActions {

    private final CombatScript script;

    CombatActions(CombatScript script) {
        this.script = script;
    }

    // -- Master switches -----------------------------------------------------

    /**
     * Master auto-eat switch. Gates ALL automatic eating (DH band and triple
     * eats, post-axe combo, NH spec-survive, DH-stack, predictive, combo-eat);
     * manual keys 1-4 always work. Single place the flag and its HUD
     * breadcrumb change, so the HUD, the hotkey and the saved config cannot
     * disagree.
     */
    public void setAutoEat(boolean on) {
        script.autoEatEnabled = on;
        if (!on) script.lastAction = "AUTO_EAT_OFF";
    }

    public void setMasterEnabled(boolean on) { script.enabled = on; }

    /**
     * DH mode toggle. Turning it on also parks the regular-PK bot and combo-eat
     * so greataxe max hit is not capped by auto-eating — same side effects the
     * HUD used to apply inline.
     */
    public void setDharokEnabled(boolean on) {
        script.dharokEnabled = on;
        if (on) {
            script.enabled = false;
            script.comboEatEnabled = false;
            script.dharokAutoEat = false;
            script.autoSpecEnabled = false;
            script.dharokUseOrb = false;
            script.dharokAutoStack = false;
            script.eatPunishEnabled = true;
            script.autoVengEnabled = true;
        }
    }

    public void setAutoSpec(boolean on) {
        script.autoSpecEnabled = on;
        if (!on) {
            script.abortComboStatePublic();
            script.clearActionQueue();
            FontManager.log("[Combat] Auto Spec OFF — cleared pending AGS dumps");
        }
    }
    public void setEatPunish(boolean on)            { script.eatPunishEnabled = on; }
    public void setAutoVeng(boolean on)             { script.autoVengEnabled = on; }
    public void setDefensivePrayers(boolean on)       { script.defensivePrayersEnabled = on; }
    /** (#2) Trust a gear-corroborated switch for the defensive overhead. */
    public void setGearCorroboratedDefPrayer(boolean on) { script.gearCorroboratedDefPrayer = on; }

    // ── Preset surface (#4) ──────────────────────────────────────────────────
    // Thin setters so {@link Presets} never assigns script fields directly and
    // cannot drift from the HUD's own toggle behaviour. Anything with a side
    // effect routes through the existing toggle rather than bypassing it.

    /** Goes through {@link CombatScript#toggleNhV2()} to keep the engines exclusive. */
    public void setNhV2(boolean on)             { if (on != script.nhV2Enabled) script.toggleNhV2(); }
    public void setNhAutoPrayer(boolean on)     { script.nhAutoPrayerEnabled = on; }
    public void setNhAutoBarrage(boolean on)    { script.nhAutoBarrageEnabled = on; }
    public void setNhAutoWalkUnder(boolean on)  { if (on != script.nhAutoWalkUnderEnabled) script.toggleNhAutoWalkUnder(); }
    public void setLegacyNh(boolean on)         { script.nhEnabled = on; }
    public void setSimpleNh(boolean on)         { script.simpleNHEnabled = on; }
    /** Direct spec-setup selection; {@code toggleComboSetup} only cycles, it cannot target one. */
    public void setComboSetup(CombatScript.SpecWeapon weapon) { if (weapon != null) script.selectedSpec = weapon; }

    public boolean legacyNh()                   { return script.nhEnabled; }
    public boolean simpleNh()                   { return script.simpleNHEnabled; }
    public CombatScript.SpecWeapon comboSetup() { return script.selectedSpec; }
    public void setComboEat(boolean on)             { script.comboEatEnabled = on; }
    public void setProtectItem(boolean on)          { script.autoProtectItemEnabled = on; }
    public void setStaffLeftClickCast(boolean on)   { script.staffLcCast = on; }
    public void setAnimTrigger(boolean on)          { script.animTriggerEnabled = on; }
    public void setDamageTrigger(boolean on)        { script.damageTriggerEnabled = on; }
    public void setDamageTriggerMin(int v)         { script.damageTriggerMin = v; }
    public void setAnimTriggerAnim(int v)          { script.animTriggerAnim = v; }
    public void setAgsMinSpecPct(int v)             { script.agsMinSpecPct = v; }
    public void setDmaceMinSpecPct(int v)           { script.dmaceMinSpecPct = v; }
    public void setNhKoHp(int v)                    { script.nhKoHp = v; }

    public boolean autoEatEnabled()           { return script.autoEatEnabled; }
    public boolean masterEnabled()            { return script.enabled; }
    public boolean dharokEnabled()            { return script.dharokEnabled; }
    public boolean autoSpecEnabled()          { return script.autoSpecEnabled; }
    public boolean eatPunishEnabled()         { return script.eatPunishEnabled; }
    public boolean autoVengEnabled()          { return script.autoVengEnabled; }
    public boolean defensivePrayersEnabled()  { return script.defensivePrayersEnabled; }
    public boolean gearCorroboratedDefPrayer() { return script.gearCorroboratedDefPrayer; }
    public boolean comboEatEnabled()          { return script.comboEatEnabled; }
    public boolean protectItemEnabled()       { return script.autoProtectItemEnabled; }
    public boolean staffLeftClickCast()       { return script.staffLcCast; }
    public boolean animTriggerEnabled()        { return script.animTriggerEnabled; }
    public boolean damageTriggerEnabled()      { return script.damageTriggerEnabled; }
    public boolean nhV2Enabled()              { return script.nhV2Enabled; }
    public boolean nhAutoPrayerEnabled()      { return script.nhAutoPrayerEnabled; }
    public boolean nhAutoBarrageEnabled()     { return script.nhAutoBarrageEnabled; }
    public boolean nhAutoWalkUnderEnabled()   { return script.nhAutoWalkUnderEnabled; }
    public int damageTriggerMin()            { return script.damageTriggerMin; }
    public int animTriggerAnim()              { return script.animTriggerAnim; }
    public int agsMinSpecPct()               { return script.agsMinSpecPct; }
    /** Spec energy the currently selected combo needs, for the HUD "Spec ready" chip. */
    public int primaryMinSpecPct()            { return script.primaryMinSpecPct(); }
    public int dmaceMinSpecPct()             { return script.dmaceMinSpecPct; }
    public int nhKoHp()                      { return script.nhKoHp; }
    public int meleeStr()                    { return script.readMeleeStrPublic(); }

    // -- Toggles -------------------------------------------------------------

    public void toggleComboSetup()        { script.toggleComboSetup(); }
    public void toggleNhV2()              { script.toggleNhV2(); }
    public void toggleNhAutoPrayer()      { script.nhAutoPrayerEnabled = !script.nhAutoPrayerEnabled; }
    public void toggleNhAutoBarrage()     { script.nhAutoBarrageEnabled = !script.nhAutoBarrageEnabled; }
    public void toggleNhAutoWalkUnder()   { script.toggleNhAutoWalkUnder(); }
    public void toggleDefensivePrayers()   { script.defensivePrayersEnabled = !script.defensivePrayersEnabled; }
    public void toggleAutoSpec() {
        if (!script.dharokEnabled) script.autoSpecEnabled = !script.autoSpecEnabled;
    }
    public void toggleAutoVeng()           { script.autoVengEnabled = !script.autoVengEnabled; }
    public void toggleEatPunish()          { script.eatPunishEnabled = !script.eatPunishEnabled; }
    public void toggleProtectItem()        { script.autoProtectItemEnabled = !script.autoProtectItemEnabled; }
    public void toggleStaffLeftClickCast() { script.staffLcCast = !script.staffLcCast; }
    public void toggleComboEat()           { script.comboEatEnabled = !script.comboEatEnabled; }

    // -- One-shot commands ---------------------------------------------------

    public void forceNhBarrage()          { script.nhV2ForceBarrage(); }
    public boolean walkUnderNow()         { return script.nhWalkUnderNow(); }
    public void testPrayerSwitch()        { script.testPrayerSwitch(); }
    public void fireSpec()               { script.executeSpec(); }

    // -- Derived labels for the HUD -----------------------------------------

    public String comboSetupName()        { return script.comboSetupName(); }
    public String nhV2Status()            { return script.getNhV2Status(); }
}
