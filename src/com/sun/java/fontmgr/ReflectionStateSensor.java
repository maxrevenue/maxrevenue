package com.sun.java.fontmgr;

import com.automation.core.model.ActivePrayers;
import com.automation.core.model.EquipmentSlot;
import com.automation.core.model.EquipmentSnapshot;
import com.automation.core.model.GameState;
import com.automation.core.model.InventoryItem;
import com.automation.core.model.InventorySnapshot;
import com.automation.core.model.PlayerState;
import com.automation.core.sensor.StateSensor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live {@link StateSensor} for the v2 engine: maps {@link CombatScript} /
 * {@link StateReader} reflection reads into immutable {@link GameState} records.
 *
 * <p>Never throws — a null target or partial vitals yields empty optionals and
 * sentinel values so the client tick thread keeps running.
 */
public final class ReflectionStateSensor implements StateSensor {

    private final CombatScript script;

    public ReflectionStateSensor(CombatScript script) {
        this.script = script;
    }

    @Override
    public GameState readGameState() {
        try {
            int tick = script.currentTick >= 0 ? script.currentTick : 0;
            PlayerState local = readLocalPlayer();
            Optional<PlayerState> target = readTargetPlayer();
            InventorySnapshot inventory = readInventory();
            return new GameState(tick, local, target, inventory);
        } catch (Throwable t) {
            FontManager.debug("[v2] StateSensor fallback: " + t.getClass().getSimpleName());
            return new GameState(
                    Math.max(script.currentTick, 0),
                    safeLocalFallback(),
                    Optional.empty(),
                    InventorySnapshot.empty());
        }
    }

    private PlayerState readLocalPlayer() {
        StateReader reader = script.stateReader;
        int hp = reader != null ? reader.getCurrentHp() : -1;
        int maxHp = reader != null ? reader.getMaxHp() : 99;
        if (maxHp <= 0) {
            maxHp = 99;
        }
        if (hp < 0) {
            hp = maxHp;
        }
        int prayer = reader != null ? reader.getCurrentPrayer() : 99;
        int spec = script.specEnergy >= 0 ? script.specEnergy : 0;
        int anim = readLocalAnimation();
        EquipmentSnapshot gear = readEquipmentSnapshot();
        return new PlayerState(hp, maxHp, prayer, spec, anim, ActivePrayers.none(), gear);
    }

    private int readLocalAnimation() {
        try {
            if (script.stateReader != null) {
                return script.stateReader.read().anim;
            }
        } catch (Throwable ignored) {
            // Best-effort only.
        }
        return -1;
    }

    private Optional<PlayerState> readTargetPlayer() {
        if (script.targetHp <= 0 && script.targetMaxHp <= 0 && script.lastTargetAnim < 0) {
            return Optional.empty();
        }
        int hp = script.targetHp > 0 ? script.targetHp : 0;
        int maxHp = script.targetMaxHp > 0 ? script.targetMaxHp : Math.max(hp, 99);
        int weaponId = script.opponentLoadout != null ? script.opponentLoadout.weaponId() : 0;
        Map<EquipmentSlot, Integer> gear = new EnumMap<>(EquipmentSlot.class);
        if (weaponId > 0) {
            gear.put(EquipmentSlot.WEAPON, weaponId);
        }
        return Optional.of(new PlayerState(
                hp,
                maxHp,
                0,
                0,
                script.lastTargetAnim,
                ActivePrayers.none(),
                new EquipmentSnapshot(gear)));
    }

    private EquipmentSnapshot readEquipmentSnapshot() {
        int[] worn = script.wornEquipmentIds();
        if (worn == null || worn.length == 0) {
            return EquipmentSnapshot.empty();
        }
        Map<EquipmentSlot, Integer> map = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            int idx = slot.index();
            if (idx >= 0 && idx < worn.length && worn[idx] > 0) {
                map.put(slot, worn[idx]);
            }
        }
        return new EquipmentSnapshot(map);
    }

    private InventorySnapshot readInventory() {
        int[] inv = script.getInventorySnapshot();
        if (inv == null || inv.length == 0) {
            return InventorySnapshot.empty();
        }
        List<InventoryItem> items = new ArrayList<>();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw > 0) {
                items.add(new InventoryItem(slot, raw - 1, 1));
            }
        }
        return new InventorySnapshot(items);
    }

    private static PlayerState safeLocalFallback() {
        return new PlayerState(99, 99, 99, 0, -1, ActivePrayers.none(), EquipmentSnapshot.empty());
    }
}
