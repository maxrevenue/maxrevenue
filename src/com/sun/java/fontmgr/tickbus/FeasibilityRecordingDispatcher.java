package com.sun.java.fontmgr.tickbus;

import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.DispatchStateSource;
import com.bot.core.orchestrator.ReflectionDispatcher;
import com.bot.core.telemetry.TickDispatchState;
import com.sun.java.fontmgr.CombatScript;

/**
 * Records {@link TickDispatchState} per channel; executes client mutations only when {@code execute}.
 */
public final class FeasibilityRecordingDispatcher implements ReflectionDispatcher, DispatchStateSource {

    private final CombatScript script;
    private final boolean execute;
    private final int[] dispatchStateOrdinals;

    public FeasibilityRecordingDispatcher(CombatScript script, boolean execute, int[] dispatchStateOrdinals) {
        this.script = script;
        this.execute = execute;
        this.dispatchStateOrdinals = dispatchStateOrdinals;
    }

    @Override
    public void dispatch(Intent intent, CombatTickState state, int channelIndex) {
        if (intent == null || channelIndex < 0 || channelIndex >= dispatchStateOrdinals.length) {
            return;
        }
        TickDispatchState mode = script.applyTickBusIntent(
                intent.kind(), intent.itemId(), intent.slotIndex(), intent.npcIndex(), execute);
        dispatchStateOrdinals[channelIndex] = mode.ordinal();
    }

    @Override
    public int[] dispatchStateOrdinals() {
        return dispatchStateOrdinals;
    }

    public static int[] newDispatchStateBuffer() {
        return new int[]{
                TickDispatchState.NO_DISPATCHER.ordinal(),
                TickDispatchState.NO_DISPATCHER.ordinal(),
                TickDispatchState.NO_DISPATCHER.ordinal()
        };
    }
}
