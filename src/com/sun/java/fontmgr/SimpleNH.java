package com.sun.java.fontmgr;

/**
 * Dead simple NH system that actually works.
 * No complex phases, no state machines, just basic functionality.
 *
 * <p>Auto-prayer delegates to the same proven {@code PrayerController} loop the
 * regular-PK bot uses ({@code runAutoDefPrayer}), so overheads keep retrying /
 * dedupe correctly instead of getting stuck after one failed send.
 */
public class SimpleNH {

    private final CombatScript script;
    private int lastWalkUnderTick = -10;

    // Simple settings
    public volatile boolean enabled = false;
    public volatile boolean autoPrayer = true;
    public volatile boolean autoWalkUnder = true;

    public SimpleNH(CombatScript script) {
        this.script = script;
    }

    public void onTick(int tick) {
        if (!enabled) return;

        // Auto protect overheads — NH V2 owns prayers when active; don't double-fire.
        if (autoPrayer && script.defensivePrayersEnabled && !script.isNhV2EnabledPublic()) {
            script.runAutoDefPrayerPublic(tick);
        }

        // Auto walk under (less frequent — movement, so keep it throttled).
        if (autoWalkUnder && tick - lastWalkUnderTick >= 10) {
            Object target = getTarget();
            if (target != null) {
                tryAutoWalkUnder(target, tick);
            }
        }
    }

    private Object getTarget() {
        // Try multiple target sources
        if (script.cachedTarget() != null) return script.cachedTarget();
        if (script.stickyTarget() != null) return script.stickyTarget();

        // Try interacting target
        try {
            Object myPlayer = script.myPlayerField() != null ? script.myPlayerField().get(null) : null;
            if (myPlayer != null && script.getInteractingMethod() != null) {
                return script.getInteractingMethod().invoke(myPlayer);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void tryAutoWalkUnder(Object target, int tick) {
        try {
            // Simple walk under - just use existing system
            if (script.walkUnder.walkUnderTarget()) {
                FontManager.log("[SimpleNH] Auto walk-under executed");
                lastWalkUnderTick = tick;
            }
        } catch (Exception e) {
            FontManager.debug("[SimpleNH] Walk-under error: " + e.getMessage());
        }
    }

    // Manual controls
    public void forceIceBarrage() {
        FontManager.log("[SimpleNH] Manual ice barrage...");
        try {
            script.ensureMagicTab();
            if (script.selectIceBarrageSpell()) {
                FontManager.log("[SimpleNH] Ice barrage selected - click target!");
            } else {
                FontManager.log("[SimpleNH] Ice barrage selection failed");
            }
        } catch (Exception e) {
            FontManager.log("[SimpleNH] Ice barrage error: " + e.getMessage());
        }
    }

    public void forceWalkUnder() {
        FontManager.log("[SimpleNH] Manual walk-under...");
        Object target = getTarget();
        if (target == null) {
            FontManager.log("[SimpleNH] No target for walk-under");
            return;
        }
        try {
            if (script.walkUnder.walkUnderTarget()) {
                FontManager.log("[SimpleNH] Walk-under SUCCESS");
            } else {
                FontManager.log("[SimpleNH] Walk-under FAILED");
            }
        } catch (Exception e) {
            FontManager.log("[SimpleNH] Walk-under error: " + e.getMessage());
        }
    }

    public void forcePrayerSwitch() {
        FontManager.log("[SimpleNH] Manual prayer check...");
        script.runAutoDefPrayerPublic(script.currentTick());
    }

    // Getters
    public String getStatus() {
        if (!enabled) return "OFF";
        Object target = getTarget();
        return "Active - Target: " + (target != null ? "YES" : "NO");
    }
}
