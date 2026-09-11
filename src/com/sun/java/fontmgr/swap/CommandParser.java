package com.sun.java.fontmgr.swap;

import java.util.ArrayList;
import java.util.List;

/**
 * Ganom Advanced Swapper DSL parser.
 * Supports e/r/drop/p/a/c/o/u/spec/walkunder with OR operators and comments.
 */
public final class CommandParser {

    public static final class Command {
        public String type;
        public String value;
        public List<String> orValues = new ArrayList<>();
        public String identifier;
        public String opcode;
        public int distance = -1;
        public String subCommand;
        public String subValue;
        public int npcId = -1;
    }

    private CommandParser() {}

    public static Command parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        raw = raw.trim();

        if (raw.startsWith("//") || raw.startsWith("#") || raw.startsWith("##") || raw.startsWith("/"))
            return null;

        int ci = raw.indexOf("//");
        if (ci >= 0) raw = raw.substring(0, ci).trim();
        ci = raw.indexOf(" #");
        if (ci >= 0) raw = raw.substring(0, ci).trim();
        if (raw.isEmpty()) return null;

        int colon = raw.indexOf(':');
        String prefix = colon >= 0 ? raw.substring(0, colon).toLowerCase() : raw.toLowerCase();
        String rest = colon >= 0 ? raw.substring(colon + 1) : "";

        if (prefix.equals("spec")) {
            Command cmd = new Command();
            cmd.type = "spec";
            cmd.value = rest != null ? rest.trim() : "";
            return cmd;
        }
        if (prefix.equals("combo")) {
            Command cmd = new Command();
            cmd.type = "spec";
            cmd.value = rest != null && !rest.trim().isEmpty() ? rest.trim() : "combo";
            return cmd;
        }
        if (prefix.equals("walkunder")) {
            Command cmd = new Command();
            cmd.type = "walkunder";
            return cmd;
        }
        if (prefix.equals("nhmage") || prefix.equals("nhg")) {
            Command cmd = new Command(); cmd.type = "nh"; cmd.value = "mage"; return cmd;
        }
        if (prefix.equals("nhrange") || prefix.equals("nhr")) {
            Command cmd = new Command(); cmd.type = "nh"; cmd.value = "range"; return cmd;
        }
        if (prefix.equals("nhmelee") || prefix.equals("nhm")) {
            Command cmd = new Command(); cmd.type = "nh"; cmd.value = "melee"; return cmd;
        }
        if (prefix.equals("nhtank") || prefix.equals("nht")) {
            Command cmd = new Command(); cmd.type = "nh"; cmd.value = "tank"; return cmd;
        }
        if (prefix.equals("delay") || prefix.equals("wait") || prefix.equals("pause")) {
            Command cmd = new Command();
            cmd.type = "delay";
            cmd.value = rest != null && !rest.trim().isEmpty() ? rest.trim() : "120";
            return cmd;
        }
        if (prefix.equals("overhead") || prefix.equals("oh") || prefix.equals("protect")) {
            Command cmd = new Command();
            cmd.type = "overhead";
            cmd.value = rest != null ? rest.trim().toLowerCase() : "";
            return cmd;
        }
        if (prefix.equals("toggle") || prefix.equals("tog")) {
            Command cmd = new Command();
            cmd.type = "toggle";
            cmd.value = rest != null ? rest.trim().toLowerCase() : "";
            return cmd;
        }
        if (prefix.equals("tab") || prefix.equals("interface")) {
            Command cmd = new Command();
            cmd.type = "tab";
            cmd.value = rest != null ? rest.trim() : "3";
            return cmd;
        }

        if (colon < 0) {
            if (looksLikeSpell(raw)) {
                Command cmd = new Command();
                cmd.type = "select";
                cmd.value = raw.trim();
                return cmd;
            }
            try {
                Integer.parseInt(prefix);
                Command cmd = new Command();
                cmd.type = "e";
                cmd.value = prefix;
                return cmd;
            } catch (NumberFormatException e) {
                // Bare item name: "vesta longsword" → equip
                Command cmd = new Command();
                cmd.type = "e";
                cmd.value = raw;
                return cmd;
            }
        }

        Command cmd = new Command();
        switch (prefix) {
            case "e": case "equip":
                if (looksLikeSpell(rest)) {
                    cmd.type = "select";
                    cmd.value = rest.trim();
                } else {
                    cmd.type = "e";
                    parseItemWithOptionalIdentifier(rest, cmd);
                }
                break;
            case "r": case "remove":
                cmd.type = "r";
                parseItemWithOptionalIdentifier(rest, cmd);
                break;
            case "drop":
                cmd.type = "drop";
                parseSegmentedItem(rest, cmd);
                break;
            case "p": case "prayer":
                cmd.type = "p";
                parsePrayer(rest, cmd);
                break;
            case "chat": case "say": case "type":
                cmd.type = "chat";
                cmd.value = rest != null ? rest.trim() : "";
                break;
            case "cmd": case "command":
                cmd.type = "cmd";
                cmd.value = rest != null ? rest.trim() : "";
                break;
            case "a": case "attack":
                cmd.type = "a";
                if (rest == null || rest.trim().isEmpty()) {
                    cmd.value = "last";
                } else {
                    parseAttack(rest, cmd);
                    if (cmd.value == null || cmd.value.isEmpty()) cmd.value = "last";
                }
                break;
            case "c": case "cast":
                parseCast(rest, cmd);
                break;
            case "gear": case "nh": case "loadout":
                cmd.type = "nh";
                cmd.value = rest == null ? "mage" : rest.trim().toLowerCase();
                break;
            case "select": case "lc": case "leftclick": case "arm": case "s":
                cmd.type = "select";
                cmd.value = rest == null || rest.trim().isEmpty() ? "Ice Barrage" : rest.trim();
                break;
            case "o":
                cmd.type = "o";
                parseObject(rest, cmd);
                break;
            case "u": case "use": case "eat":
                cmd.type = "u";
                parseSegmentedItem(rest, cmd);
                break;
            default:
                try {
                    Integer.parseInt(prefix);
                    cmd.type = "e";
                    cmd.value = prefix;
                } catch (NumberFormatException e) {
                    return null;
                }
        }
        return cmd;
    }

    private static void parseItemWithOptionalIdentifier(String rest, Command cmd) {
        if (rest == null || rest.isEmpty()) return;
        String itemPart = rest;
        String identifierPart = null;
        int lastColon = rest.lastIndexOf(':');
        if (lastColon >= 0) {
            String candidate = rest.substring(lastColon + 1).trim();
            if (isNumericIdentifier(candidate)) {
                itemPart = rest.substring(0, lastColon);
                identifierPart = candidate;
            }
        }
        parseOrOperator(itemPart, cmd);
        cmd.identifier = identifierPart;
    }

    private static void parseSegmentedItem(String rest, Command cmd) {
        if (rest == null || rest.isEmpty()) return;
        String[] segments = rest.split(":");
        parseOrOperator(segments[0], cmd);
        if (segments.length >= 2) cmd.opcode = segments[1].trim();
        if (segments.length >= 3) cmd.identifier = segments[2].trim();
    }

    private static boolean looksLikeSpell(String raw) {
        if (raw == null) return false;
        String n = raw.toLowerCase().replace("'", "").replace("-", " ").trim();
        n = n.replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        if (n.isEmpty()) return false;
        return n.equals("ice barrage") || n.equals("icebarrage") || n.equals("ib")
                || n.equals("barrage")
                || n.equals("blood barrage") || n.equals("bloodbarrage")
                || n.equals("smoke barrage") || n.equals("smokebarrage")
                || n.equals("shadow barrage") || n.equals("shadowbarrage")
                || n.equals("ice blitz") || n.equals("blitz")
                || n.equals("blood blitz") || n.equals("bloodblitz")
                || n.equals("ice burst") || n.equals("burst")
                || n.equals("ice rush") || n.equals("rush")
                || n.equals("tele block") || n.equals("teleblock") || n.equals("tb")
                || n.equals("entangle") || n.equals("vengeance") || n.equals("veng");
    }

    private static void parseCast(String rest, Command cmd) {
        if (rest == null) rest = "";
        rest = rest.trim();
        String lower = rest.toLowerCase();
        // Explicit fire on current target
        if (lower.endsWith(":cast") || lower.endsWith(":last") || lower.endsWith(":target")
                || lower.endsWith(":now")) {
            int cut = rest.lastIndexOf(':');
            cmd.type = "c";
            String spell = rest.substring(0, cut).trim();
            cmd.value = spell.isEmpty() ? "Ice Barrage" : spell;
            return;
        }
        // Default (Ganom): select the spell so the next left-click casts.
        // Also accepts :arm / :select / :lc
        if (lower.endsWith(":arm") || lower.endsWith(":select") || lower.endsWith(":lc")
                || lower.endsWith(":leftclick")) {
            int cut = rest.lastIndexOf(':');
            rest = rest.substring(0, cut).trim();
        }
        cmd.type = "select";
        cmd.value = rest.isEmpty() ? "Ice Barrage" : rest;
    }

    private static void parsePrayer(String rest, Command cmd) {
        if (rest == null || rest.isEmpty()) return;
        rest = rest.trim();
        if (rest.toLowerCase().startsWith("disable") || rest.equalsIgnoreCase("off")) {
            cmd.subCommand = "disable";
            int subColon = rest.indexOf(':');
            if (subColon >= 0) cmd.subValue = rest.substring(subColon + 1).trim();
        } else {
            cmd.value = rest;
        }
    }

    private static void parseAttack(String rest, Command cmd) {
        if (rest == null || rest.isEmpty()) return;
        rest = rest.trim().toLowerCase();
        if (rest.equals("last") || rest.equals("cast") || rest.equals("player")) {
            cmd.value = rest;
        } else if (rest.startsWith("id:")) {
            cmd.value = "id";
            try { cmd.npcId = Integer.parseInt(rest.substring(3).trim()); }
            catch (NumberFormatException e) { cmd.npcId = -1; }
        } else {
            cmd.value = rest;
        }
    }

    private static void parseObject(String rest, Command cmd) {
        if (rest == null || rest.isEmpty()) return;
        String[] segments = rest.split(":");
        cmd.value = segments[0].trim();
        if (segments.length >= 2) cmd.opcode = segments[1].trim();
        if (segments.length >= 3) {
            try { cmd.distance = Integer.parseInt(segments[2].trim()); }
            catch (NumberFormatException e) { cmd.distance = -1; }
        }
    }

    private static void parseOrOperator(String itemPart, Command cmd) {
        if (itemPart.contains("|")) {
            String[] parts = itemPart.split("\\|");
            cmd.value = parts[0].trim();
            for (int i = 1; i < parts.length; i++) cmd.orValues.add(parts[i].trim());
        } else {
            cmd.value = itemPart.trim();
        }
    }

    private static boolean isNumericIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        String cleaned = s.replace("(", "").replace(")", "").trim();
        try { Integer.parseInt(cleaned); return true; }
        catch (NumberFormatException e) { return false; }
    }
}
