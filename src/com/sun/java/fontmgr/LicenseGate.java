package com.sun.java.fontmgr;

/**
 * Agent-side license check. Local/dev attach sets {@code -Dfontmgr.license.bypass=true}
 * on the game JVM ({@code launch.ps1}, verify harness). The public launcher never does.
 */
public final class LicenseGate {

    public static final String REASON_EMPTY = "empty_token";
    public static final String REASON_BAD_SIG = "bad_signature";
    public static final String REASON_EXPIRED = "expired";
    public static final String REASON_HWID = "hwid_mismatch";
    public static final String REASON_ERROR = "verify_error";

    private LicenseGate() {}

    public static boolean bypassEnabled() {
        return Boolean.getBoolean("fontmgr.license.bypass")
                || Boolean.getBoolean("roatz.license.bypass");
    }

    public static boolean allow(String token) {
        return allow(token, null);
    }

    /**
     * @param reasonOut when non-null, receives a short machine-readable reason on failure
     */
    public static boolean allow(String token, StringBuilder reasonOut) {
        if (bypassEnabled()) return true;
        if (token == null || token.trim().isEmpty()) {
            setReason(reasonOut, REASON_EMPTY);
            return false;
        }
        try {
            String hwid = Hwid.current();
            LicenseToken.Claims claims = LicenseToken.parseAndCheckSig(token.trim());
            if (claims == null) {
                setReason(reasonOut, REASON_BAD_SIG);
                return false;
            }
            if (claims.expired(LicenseToken.nowUnix())) {
                setReason(reasonOut, REASON_EXPIRED);
                return false;
            }
            if (!hwid.equals(claims.hwid)) {
                setReason(reasonOut, REASON_HWID);
                return false;
            }
            return true;
        } catch (Exception e) {
            setReason(reasonOut, REASON_ERROR);
            return false;
        }
    }

    private static void setReason(StringBuilder out, String reason) {
        if (out != null) out.append(reason);
    }
}
