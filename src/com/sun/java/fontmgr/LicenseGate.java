package com.sun.java.fontmgr;

/**
 * Agent-side license check. Local/dev attach sets {@code -Dfontmgr.license.bypass=true}
 * on the game JVM ({@code launch.ps1}, verify harness). The public launcher never does.
 */
public final class LicenseGate {

    private LicenseGate() {}

    public static boolean bypassEnabled() {
        return Boolean.getBoolean("fontmgr.license.bypass")
                || Boolean.getBoolean("roatz.license.bypass");
    }

    public static boolean allow(String token) {
        if (bypassEnabled()) return true;
        if (token == null || token.trim().isEmpty()) return false;
        try {
            return LicenseToken.verify(token.trim(), Hwid.current()) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
