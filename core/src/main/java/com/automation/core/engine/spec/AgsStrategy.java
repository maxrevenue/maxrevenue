package com.automation.core.engine.spec;

/**
 * Armadyl godsword (and Roat starter/or) spec — 50% energy, classic melee KO.
 *
 * <p>Fires when the target is inside the KO band and an AGS-family weapon is
 * wielded or ready in the inventory (hybrid NH loadouts often carry the spec
 * weapon in the weapon slot after a barrage→melee switch).
 */
public final class AgsStrategy extends AbstractWeaponSpecStrategy {

    /** OSRS / Roat AGS spec animation. */
    public static final int SPEC_ANIMATION = 7644;

    private static final int[] AGS_ITEM_IDS = {
            11802, 20368, 20370, 20372, 20374, 31800
    };

    /** Default KO window for AGS finishers in NH. */
    public static final int DEFAULT_KO_HP = 45;

    public AgsStrategy() {
        this(DEFAULT_KO_HP);
    }

    /**
     * @param maxTargetHpInclusive spec when target current HP is at or below this value
     */
    public AgsStrategy(int maxTargetHpInclusive) {
        super("AGS", 50, AGS_ITEM_IDS, maxTargetHpInclusive);
    }

    @Override
    public boolean isSpecAnim(int anim) {
        return anim == SPEC_ANIMATION || anim == 7645 || anim == 7646;
    }
}
