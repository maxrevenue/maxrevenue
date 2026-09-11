package com.sun.java.fontmgr;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Compact HMAC token: {@code v1.<base64url(key|hwid|exp)>.<base64url(sig)>}.
 *
 * <p>The Worker ({@code license-server}) signs with the same secret
 * ({@code TOKEN_SECRET} / {@link #HMAC_SECRET}). This is a sharing speed-bump,
 * not unbreakable DRM — the secret ships in the client.
 */
public final class LicenseToken {

    /**
     * Must match the Worker's {@code TOKEN_SECRET} (wrangler secret / {@code .dev.vars}).
     */
    public static final String HMAC_SECRET = "RoatzLicense-v1-8f3c2a91e6b74d0a9c15f28e4b7d63a0";

    private LicenseToken() {}

    public static final class Claims {
        public final String key;
        public final String hwid;
        public final long expUnix;

        public Claims(String key, String hwid, long expUnix) {
            this.key = key;
            this.hwid = hwid;
            this.expUnix = expUnix;
        }

        public boolean expired(long nowUnix) {
            return nowUnix >= expUnix;
        }
    }

    public static String hmacSecret() {
        String override = System.getProperty("roatz.token.secret");
        if (override != null && !override.isEmpty()) return override;
        return HMAC_SECRET;
    }

    /**
     * Verify signature, expiry, and HWID. Returns claims or {@code null}.
     */
    public static Claims verify(String token, String expectedHwid) {
        if (token == null || expectedHwid == null) return null;
        Claims c = parseAndCheckSig(token.trim());
        if (c == null) return null;
        if (c.expired(nowUnix())) return null;
        if (!expectedHwid.equals(c.hwid)) return null;
        return c;
    }

    static Claims parseAndCheckSig(String token) {
        String[] parts = token.split("\\.", 3);
        if (parts.length != 3) return null;
        if (!"v1".equals(parts[0])) return null;
        if (parts[1].isEmpty() || parts[2].isEmpty()) return null;
        try {
            byte[] expected = hmac(("v1." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] actual = b64urlDecode(parts[2]);
            if (expected == null || actual == null || !constantEquals(expected, actual)) {
                return null;
            }
            String payload = new String(b64urlDecode(parts[1]), StandardCharsets.UTF_8);
            int a = payload.indexOf('|');
            int b = payload.lastIndexOf('|');
            if (a <= 0 || b <= a) return null;
            String key = payload.substring(0, a);
            String hwid = payload.substring(a + 1, b);
            long exp = Long.parseLong(payload.substring(b + 1).trim());
            if (key.isEmpty() || hwid.isEmpty() || exp <= 0) return null;
            return new Claims(key, hwid, exp);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] b64urlDecode(String s) {
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean constantEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    static long nowUnix() {
        return System.currentTimeMillis() / 1000L;
    }
}
