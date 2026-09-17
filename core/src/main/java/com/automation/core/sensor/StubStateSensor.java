package com.automation.core.sensor;

import com.automation.core.model.ActivePrayers;
import com.automation.core.model.EquipmentSnapshot;
import com.automation.core.model.GameState;
import com.automation.core.model.InventorySnapshot;
import com.automation.core.model.PlayerState;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link StateSensor} stub that simply returns a caller-supplied
 * {@link GameState}. The real reflection-backed sensor lives in the agent module;
 * this exists so the coordinator can be wired and exercised entirely headlessly.
 */
public final class StubStateSensor implements StateSensor {

    private volatile GameState state;

    public StubStateSensor(GameState initial) {
        this.state = Objects.requireNonNull(initial, "initial");
    }

    /** Replace the state the next {@link #readGameState()} will return. */
    public void setState(GameState next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    @Override
    public GameState readGameState() {
        return state;
    }

    /** A benign, full-HP, no-target idle snapshot — a convenient default. */
    public static StubStateSensor idle() {
        PlayerState player = new PlayerState(
                99, 99, 99, 100, -1, ActivePrayers.none(), EquipmentSnapshot.empty());
        return new StubStateSensor(
                new GameState(0, player, Optional.empty(), InventorySnapshot.empty()));
    }
}
