package com.sun.java.fontmgr.tickbus.advisor;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;
import com.bot.core.bus.Advisor;
import com.bot.core.bus.IntentPool;
import com.bot.core.bus.TickBus;
import com.bot.core.model.GameState;
import com.sun.java.fontmgr.CombatScript;

/**
 * Transcription of legacy sustain paths into intents (no redesign during wrap phase).
 */
public final class SustainAdvisor implements Advisor {

    private final CombatScript script;
    private final IntentPool pool;

    public SustainAdvisor(CombatScript script, int advisorOrdinal) {
        this.script = script;
        this.pool = new IntentPool(advisorOrdinal, 4);
    }

    @Override
    public void evaluate(GameState state, TickBus bus) {
        pool.beginEvaluate();
        int slot = script.findHpReducerSlotPublic();
        if (slot < 0) {
            return;
        }
        int hp = script.readLocalHpPublic();
        if (hp >= 0 && hp <= script.comboEatHpThreshold) {
            bus.publish(pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 0, 0, slot,
                    state.tickIndex(), 1, 0, 0));
        }
    }
}
