package com.sun.java.fontmgr;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;

/**
 * Yields auto-combat when you click or hold pause, so the client looks like
 * a person took over instead of fighting the script.
 */
final class PauseManager {

    private static final PauseManager INSTANCE = new PauseManager();

    private volatile boolean hotkeyPause;
    private volatile int pauseUntilTick = -1;
    private volatile boolean installed;
    private CombatScript script;

    static PauseManager get() {
        return INSTANCE;
    }

    void init(CombatScript script) {
        this.script = script;
        if (installed) return;
        // Game clicks: {@link ClientHooks#onMousePressed} via MouseHandler bytecode.

        KeyEventDispatcher d = e -> {
            if (e.getKeyCode() != KeyEvent.VK_PAUSE && e.getKeyCode() != KeyEvent.VK_SCROLL_LOCK) {
                return false;
            }
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                hotkeyPause = !hotkeyPause;
            }
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(d);
        installed = true;
    }

    boolean isPaused(int tick) {
        if (hotkeyPause) return true;
        return pauseUntilTick >= 0 && tick <= pauseUntilTick;
    }

    boolean isHotkeyPaused() {
        return hotkeyPause;
    }

    /** Called from {@link ClientHooks} on every game-canvas left/right press. */
    void onGameMousePressed() {
        int tick = currentTick();
        if (tick >= 0) pauseUntilTick = tick + 4;
    }

    private int currentTick() {
        return script != null ? script.currentTick : -1;
    }
}
