package com.sun.java.fontmgr.tickbus;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;
import com.bot.core.orchestrator.ReflectionDispatcher;
import com.sun.java.fontmgr.CombatScript;

public final class CombatReflectionDispatcher implements ReflectionDispatcher {

    private final CombatScript script;

    public CombatReflectionDispatcher(CombatScript script) {
        this.script = script;
    }

    @Override
    public void dispatch(Intent intent, CombatTickState state) {
        if (intent == null) {
            return;
        }
        script.dispatchTickBusIntent(intent.kind(), intent.itemId(), intent.slotIndex(), intent.npcIndex());
    }
}
