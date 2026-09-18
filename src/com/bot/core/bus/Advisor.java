package com.bot.core.bus;

import com.bot.core.model.GameState;

public interface Advisor {

    void evaluate(GameState state, TickBus bus);
}
