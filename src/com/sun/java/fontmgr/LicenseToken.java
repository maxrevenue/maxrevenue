package com.sun.java.fontmgr;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Compact Ed25519 token: {@code v2.<base64url(key|hwid|exp)>.<base64url(sig)>}.
 *
 * <p>The Worker ({@code license-server}) signs with {@code TOKEN_PRIVATE_JWK}.
 * This class holds only the matching public key. HMAC {@code v1} tokens are
 * rejected — the old shared secret is gone from the client.
 *
 * <p>The default public key is the <em>local example</em> pair in
 * {@code license-server/.dev.vars.example}. Generate a production pair with
 * {@code node scripts/gen-license-keys.mjs} before deploy; see
 * {@code docs/license-ed25519.md}.
 */
public final class LicenseToken {

    /**
     * Example / local-dev Ed25519 public key (32 bytes, base64url).
     * Override with {@code -Droatz.token.pubkey=} (tests, production rotation).
     */
    public static final String ED25519_PUBLIC_KEY_B64 =
            "6dGojlBqZ3Qt_roZRBxMRwPLEZfZLgak1iZ8mToDQx8";

    private static final EdDSANamedCurveSpec ED25519 =
            EdDSANamedCurveTable.getByName("Ed25519");

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

    /** Base64url public key used for verify. Never a private key. */
    public static String publicKeyB64() {
        String override = System.getProperty("roatz.token.pubkey");
        if (override != null && !override.isEmpty()) return override.trim();
        return ED25519_PUBLIC_KEY_B64;
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
        if (!"v2".equals(parts[0])) return null;
        if (parts[1].isEmpty() || parts[2].isEmpty()) return null;
        try {
            byte[] actual = b64urlDecode(parts[2]);
            if (actual == null || actual.length != 64) return null;
            byte[] msg = ("v2." + parts[1]).getBytes(StandardCharsets.UTF_8);
            if (!ed25519Verify(publicKeyBytes(), msg, actual)) return null;
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

    static byte[] publicKeyBytes() {
        byte[] raw = b64urlDecode(publicKeyB64());
        if (raw == null || raw.length != 32) {
            throw new IllegalStateException("Ed25519 public key must be 32 bytes base64url");
        }
        return raw;
    }

    static boolean ed25519Verify(byte[] pub32, byte[] message, byte[] sig64) {
        try {
            EdDSAPublicKeySpec spec = new EdDSAPublicKeySpec(pub32, ED25519);
            PublicKey pub = new EdDSAPublicKey(spec);
            Signature engine = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            engine.initVerify(pub);
            engine.update(message);
            return engine.verify(sig64);
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] b64urlDecode(String s) {
        try {
            return Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static long nowUnix() {
        return System.currentTimeMillis() / 1000L;
    }
}
