package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.FontManager;
import com.sun.java.fontmgr.InventoryTracker;
import com.sun.java.fontmgr.swap.CommandParser.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a swap's command lines with AHK-safe, in-order pacing.
 *
 * <p>Roat's {@code AhkDetection} flags inventory clicks on <b>different slots</b>
 * that land {@code <= 70ms} apart (doAction opcodes 74 / 454). Equip / eat /
 * drop lines therefore use {@link ClientThreadGuard#ahkSafeInvGapMs()}
 * (72–99ms). Prayer, spec, attack, and spell select do not hit that detector,
 * so they keep a 9–18ms same-tick gap and can still land with the last equip.
 *
 * <p>Waits are wall-clock deadlines on {@link ClientThreadGuard#invokeAfter}.
 * {@code UiExecutor} used to fire the click on a background pool because the
 * client-thread queue was only pumped once per 600 ms game tick from
 * {@code agent-tick}, which bunched delayed work and marked the wrong thread.
 * {@code GameEngine.clientTick} now drains due tasks every client cycle
 * (~20 ms), so AHK-safe inventory gaps stay in order on the real client thread.
 *
 * <p>Already-worn {@code e:} lines are dropped before scheduling so a snapshot
 * of 11 pieces does not burn 11 gaps when only 2 changed. Consecutive equip
 * lines are ordered weapon → offhand → armour.
 *
 * <p>A new hotkey press bumps a generation counter so an in-flight swap
 * stops clicking instead of interleaving with the next one. Two <b>different</b>
 * swaps pressed within 160ms (DH unequip + veng) are merged onto the same
 * timeline instead of cancelling each other.
 *
 * <p>{@code r:} / {@code unequip:} clicks iface 1688 (packet 146), not
 * inventory 454, so they use the same-tick gap and can land with {@code c:veng}.
 */
public final class SwapDispatcher {

    private final CombatScript script;
    private final AtomicInteger runGen = new AtomicInteger();
    private final Object runLock = new Object();
    private int activeGen;
    private long mergeUntilMs;
    private long nextDelayMs;
    private boolean firstInv;
    private String lastMergedName = "";
    private String lastSwapName = "";

    public SwapDispatcher(CombatScript script) {
        this.script = script;
    }

    public void cancel() {
        runGen.incrementAndGet();
        synchronized (runLock) {
            mergeUntilMs = 0L;
            lastMergedName = "";
            lastSwapName = "";
        }
    }

    public void run(Swap swap) {
        if (swap == null || swap.getCommands() == null || script == null) return;
        final String name = swap.getName();
        final String body = swap.getCommands();

        List<Command> parsed = new ArrayList<>();
        for (String line : body.split("\\R")) {
            String cmd = line.trim();
            if (cmd.isEmpty()) continue;
            Command c = CommandParser.parse(cmd);
            if (c == null) continue;
            parsed.add(c);
        }
        if (parsed.isEmpty()) return;

        List<Command> steps = compactEquips(parsed);
        if (steps.isEmpty()) {
            FontManager.log("[Swapper] " + name + ": nothing to do (already worn / empty)");
            return;
        }

        applyIceWeaponIntent(parsed);

        final int gen;
        final boolean merged;
        synchronized (runLock) {
            long now = System.currentTimeMillis();
            boolean canMerge = SwapTimeline.shouldMerge(
                    now, mergeUntilMs, activeGen, runGen.get(), lastSwapName, name);
            if (!canMerge) {
                activeGen = runGen.incrementAndGet();
                nextDelayMs = 0L;
                firstInv = true;
                lastMergedName = name != null ? name : "";
                lastSwapName = lastMergedName;
                merged = false;
            } else {
                lastMergedName = lastMergedName + "+" + name;
                lastSwapName = name;
                merged = true;
            }
            gen = activeGen;
            mergeUntilMs = now + SwapTimeline.MERGE_WINDOW_MS;

            FontManager.log("[Swapper] " + (merged ? "merge" : "run") + ": "
                    + lastMergedName + " lines=" + steps.size() + "/" + parsed.size());
            try {
                script.sendGameMessage("Swap: " + lastMergedName);
            } catch (Exception ignored) {}

            CommandExecutor executor = new CommandExecutor(script);
            SwapTimeline.Cursor cur = new SwapTimeline.Cursor(nextDelayMs, firstInv);
            for (int i = 0; i < steps.size(); i++) {
                final Command cmd = steps.get(i);
                if ("delay".equals(cmd.type)) {
                    cur.addExplicitDelay(cmd.value);
                    continue;
                }
                final long at = cur.scheduleClick(cmd.type);
                ClientThreadGuard.get().invokeAfter(at, () -> {
                    if (runGen.get() != gen) return;
                    boolean ok = executor.execute(cmd);
                    FontManager.debug("[Swapper] " + cmd.type + "=" + cmd.value + " ok=" + ok);
                });
            }
            nextDelayMs = cur.delayMs;
            firstInv = cur.firstInv;

            final long doneAt = cur.doneAtMs();
            final String doneName = lastMergedName;
            ClientThreadGuard.get().invokeAfter(doneAt, () -> {
                if (runGen.get() != gen) return;
                FontManager.log("[Swapper] done: " + doneName);
                script.pinStaffLeftClickCastPublic();
            });
        }
    }

    /**
     * Drop Ice the moment a range/melee swap is pressed — before 454 / 1688.
     * Mage staff in the swap lets Ice re-arm after the staff is actually worn.
     */
    private void applyIceWeaponIntent(List<Command> cmds) {
        int weaponId = -1;
        String weaponName = null;
        for (Command cmd : cmds) {
            if (cmd == null || !"e".equals(cmd.type)) continue;
            int id = parseId(cmd.value);
            String name = cmd.value;
            if (id > 0) {
                String resolved = script.resolveItemNamePublic(id);
                if (resolved != null && !resolved.isEmpty()) name = resolved;
            }
            if (InventoryTracker.isAmmo(id, name)) continue;
            if (!InventoryTracker.isMageStaff(id, name)
                    && !InventoryTracker.isBlueMoonSpear(id, name)
                    && !InventoryTracker.looksLikeMageWeaponName(name)
                    && !InventoryTracker.isNhMainWeapon(id, name)) {
                continue;
            }
            weaponId = id;
            weaponName = name;
            break;
        }
        if (weaponId > 0 || (weaponName != null && !weaponName.isEmpty())) {
            script.noteIncomingMainHandPublic(weaponId, weaponName);
        } else {
            script.clearLeftClickArmPublic();
        }
    }

    /**
     * Drop worn equips and sort each consecutive {@code e:} run weapon-first
     * so a 2h doesn't get re-equipped after its offhand.
     */
    private List<Command> compactEquips(List<Command> in) {
        List<Command> out = new ArrayList<>(in.size());
        int i = 0;
        while (i < in.size()) {
            Command c = in.get(i);
            if ("r".equals(c.type) && shouldSkipRemove(c)) {
                i++;
                continue;
            }
            if (!"e".equals(c.type)) {
                out.add(c);
                i++;
                continue;
            }
            List<Command> block = new ArrayList<>();
            while (i < in.size() && "e".equals(in.get(i).type)) {
                Command eq = in.get(i++);
                if (shouldSkipEquip(eq)) continue;
                block.add(eq);
            }
            block.sort((a, b) -> Integer.compare(equipPriority(a), equipPriority(b)));
            out.addAll(block);
        }
        return out;
    }

    private boolean shouldSkipEquip(Command cmd) {
        return cmd == null || anyVariantWorn(cmd);
    }

    private boolean shouldSkipRemove(Command cmd) {
        if (cmd == null) return true;
        if (CommandExecutor.isVengAlias(cmd.value)) return false;
        if (isRemoveTargetWorn(cmd.value)) return false;
        for (String or : cmd.orValues) {
            if (CommandExecutor.isVengAlias(or) || isRemoveTargetWorn(or)) return false;
        }
        return true;
    }

    private boolean isRemoveTargetWorn(String spec) {
        if (spec == null || spec.isEmpty()) return false;
        return script.isRemoveTargetWorn(spec);
    }

    private boolean anyVariantWorn(Command cmd) {
        if (isWorn(cmd.value)) return true;
        for (String or : cmd.orValues) {
            if (isWorn(or)) return true;
        }
        return false;
    }

    private boolean anyVariantInInventory(Command cmd) {
        int hinted = parseSlotHint(cmd.identifier);
        if (inInventory(cmd.value, hinted)) return true;
        for (String or : cmd.orValues) {
            if (inInventory(or, hinted)) return true;
        }
        return false;
    }

    private boolean isWorn(String spec) {
        if (spec == null || spec.isEmpty()) return false;
        int id = parseId(spec);
        if (id > 0 && script.isEquippedId(id)) return true;
        return id <= 0 && script.isEquippedName(spec);
    }

    private boolean inInventory(String spec, int hintedSlot) {
        if (spec == null || spec.isEmpty()) return false;
        int id = parseId(spec);
        if (hintedSlot >= 0 && hintedSlot < 28) {
            int raw = script.getInventoryItemId(hintedSlot);
            if (raw > 0) {
                int slotId = raw - 1;
                if (id > 0 && slotId == id) return true;
                if (id <= 0 && script.itemNameMatchesPublic(slotId, spec)) return true;
            }
        }
        if (id > 0) return script.findInventorySlotById(id) >= 0;
        return script.findInventorySlotByName(spec) >= 0;
    }

    private int equipPriority(Command cmd) {
        int id = parseId(cmd.value);
        String name = cmd.value;
        if (id > 0) {
            String resolved = script.resolveItemNamePublic(id);
            if (resolved != null && !resolved.isEmpty()) name = resolved;
        }
        return InventoryTracker.nhEquipPriority(id, name);
    }

    private static int parseId(String value) {
        if (value == null || value.isEmpty()) return -1;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private static int parseSlotHint(String identifier) {
        if (identifier == null || identifier.isEmpty()) return -1;
        try {
            int slot = Integer.parseInt(identifier.replace("(", "").replace(")", "").trim());
            return (slot >= 0 && slot < 28) ? slot : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
