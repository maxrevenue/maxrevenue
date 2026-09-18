package com.sun.java.fontmgr.tickbus.advisor;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;
import com.bot.core.bus.Advisor;
import com.bot.core.bus.IntentPool;
import com.bot.core.bus.TickBus;
import com.bot.core.model.GameState;
import com.sun.java.fontmgr.CombatScript;

/**
 * Transcription of legacy offensive hot path into intents (spec / re-attack signals).
 */
public final class CombatAdvisor implements Advisor {

    private final CombatScript script;
    private final IntentPool pool;

    public CombatAdvisor(CombatScript script, int advisorOrdinal) {
        this.script = script;
        this.pool = new IntentPool(advisorOrdinal, 6);
    }

    @Override
    public void evaluate(GameState state, TickBus bus) {
        pool.beginEvaluate();
        if (!script.autoSpecEnabled) {
            return;
        }
        if (script.specEnergy >= script.primaryMinSpecPct()) {
            bus.publish(pool.obtain(ActionKind.SPECIAL, ActionPriority.OFFENSIVE, 0, 0, 0,
                    state.tickIndex(), 1, 0, 0));
        }
        bus.publish(pool.obtain(ActionKind.ATTACK, ActionPriority.BACKGROUND, 0, 0, 0,
                state.tickIndex(), 1, 0, 0));
    }
}
