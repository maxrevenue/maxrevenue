package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import com.sun.java.fontmgr.CombatScript;
import com.sun.java.fontmgr.FontManager;
import com.sun.java.fontmgr.swap.CommandParser.Command;

/** Runs a swap's command lines on the client tick thread. */
public final class SwapDispatcher {

    private final CombatScript script;

    public SwapDispatcher(CombatScript script) {
        this.script = script;
    }

    public void run(Swap swap) {
        if (swap == null || swap.getCommands() == null || script == null) return;
        final String name = swap.getName();
        final String body = swap.getCommands();
        ClientThreadGuard.get().invokeLater(() -> {
            FontManager.log("[Swapper] run: " + name);
            // Clear click-cast arm so inventory wields are not blocked by spellSelected.
            script.clearLeftClickArmPublic();
            try { script.sendGameMessage("Swap: " + name); } catch (Exception ignored) {}
            CommandExecutor executor = new CommandExecutor(script);
            int ran = 0;
            for (String line : body.split("\\R")) {
                String cmd = line.trim();
                if (cmd.isEmpty()) continue;
                Command parsed = CommandParser.parse(cmd);
                if (parsed != null) {
                    boolean ok = executor.execute(parsed);
                    ran++;
                    FontManager.debug("[Swapper] " + parsed.type + "=" + parsed.value + " ok=" + ok);
                }
            }
            FontManager.log("[Swapper] done: " + name + " lines=" + ran);
        });
    }
}
