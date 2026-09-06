package com.sun.java.fontmgr;

/**
 * Dharok Controller:
 * - Fights with Whip + Defender
 * - When HP gets low (or opponent in kill range / manual stack), equips DH Greataxe and attacks
 * - Immediately re-equips Whip + Defender after greataxe swing
 * - Handles 1-tick combo eating (Marlin -> Sara Brew -> Halibut) and Vengeance
 */
public final class DharokController {

    private static final int AXE_COOLDOWN_TICKS = 7;

    private final CombatScript script;
    private Object lastEngageTarget = null;
    private int lastEngageTick = -99;
    private boolean wasInFight = false;

    public DharokController(CombatScript script) {
        this.script = script;
    }

    public void onTick(int tick) {
        if (!script.dharokEnabled) return;
        if (PauseManager.get().isPaused(tick)) return;
        if (script.isSpecSequenceBusyPublic()) return;

        script.comboEatEnabled = false;
        script.dharokAutoEat = false;
        script.autoSpecEnabled = false;
        script.dharokAutoStack = false;

        if (!script.isInActivePvpFightPublic()) {
            leaveFight(tick);
            return;
        }

        tryEngageVeng(tick);

        int hp = script.readLocalHpPublic();
        if (hp <= 0) return;
        int maxHp = script.stateReader != null ? script.stateReader.getMaxHp() : 99;
        boolean manualStacking = script.inSelfOrbWindow(tick);

        script.wouldDhKoIfStacked();

        // 1. Follow-up Gmaul finisher if target survived axe inside gmaul kill range
        if (script.tryDhGmaulFollow(tick)) {
            // Gmaul + eat share the tick — firing the finisher must never starve HP.
            if (hp < CombatScript.DH_EAT_BAND_MIN && !manualStacking) {
                script.eatOffDhStackForced();
            }
            script.pumpDhSanfew(tick);
            return;
        }

        // 2. Defensive eat against opponent specs
        if (script.tryEatOffOpponentSpec(tick)) {
            script.pumpDhSanfew(tick);
            script.pumpDhEat(tick);
            return;
        }

        // 3. Re-equip Whip + Defender after greataxe swing (1-tick restore)
        // pendingDhWhipDef persists until BOTH weapons are in hands.
        if (script.pendingDhWhipDef && !script.hasPendingDhGmaul()
                && !script.pendingSanfew && tick > script.lastDhAxeTickPublic()) {
            script.tryRestoreWhipDefAfterDh(tick);
            if (hp < CombatScript.DH_EAT_BAND_MIN && !manualStacking) {
                script.eatOffDhStackForced();
            }
            script.pumpDhEat(tick);
            script.pumpDhSanfew(tick);
            return;
        }

        boolean axeReady = tick - script.lastDhAxeTickPublic() >= AXE_COOLDOWN_TICKS;
        int oppHp = script.liveTargetHp();
        int dhMaxHit = MaxHitCalculator.dharokMaxHit(script.readMeleeStrPublic(), hp, maxHp);

        boolean koReady = script.inKillRange
                && oppHp > 0
                && dhMaxHit >= oppHp;

        int stackHp = Math.max(1, (maxHp * Math.max(1, script.dharokTargetHpPct)) / 100);
        boolean stacked = hp <= stackHp + 6;

        // Do NOT swing just because HP dipped to 55 — that is the random greataxe.
        // You orb to ~1, then we axe. Or you are already stacked AND can KO.
        if (axeReady && stacked && (manualStacking || koReady)) {
            swingAxe(tick);
            return;
        }

        // 5. Critical HP panic eat (only when dangerously low, e.g. <= 25 HP)
        if (hp <= 25 && !manualStacking) {
            script.eatOffDhStackForced();
            script.lastAction = "DH_PANIC_EAT@" + tick;
            script.pumpDhSanfew(tick);
            return;
        }

        script.pumpDhSanfew(tick);
        script.pumpDhEat(tick);
        script.pumpManualDhAxe(tick);
        script.noteManualDhAxeRestore(tick);
    }

    private void swingAxe(int tick) {
        script.pendingDhStack = true;
        script.dharokStackArmed = true;
        boolean swung = script.tryOdablockDhAxe(true);
        if (!swung) {
            script.lastAction = "DH_AXE_RETRY@" + tick;
        }
        script.pendingDhStack = false;
        script.dharokStackArmed = false;
        script.pendingDhAxeAfterStack = false;
    }

    private void leaveFight(int tick) {
        script.pumpDhSanfew(tick);
        int hp = script.readLocalHpPublic();
        if (wasInFight && hp > 0 && hp <= 25) {
            script.eatOffDhStackForced();
        }
        if (script.pendingDhWhipDef && script.restoreWhipAndDefNow()) {
            script.pendingDhWhipDef = false;
        }
        script.pendingDhMustEat = false;
        if (wasInFight) script.lastAction = "DH_IDLE@" + tick;
        wasInFight = false;
        lastEngageTarget = null;
        script.pendingDhStack = false;
        script.dharokStackArmed = false;
        script.pendingDhAxeAfterStack = false;
        script.clearPendingDhGmaul();
    }

    private void tryEngageVeng(int tick) {
        wasInFight = true;
        if (!script.autoVengEnabled) return;
        Object target = script.getCachedTargetPublic();
        if (target == null) return;
        if (target == lastEngageTarget && tick - lastEngageTick < 20) return;
        lastEngageTarget = target;
        lastEngageTick = tick;
        if (script.isVengeanceLocked()) return;
        script.tryCastVengeanceEngage();
        script.lastAction = "DH_ENGAGE_VENG@" + tick;
    }
}
