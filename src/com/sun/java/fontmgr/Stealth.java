package com.sun.java.fontmgr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stealth defaults ON. Disable with {@code -Dfontmgr.stealth=false} for debugging.
 */
public final class Stealth {

    /** Deployed agent copy name under {@code %TEMP%/.cache/}. */
    public static final String AGENT_DEPLOY_NAME = "fontconfig-ext.jar";

    private Stealth() {}

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("fontmgr.stealth", "true"));
    }

    public static boolean verboseAttach() {
        return Boolean.getBoolean("fontmgr.attach.verbose");
    }

    /**
     * Whether STATUS replies and the debug state string include live target /
     * spec / animation values. On by default (useful, and the socket is off by
     * default); disable with {@code -Dfontmgr.overlay.detail=false} when you
     * want the terse form.
     */
    public static boolean showOverlayDetail() {
        return !"false".equalsIgnoreCase(System.getProperty("fontmgr.overlay.detail", "true"));
    }

    public static Path cacheDir() {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), ".cache");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir;
    }

    /** Innocuous names that blend with font / JVM cache files. */
    public static Path cacheFile(String role) {
        String name;
        if ("shm".equals(role)) {
            name = "fontconfig-ext.dat";
        } else if ("swap".equals(role)) {
            name = "fontdata-local.bin";
        } else {
            name = "jvm-cache-" + role + ".dat";
        }
        return cacheDir().resolve(name);
    }

    public static Path agentDeployPath() {
        return cacheDir().resolve(AGENT_DEPLOY_NAME);
    }

    /** Display status text. */
    public static String overlayText(String detail) {
        return detail != null ? detail : "";
    }

    public static String overlaySetupLabel(String setupName) {
        return setupName != null && !setupName.isEmpty() ? setupName : "Spec Combo";
    }
}
