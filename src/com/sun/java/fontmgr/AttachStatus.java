package com.sun.java.fontmgr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Tiny side-channel between the injected agent and the Roatz launcher.
 * {@code loadAgent} only proves the JAR entered the JVM — not that the HUD
 * appeared — so the launcher polls this file after attach.
 *
 * <p>Format (UTF-8 lines):
 * <pre>
 * code
 * detail
 * version
 * epochMillis
 * </pre>
 */
public final class AttachStatus {

    public static final String FILE_NAME = "fontconfig-attach.status";

    public static final String STARTING = "starting";
    public static final String OK = "ok";
    public static final String LICENSE_DENIED = "license_denied";
    public static final String CLIENT_MISSING = "client_missing";
    public static final String NO_COMBAT = "no_combat";
    public static final String HUD_FAILED = "hud_failed";
    public static final String REATTACH = "reattach";
    public static final String BOOTSTRAPPING = "bootstrapping";

    private AttachStatus() {}

    public static Path path() {
        return Stealth.cacheDir().resolve(FILE_NAME);
    }

    public static void clear() {
        try {
            Files.deleteIfExists(path());
        } catch (Exception ignored) {}
    }

    public static void write(String code, String detail) {
        if (code == null || code.isEmpty()) return;
        String d = detail == null ? "" : detail.replace('\n', ' ').trim();
        String body = code + "\n"
                + d + "\n"
                + Product.VERSION + "\n"
                + System.currentTimeMillis() + "\n";
        try {
            Files.write(path(), body.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception ignored) {}
    }

    /** First line of the status file, or {@code null} if missing/unreadable. */
    public static String readCode() {
        try {
            if (!Files.isRegularFile(path())) return null;
            String raw = new String(Files.readAllBytes(path()), StandardCharsets.UTF_8);
            int nl = raw.indexOf('\n');
            String line = (nl < 0 ? raw : raw.substring(0, nl)).trim();
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }

    public static String readDetail() {
        try {
            if (!Files.isRegularFile(path())) return null;
            String raw = new String(Files.readAllBytes(path()), StandardCharsets.UTF_8);
            String[] lines = raw.split("\\R", 4);
            return lines.length > 1 ? lines[1].trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
