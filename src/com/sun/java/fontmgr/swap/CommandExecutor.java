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
            case "tab":       return executeTab(cmd);
            case "nh":        return executeNh(cmd);
            case "overhead":  return executeOverhead(cmd);
            case "toggle":    return executeToggle(cmd);
            case "o":         return executeObject(cmd);
            case "delay":     return true; // timing handled by SwapDispatcher
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
        return script.armLeftClickSpellNow(spell);
    }

    private boolean executeWalkUnder() {
        return script.walkUnderTarget();
    }

    private boolean executeNh(Command cmd) {
        String v = cmd.value == null ? "mage" : cmd.value.trim().toLowerCase();
        switch (v) {
            case "mage": case "g": case "gmaul": script.nhSwitchMage(); return true;
            case "range": case "r": script.nhSwitchRange(); return true;
            case "melee": case "m": script.nhSwitchMelee(); return true;
            case "tank": case "t": script.nhSwitchTank(); return true;
            default: return false;
        }
    }

    private boolean executeTab(Command cmd) {
        String v = cmd.value == null ? "" : cmd.value.trim().toLowerCase();
        int tab;
        switch (v) {
            case "combat": case "attack": case "0": tab = 0; break;
            case "stats": case "skill": case "1": tab = 1; break;
            case "quest": case "2": tab = 2; break;
            case "inv": case "inventory": case "3": tab = 3; break;
            case "equipment": case "worn": case "4": tab = 4; break;
            case "prayer": case "pray": case "5": tab = 5; break;
            case "magic": case "spell": case "spells": case "6": tab = 6; break;
            case "friend": case "friends": case "7": tab = 7; break;
            case "ignore": case "8": tab = 8; break;
            case "log": case "logout": case "9": tab = 9; break;
            default:
                try { tab = Integer.parseInt(v.trim()); }
                catch (NumberFormatException e) { return false; }
        }
        script.invokeSetTabPublic(tab);
        return true;
    }

    private boolean executeEquip(Command cmd) {
        int hinted = parseSlotHint(cmd.identifier);
        if (tryEquipSpec(cmd.value, hinted)) return true;
        for (String orVal : cmd.orValues) {
            if (tryEquipSpec(orVal, hinted)) return true;
        }
        return false;
    }

    private boolean tryEquipSpec(String spec, int hintedSlot) {
        if (spec == null || spec.isEmpty()) return false;
        int itemId = resolveId(spec);
        if (itemId > 0) {
            if (script.isEquippedId(itemId)) return true;
            return script.equipById(itemId, hintedSlot);
        }
        if (script.isEquippedName(spec)) return true;
        return script.equipByName(spec, hintedSlot);
    }

    private boolean executeRemove(Command cmd) {
        if (tryRemoveSpec(cmd.value)) return true;
        for (String orVal : cmd.orValues) {
            if (tryRemoveSpec(orVal)) return true;
        }
        return false;
    }

    private boolean tryRemoveSpec(String spec) {
        if (spec == null || spec.isEmpty()) return false;
        int itemId = resolveId(spec);
        if (itemId > 0) {
            if (!script.isEquippedId(itemId)) return true;
            return script.removeEquipById(itemId);
        }
        if (!script.isEquippedName(spec)) return true;
        return script.removeEquipByName(spec);
    }

    private boolean executeDrop(Command cmd) {
        int opcode = resolveOpcode(cmd.opcode, 847);
        if (tryDropSpec(cmd.value, opcode)) return true;
        for (String orVal : cmd.orValues) {
            if (tryDropSpec(orVal, opcode)) return true;
        }
        return false;
    }

    private boolean tryDropSpec(String spec, int opcode) {
        if (spec == null || spec.isEmpty()) return false;
        int itemId = resolveId(spec);
        if (itemId > 0) return script.dropById(itemId, opcode);
        return script.dropByName(spec, opcode);
    }

    private boolean executeUse(Command cmd) {
        if (tryUseSpec(cmd.value)) return true;
        for (String orVal : cmd.orValues) {
            if (tryUseSpec(orVal)) return true;
        }
        return false;
    }

    private boolean tryUseSpec(String spec) {
        if (spec == null || spec.isEmpty()) return false;
        int itemId = resolveId(spec);
        if (itemId > 0) return script.useItemById(itemId);
        return script.useItemByName(spec);
    }

    private boolean executePrayer(Command cmd) {
        if ("disable".equals(cmd.subCommand)) {
            if (cmd.subValue != null && !cmd.subValue.isEmpty())
                return script.disablePrayerNamed(cmd.subValue);
            return script.disableOffensivePrayers();
        }
        return cmd.value != null && script.firePrayerNow(cmd.value);
    }

    private boolean executeChat(Command cmd) {
        if (cmd.value == null || cmd.value.isEmpty()) return false;
        return script.sendPlayerChat(cmd.value);
    }

    private boolean executeCmd(Command cmd) {
        if (cmd.value == null || cmd.value.isEmpty()) return false;
        return script.sendCommandString(cmd.value);
    }

    private boolean executeSpec(Command cmd) {
        String mode = cmd != null && cmd.value != null ? cmd.value.trim() : "";
        return script.fireSpecCommand(mode);
    }

    private boolean executeAttack(Command cmd) {
        String v = cmd.value == null || cmd.value.isEmpty() ? "last" : cmd.value;
        switch (v) {
            case "last":
            case "cast":
            case "player":
                return script.attackLastTarget();
            case "id":
                return cmd.npcId >= 0 && script.attackNpcIndex(cmd.npcId);
            default:
                try {
                    int idx = Integer.parseInt(v.trim());
                    return script.attackPlayerIndex(idx) || script.attackNpcIndex(idx);
                } catch (NumberFormatException e) {
                    return false;
                }
        }
    }

    /** overhead:mage|range|melee — set the protect prayer. */
    private boolean executeOverhead(Command cmd) {
        String v = cmd.value == null ? "" : cmd.value.trim().toLowerCase();
        switch (v) {
            case "mage": case "magic": case "m":
                script.triggerProtectMagic(); return true;
            case "range": case "ranged": case "r": case "missiles":
                script.triggerProtectRange(); return true;
            case "melee": case "me":
                script.triggerProtectMelee(); return true;
            default:
                return false;
        }
    }

    /** toggle:autospec|veng|eatpunish|overheads|defpray|protectitem|staffcast|comboeat */
    private boolean executeToggle(Command cmd) {
        String v = cmd.value == null ? "" : cmd.value.trim().toLowerCase();
        switch (v) {
            case "autospec": case "specauto":
                script.actions().toggleAutoSpec();
                FontManager.log("[Swapper] auto spec " + (script.actions().autoSpecEnabled() ? "ON" : "OFF"));
                return true;
            case "veng": case "autoveng":
                script.actions().toggleAutoVeng();
                FontManager.log("[Swapper] auto veng " + (script.actions().autoVengEnabled() ? "ON" : "OFF"));
                return true;
            case "eatpunish":
                script.actions().toggleEatPunish();
                FontManager.log("[Swapper] eat punish " + (script.actions().eatPunishEnabled() ? "ON" : "OFF"));
                return true;
            case "overheads": case "defpray": case "defensive": case "pray":
                script.actions().toggleDefensivePrayers();
                FontManager.log("[Swapper] overheads " + (script.actions().defensivePrayersEnabled() ? "ON" : "OFF"));
                return true;
            case "protectitem": case "protitem":
                script.actions().toggleProtectItem();
                FontManager.log("[Swapper] protect item " + (script.actions().protectItemEnabled() ? "ON" : "OFF"));
                return true;
            case "staffcast": case "stafflc": case "lc":
                script.actions().toggleStaffLeftClickCast();
                FontManager.log("[Swapper] staff left-click cast " + (script.actions().staffLeftClickCast() ? "ON" : "OFF"));
                return true;
            case "comboeat":
                script.actions().toggleComboEat();
                FontManager.log("[Swapper] combo eat " + (script.actions().comboEatEnabled() ? "ON" : "OFF"));
                return true;
            default:
                return false;
        }
    }

    /** o:name / o:name:opcode — object click (best effort). */
    private boolean executeObject(Command cmd) {
        FontManager.log("[Swapper] object click not yet wired: " + cmd.value);
        return false;
    }

    private int resolveId(String value) {
        if (value == null || value.isEmpty()) return -1;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return -1; }
    }

    private static int resolveOpcode(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return fallback; }
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
