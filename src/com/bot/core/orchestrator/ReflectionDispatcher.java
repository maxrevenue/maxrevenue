package com.bot.core.orchestrator;

import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;

public interface ReflectionDispatcher {

    /**
     * @param channelIndex {@link ChannelIndex#OFFENSIVE}, {@link ChannelIndex#SUSTAIN}, or {@link ChannelIndex#DEFENSIVE}
     */
    void dispatch(Intent intent, CombatTickState state, int channelIndex);
}
