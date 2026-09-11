package com.sun.java.fontmgr;

import java.awt.Color;

/**
 * The single source of truth for the Roatz palette, shared by the in-game HUD
 * ({@link OverlayUI}) and the launcher ({@code roatz.launcher.Theme}).
 *
 * <p><b>Why it lives here.</b> The launcher already depends on this package (it
 * imports {@link Product}, {@link Hwid} and {@link LicenseToken}), so the agent
 * is the one module both surfaces can see. The two palettes had drifted into
 * being separately maintained copies of the same twelve colours — same RGB
 * values, but two places to change and two chances to disagree, on the two
 * screens a buyer sees first.
 *
 * <p>These are <em>opaque</em> base colours. The HUD floats over the game, so it
 * applies its own alpha on top (see {@link #withAlpha}); the launcher is a normal
 * window and uses them directly. Keeping the alpha out of the shared constants
 * is what lets one palette serve both.
 */
public final class Theme {

    // ── Surfaces ─────────────────────────────────────────────────────────────
    /** Window / panel background. */
    public static final Color BG_DARK  = new Color(20, 22, 26);
    /** Raised card background. */
    public static final Color CARD_BG  = new Color(30, 33, 38);
    /** Title bar / inset background. */
    public static final Color TITLE_BG = new Color(38, 42, 48);

    // ── Text ─────────────────────────────────────────────────────────────────
    /** Primary text. */
    public static final Color FG_BRIGHT = new Color(240, 242, 245);
    /** Secondary / hint text. */
    public static final Color FG_MUTED  = new Color(150, 155, 165);
    /** Text drawn on top of {@link #ACCENT_GOLD} (the primary button). */
    public static final Color ON_ACCENT = new Color(28, 30, 34);

    // ── Accents ──────────────────────────────────────────────────────────────
    /** Brand colour. */
    public static final Color ACCENT_GOLD   = new Color(255, 195, 60);
    public static final Color ACCENT_BLUE   = new Color(85, 145, 255);
    public static final Color ACCENT_GREEN  = new Color(65, 195, 95);
    public static final Color ACCENT_RED    = new Color(235, 75, 75);
    public static final Color ACCENT_PURPLE = new Color(160, 110, 240);
    public static final Color ACCENT_ORANGE = new Color(255, 140, 60);

    // ── Controls ─────────────────────────────────────────────────────────────
    /** Neutral button background. */
    public static final Color BTN_BG     = new Color(42, 45, 52);
    /** Button and chip border. */
    public static final Color BTN_BORDER = new Color(60, 65, 75);

    /** Opaque base colour at the given alpha — the HUD's translucent surfaces. */
    public static Color withAlpha(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private Theme() {}
}
