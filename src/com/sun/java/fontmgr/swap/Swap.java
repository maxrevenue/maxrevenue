package com.sun.java.fontmgr.swap;

import java.awt.event.KeyEvent;

/** Named hotkeyed command script (Ganom Advanced Swapper style). */
public class Swap {
    private String name;
    private String commands;
    private int keyCode = -1;
    private int modifiers = 0;

    public Swap() {}

    public Swap(String name, String commands) {
        this.name = name;
        this.commands = commands;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCommands() { return commands; }
    public void setCommands(String commands) { this.commands = commands; }

    public int getKeyCode() { return keyCode; }
    public void setKeyCode(int keyCode) { this.keyCode = keyCode; }

    public int getModifiers() { return modifiers; }
    public void setModifiers(int modifiers) { this.modifiers = modifiers; }

    public boolean hasHotkey() { return keyCode != -1; }

    public String hotkeyText() {
        if (!hasHotkey()) return "Not set";
        String mod = "";
        if ((modifiers & KeyEvent.SHIFT_DOWN_MASK) != 0) mod += "Shift+";
        if ((modifiers & KeyEvent.CTRL_DOWN_MASK) != 0) mod += "Ctrl+";
        if ((modifiers & KeyEvent.ALT_DOWN_MASK) != 0) mod += "Alt+";
        return mod + KeyEvent.getKeyText(keyCode);
    }
}
