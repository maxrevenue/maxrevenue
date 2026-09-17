package com.automation.core.model;

/**
 * A pure-data description of something the bot intends to do this tick.
 *
 * <p>Intents contain <strong>no execution logic</strong>: they are produced by
 * the {@code DecisionEngine} from an immutable {@code GameState} and later
 * translated into client interactions by the {@code ActionDispatcher}. Because
 * the type is {@code sealed}, the dispatcher can pattern-match exhaustively over
 * every permitted intent, and adding a new intent is a compile-time prompt to
 * handle it everywhere.
 */
public sealed interface ActionIntent
        permits EatAction, EquipAction, CastSpellAction, AttackAction, SpecAction {

    /** The tier this intent competes in when several fire on the same tick. */
    ActionPriority priority();

    /** Convenience shortcut for {@code priority().level()} used when sorting. */
    default int priorityLevel() {
        return priority().level();
    }
}
