package com.sun.java.fontmgr;

import java.awt.AWTEvent;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

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
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
            if (e.getID() != MouseEvent.MOUSE_PRESSED) return;
            if (!(e instanceof MouseEvent)) return;
            MouseEvent me = (MouseEvent) e;
            // Ignore overlay chrome; only yield on game clicks.
            if (me.getComponent() != null) {
                String cn = me.getComponent().getClass().getName();
                if (cn.startsWith("javax.swing") || cn.startsWith("com.sun.java.fontmgr")) return;
            }
            int tick = currentTick();
            if (tick >= 0) pauseUntilTick = tick + 4;
        }, AWTEvent.MOUSE_EVENT_MASK);

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

    private int currentTick() {
        return script != null ? script.currentTick : -1;
    }
}
