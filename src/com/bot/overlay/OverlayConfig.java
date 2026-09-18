package com.bot.overlay;

/** Flag-gated render-side overlay ({@code -Droatz.overlay=true}, default off). */
public final class OverlayConfig {

    private OverlayConfig() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("roatz.overlay");
    }
}
