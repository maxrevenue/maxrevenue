package com.automation.core.engine.spec;

/**
 * Vesta's longsword spec — 25% energy, high-accuracy finish on low targets.
 */
public final class VlsStrategy extends AbstractWeaponSpecStrategy {

    /** VLS spec animation on most PK clients. */
    public static final int SPEC_ANIMATION = 7515;

    private static final int[] VLS_ITEM_IDS = {13899, 13901, 22613};

    public static final int DEFAULT_KO_HP = 40;

    public VlsStrategy() {
        this(DEFAULT_KO_HP);
    }

    public VlsStrategy(int maxTargetHpInclusive) {
        super("VLS", 25, VLS_ITEM_IDS, maxTargetHpInclusive);
    }

    @Override
    public boolean isSpecAnim(int anim) {
        return anim == SPEC_ANIMATION;
    }
}
