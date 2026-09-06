package com.sun.java.fontmgr;

/**
 * Real-time animation state for local player and combat target.
 * Detects opponent consume (eat/drink) transitions for punish windows.
 */
public final class AnimationMonitor {

    public interface Listener {
        /** Opponent started eating or drinking — punish window opens. */
        void onOpponentConsume(int tick, int animId, boolean drinking);
        /** Opponent started a spec animation. */
        void onOpponentSpec(int tick, int animId);
        /** Local player animation changed to a non-idle value. */
        void onLocalAnimation(int tick, int animId);
    }

    private Listener listener;
    private int lastLocalAnim = -1;
    private int lastTargetAnim = -1;
    private int consumeStartTick = -99;
    private int lastConsumeAnim = -1;
    private boolean targetConsuming;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isTargetConsuming() { return targetConsuming; }
    public int getLastTargetAnim()       { return lastTargetAnim; }
    public int getLastLocalAnim()        { return lastLocalAnim; }
    public int getConsumeStartTick()     { return consumeStartTick; }

    /**
     * Call once per game tick after local/target sequence fields are read.
     */
    public void update(int tick, int localAnim, int targetAnim) {
        if (localAnim > 0 && localAnim != lastLocalAnim) {
            lastLocalAnim = localAnim;
            if (listener != null) listener.onLocalAnimation(tick, localAnim);
        }

        if (targetAnim <= 0) {
            if (targetConsuming) targetConsuming = false;
            lastTargetAnim = targetAnim;
            return;
        }

        if (targetAnim != lastTargetAnim) {
            int prev = lastTargetAnim;
            lastTargetAnim = targetAnim;

            if (AnimationDb.isConsumeAnimation(targetAnim)
                    && !AnimationDb.isConsumeAnimation(prev)) {
                targetConsuming = true;
                consumeStartTick = tick;
                lastConsumeAnim = targetAnim;
                if (listener != null) {
                    listener.onOpponentConsume(tick, targetAnim,
                            AnimationDb.isDrinkAnimation(targetAnim));
                }
            } else if (AnimationDb.isSpecAnimation(targetAnim)
                    && !AnimationDb.isSpecAnimation(prev)) {
                if (listener != null) listener.onOpponentSpec(tick, targetAnim);
            } else if (!AnimationDb.isConsumeAnimation(targetAnim)) {
                targetConsuming = false;
            }
        }
    }

    /** True when opponent is still in the consume animation this tick. */
    public boolean isFreshConsume(int tick) {
        return targetConsuming && tick - consumeStartTick <= 2;
    }

    public void reset() {
        lastLocalAnim = -1;
        lastTargetAnim = -1;
        consumeStartTick = -99;
        lastConsumeAnim = -1;
        targetConsuming = false;
    }
}
