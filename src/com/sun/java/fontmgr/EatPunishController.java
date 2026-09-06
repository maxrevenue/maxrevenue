package com.sun.java.fontmgr;

/**
 * Instant counter when the opponent locks into eat animation (food only).
 */
public final class EatPunishController implements AnimationMonitor.Listener {

  private final CombatScript script;
  private final GearSwapEngine gearSwap;
  private int lastPunishTick = -99;
  private static final int PUNISH_COOLDOWN = 4;

  public EatPunishController(CombatScript script, GearSwapEngine gearSwap) {
    this.script = script;
    this.gearSwap = gearSwap;
  }

  @Override
  public void onOpponentConsume(int tick, int animId, boolean drinking) {
    if (!script.eatPunishEnabled) return;
    if (PauseManager.get().isPaused(tick)) return;
    if (!script.hasCombatContextPublic()) return;
    if (!script.isInActivePvpFightPublic()) return;
    // Punish food only — not brew/saradomin brew sips.
    if (drinking || !AnimationDb.isEatAnimation(animId)) return;
    if (tick - lastPunishTick <= PUNISH_COOLDOWN) return;
    if (script.isSpecSequenceBusyPublic()) return;
    if (script.specEnergy < script.eatPunishMinSpecPct) return;
    // Never punish during our DH burst / axe / post-axe window.
    if (script.dharokEnabled) {
      if (script.pendingDhStack || script.dharokStackArmed) return;
      if (tick - script.lastDhAxeTickPublic() <= 4) return;
      if (script.wouldDhKoIfStacked() && script.isSafeDhBurstWindow()) return;
    }

    lastPunishTick = tick;
    FontManager.debug("[EatPunish] consume anim=" + animId + " tick=" + tick);
    ClientThreadGuard.get().invokeLater(() -> firePunish(tick));
  }

  @Override
  public void onOpponentSpec(int tick, int animId) {}

  @Override
  public void onLocalAnimation(int tick, int animId) {}

  private void firePunish(int tick) {
    if (!script.eatPunishEnabled || script.isSpecSequenceBusyPublic()) return;
    if (!script.isInActivePvpFightPublic()) return;
    if (script.animationMonitor == null || !script.animationMonitor.isFreshConsume(tick)) return;
    if (script.dharokEnabled && script.wouldDhKoIfStacked() && script.isSafeDhBurstWindow()) {
      script.lastAction = "PUNISH_YIELD_DH@" + tick;
      return;
    }
    script.lastAction = "PUNISH_EAT@" + tick;
    if (script.isGmaulOnly() || !script.eatPunishPreferVls) {
      script.fireGmaulPunishPublic();
    } else {
      gearSwap.queueVlsSpec("EAT_PUNISH", null);
    }
  }
}
