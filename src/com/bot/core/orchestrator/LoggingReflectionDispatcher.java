package com.bot.core.orchestrator;

import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;

/** Shadow / test dispatcher — no client mutations. */
public final class LoggingReflectionDispatcher implements ReflectionDispatcher {

    public int dispatchCount;

    @Override
    public void dispatch(Intent intent, CombatTickState state) {
        if (intent != null) {
            dispatchCount++;
        }
    }
}
