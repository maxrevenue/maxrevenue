package com.automation.core.dispatcher;

import com.automation.core.model.ActionIntent;

import java.util.List;

/**
 * PHASE 3 — ACT.
 *
 * <p>Translates pure-data {@link ActionIntent}s into concrete client
 * interactions (interface clicks, packet fallbacks, reflection invocations).
 * This is the <em>only</em> place client-specific execution lives, so a Roat PKz
 * update that moves an offset or a widget id is fixed in exactly one class.
 */
public interface ActionDispatcher {

    /**
     * Execute the given intents, which the coordinator has already sorted by
     * descending priority.
     *
     * @param actions prioritized intents to carry out
     */
    void dispatch(List<ActionIntent> actions);
}
