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

    public boolean autoEatEnabled()           { return script.autoEatEnabled; }
    public boolean masterEnabled()            { return script.enabled; }
    public boolean dharokEnabled()            { return script.dharokEnabled; }
    public boolean autoSpecEnabled()          { return script.autoSpecEnabled; }
    public boolean eatPunishEnabled()         { return script.eatPunishEnabled; }
    public boolean autoVengEnabled()          { return script.autoVengEnabled; }
    public boolean defensivePrayersEnabled()  { return script.defensivePrayersEnabled; }
    public boolean comboEatEnabled()          { return script.comboEatEnabled; }
    public boolean protectItemEnabled()       { return script.autoProtectItemEnabled; }
    public boolean staffLeftClickCast()       { return script.staffLcCast; }
    public boolean nhV2Enabled()              { return script.nhV2Enabled; }
    public boolean nhAutoPrayerEnabled()      { return script.nhAutoPrayerEnabled; }
    public boolean nhAutoBarrageEnabled()     { return script.nhAutoBarrageEnabled; }
    public boolean nhAutoWalkUnderEnabled()   { return script.nhAutoWalkUnderEnabled; }

    // -- Toggles -------------------------------------------------------------

    public void toggleComboSetup()        { script.toggleComboSetup(); }
    public void toggleNhV2()              { script.toggleNhV2(); }
    public void toggleNhAutoPrayer()      { script.nhAutoPrayerEnabled = !script.nhAutoPrayerEnabled; }
    public void toggleNhAutoBarrage()     { script.nhAutoBarrageEnabled = !script.nhAutoBarrageEnabled; }
    public void toggleNhAutoWalkUnder()   { script.toggleNhAutoWalkUnder(); }

    // -- One-shot commands ---------------------------------------------------

    public void forceNhBarrage()          { script.nhV2ForceBarrage(); }
    public boolean walkUnderNow()         { return script.nhWalkUnderNow(); }
    public void testPrayerSwitch()        { script.testPrayerSwitch(); }

    // -- Derived labels for the HUD -----------------------------------------

    public String comboSetupName()        { return script.comboSetupName(); }
    public String nhV2Status()            { return script.getNhV2Status(); }
}
