package roatz.launcher;

import java.awt.Color;
import java.awt.Font;

/**
 * Launcher palette. Delegates to the shared {@link com.sun.java.fontmgr.Theme} so
 * the launcher and the in-game HUD are the same twelve colours, defined once. The
 * short local names exist only because they read better at the call sites.
 */
final class Theme {
    static final Color BG         = com.sun.java.fontmgr.Theme.BG_DARK;
    static final Color CARD       = com.sun.java.fontmgr.Theme.CARD_BG;
    static final Color TITLE      = com.sun.java.fontmgr.Theme.TITLE_BG;
    static final Color FG         = com.sun.java.fontmgr.Theme.FG_BRIGHT;
    static final Color MUTED      = com.sun.java.fontmgr.Theme.FG_MUTED;
    static final Color ACCENT     = com.sun.java.fontmgr.Theme.ACCENT_GOLD;
    static final Color GREEN      = com.sun.java.fontmgr.Theme.ACCENT_GREEN;
    static final Color RED        = com.sun.java.fontmgr.Theme.ACCENT_RED;
    static final Color BLUE       = com.sun.java.fontmgr.Theme.ACCENT_BLUE;
    /** Text on an {@link #ACCENT} fill. */
    static final Color ON_ACCENT  = com.sun.java.fontmgr.Theme.ON_ACCENT;
    static final Color BTN        = com.sun.java.fontmgr.Theme.BTN_BG;
    static final Color BTN_BORDER = com.sun.java.fontmgr.Theme.BTN_BORDER;

    static Font ui(float size, int style) {
        return new Font("Segoe UI", style, Math.round(size));
    }

    private Theme() {}
}
