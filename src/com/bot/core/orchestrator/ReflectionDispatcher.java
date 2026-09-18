package com.bot.core.orchestrator;

import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;

public interface ReflectionDispatcher {

    void dispatch(Intent intent, CombatTickState state);
}
