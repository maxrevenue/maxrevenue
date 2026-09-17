package com.automation.core.model;

import java.util.Objects;
import java.util.Optional;

/**
 * The single immutable snapshot of the world at the start of a tick.
 *
 * <p>Everything the decision engine needs lives here, and nothing here is
 * mutable, so a snapshot can be handed to headless tests, logged, or replayed
 * without any risk of state leaking across ticks.
 *
 * @param tickCount   monotonically increasing tick counter
 * @param localPlayer the bot's own state
 * @param target      the current opponent, if any
 * @param inventory   the player's inventory
 */
public record GameState(
        int tickCount,
        PlayerState localPlayer,
        Optional<PlayerState> target,
        InventorySnapshot inventory) {

    public GameState {
        Objects.requireNonNull(localPlayer, "localPlayer");
        target = (target == null) ? Optional.empty() : target;
        Objects.requireNonNull(inventory, "inventory");
    }

    public boolean hasTarget() {
        return target.isPresent();
    }
}
