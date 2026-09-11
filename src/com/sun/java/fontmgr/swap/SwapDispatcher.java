package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.FontManager;
import com.sun.java.fontmgr.InventoryTracker;
import com.sun.java.fontmgr.UiExecutor;
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
 * <p>Waits use {@link UiExecutor} (real millisecond timers). The client-thread
 * queue is only pumped once per game tick, so delayed work queued there used
 * to bunch and fire out of order. Clicks run on the executor thread, same as
 * NH loadout switches.
 *
 * <p>Already-worn {@code e:} lines are dropped before scheduling so a snapshot
 * of 11 pieces does not burn 11 gaps when only 2 changed. Consecutive equip
 * lines are ordered weapon → offhand → armour.
 *
 * <p>A new hotkey press bumps a generation counter so an in-flight swap
 * stops clicking instead of interleaving with the next one.
 */
public final class SwapDispatcher {

    private final CombatScript script;
    private final AtomicInteger runGen = new AtomicInteger();

    public SwapDispatcher(CombatScript script) {
        this.script = script;
    }

    public void cancel() {
        runGen.incrementAndGet();
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

        final int gen = runGen.incrementAndGet();
        List<Command> steps = compactEquips(parsed);
        if (steps.isEmpty()) {
            FontManager.log("[Swapper] " + name + ": nothing to do (already worn / empty)");
            return;
        }

        FontManager.log("[Swapper] run: " + name + " lines=" + steps.size()
                + "/" + parsed.size());

        applyIceWeaponIntent(parsed);
        try { script.sendGameMessage("Swap: " + name); } catch (Exception ignored) {}

        CommandExecutor executor = new CommandExecutor(script);
        long delay = 0L;
        boolean firstInv = true;
        for (int i = 0; i < steps.size(); i++) {
            final Command cmd = steps.get(i);
            if ("delay".equals(cmd.type)) {
                long extra = 120L;
                try { extra = Math.max(0L, Math.min(3000L, Long.parseLong(cmd.value.trim()))); }
                catch (NumberFormatException ignored) {}
                delay += extra;
                continue;
            }
            if (isInventoryClick(cmd.type)) {
                delay += firstInv
                        ? ClientThreadGuard.firstInvClickDelayMs()
                        : ClientThreadGuard.ahkSafeInvGapMs();
                firstInv = false;
            } else {
                delay += ClientThreadGuard.sameTickGapMs();
            }
            final long at = delay;
            UiExecutor.schedule(() -> {
                if (runGen.get() != gen) return;
                boolean ok = executor.execute(cmd);
                FontManager.debug("[Swapper] " + cmd.type + "=" + cmd.value + " ok=" + ok);
            }, at);
        }

        final long doneAt = delay + ClientThreadGuard.sameTickGapMs();
        UiExecutor.schedule(() -> {
            if (runGen.get() != gen) return;
            FontManager.log("[Swapper] done: " + name);
            script.pinStaffLeftClickCastPublic();
        }, doneAt);
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
     * Client {@code AhkDetection.handleClickInventoryItem} only runs on
     * doAction opcodes 74 (eat/use) and 454 (wield) against iface 3214.
     */
    private static boolean isInventoryClick(String type) {
        return "e".equals(type) || "drop".equals(type) || "u".equals(type);
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
