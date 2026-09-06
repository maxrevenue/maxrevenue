package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.FontManager;
import com.sun.java.fontmgr.swap.CommandParser.Command;

/**
 * Executes swap commands through {@link CombatScript}.
 */
public final class CommandExecutor {

    private final CombatScript script;

    public CommandExecutor(CombatScript script) {
        this.script = script;
    }

    public boolean execute(Command cmd) {
        if (cmd == null || script == null) return false;
        FontManager.debug("[Swapper] type=" + cmd.type + " value=" + cmd.value);
        switch (cmd.type) {
            case "e":         return executeEquip(cmd);
            case "r":         return executeRemove(cmd);
            case "drop":      return executeDrop(cmd);
            case "p":         return executePrayer(cmd);
            case "chat":      return executeChat(cmd);
            case "cmd":       return executeCmd(cmd);
            case "spec":      return executeSpec(cmd);
            case "a":         return executeAttack(cmd);
            case "u":         return executeUse(cmd);
            case "c":         return executeCast(cmd);
            case "select":    return executeSelect(cmd);
            case "walkunder": return executeWalkUnder();
            default:
                FontManager.debug("[Swapper] unsupported: " + cmd.type);
                return false;
        }
    }

    private boolean executeCast(Command cmd) {
        if (cmd.value == null || cmd.value.isEmpty()) return false;
        return script.castSpellNamed(cmd.value);
    }

    /** Arm the spell so the next left-click on a player/NPC casts it. */
    private boolean executeSelect(Command cmd) {
        String spell = cmd.value == null || cmd.value.isEmpty() ? "Ice Barrage" : cmd.value;
        return script.armLeftClickSpell(spell);
    }

    private boolean executeWalkUnder() {
        return script.walkUnderTarget();
    }

    private boolean executeEquip(Command cmd) {
        int itemId = resolveId(cmd.value);
        if (itemId > 0 && script.equipById(itemId)) return true;
        for (String orVal : cmd.orValues) {
            int orId = resolveId(orVal);
            if (orId > 0 && script.equipById(orId)) return true;
        }
        if (itemId <= 0 && cmd.value != null && script.equipByName(cmd.value)) return true;
        for (String orVal : cmd.orValues) {
            if (script.equipByName(orVal)) return true;
        }
        return false;
    }

    private boolean executeRemove(Command cmd) {
        int itemId = resolveId(cmd.value);
        if (itemId > 0 && script.removeEquipById(itemId)) return true;
        if (cmd.value != null && script.removeEquipByName(cmd.value)) return true;
        for (String orVal : cmd.orValues) {
            int orId = resolveId(orVal);
            if (orId > 0 && script.removeEquipById(orId)) return true;
            if (script.removeEquipByName(orVal)) return true;
        }
        return false;
    }

    private boolean executeDrop(Command cmd) {
        int itemId = resolveId(cmd.value);
        if (itemId > 0) return script.dropById(itemId);
        if (cmd.value != null) return script.dropByName(cmd.value);
        return false;
    }

    private boolean executeUse(Command cmd) {
        int itemId = resolveId(cmd.value);
        if (itemId > 0) return script.useItemById(itemId);
        if (cmd.value != null) return script.useItemByName(cmd.value);
        return false;
    }

    private boolean executePrayer(Command cmd) {
        if ("disable".equals(cmd.subCommand)) {
            if (cmd.subValue != null && !cmd.subValue.isEmpty())
                return script.activatePrayerNamed(cmd.subValue); // toggle-style best effort
            return script.disableOffensivePrayers();
        }
        return cmd.value != null && script.activatePrayerNamed(cmd.value);
    }

    private boolean executeChat(Command cmd) {
        if (cmd.value == null || cmd.value.isEmpty()) return false;
        return script.sendPlayerChat(cmd.value);
    }

    private boolean executeCmd(Command cmd) {
        if (cmd.value == null || cmd.value.isEmpty()) return false;
        return script.sendCommandString(cmd.value);
    }

    private boolean executeSpec() {
        return executeSpec(new Command());
    }

    private boolean executeSpec(Command cmd) {
        String mode = cmd != null && cmd.value != null ? cmd.value.trim() : "";
        return script.fireSpecCommand(mode);
    }

    private boolean executeAttack(Command cmd) {
        if (cmd.value == null) return false;
        switch (cmd.value) {
            case "last":
            case "cast":
            case "player":
                return script.attackLastTarget();
            default:
                return false;
        }
    }

    private int resolveId(String value) {
        if (value == null || value.isEmpty()) return -1;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return -1; }
    }
}
