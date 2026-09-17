package com.automation.core.engine.spec;

/**
 * Voidwaker spec — 50% energy, magic-based special used heavily on Roat NH.
 *
 * <p>Typical hybrid loadouts (Eclipse + Blood Moon + Barrows gloves) pair the
 * voidwaker with AGS/claws for stacked finishers; this strategy covers the
 * voidwaker-only dump when the target is in range and energy is available.
 */
public final class VoidwakerStrategy extends AbstractWeaponSpecStrategy {

    public static final int SPEC_ANIMATION = 8145;

    private static final int[] VOIDWAKER_ITEM_IDS = {27690, 27692};

    public static final int DEFAULT_KO_HP = 50;

    public VoidwakerStrategy() {
        this(DEFAULT_KO_HP);
    }

    public VoidwakerStrategy(int maxTargetHpInclusive) {
        super("Voidwaker", 50, VOIDWAKER_ITEM_IDS, maxTargetHpInclusive);
    }

    @Override
    public boolean isSpecAnim(int anim) {
        return anim == SPEC_ANIMATION;
    }
}
