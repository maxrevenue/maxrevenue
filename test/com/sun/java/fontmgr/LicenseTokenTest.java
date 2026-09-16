package com.sun.java.fontmgr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden vectors minted with Node {@code crypto.sign} (Ed25519), the same
 * algorithm Cloudflare Workers WebCrypto uses. Would fail under the old HMAC
 * {@code v1} verifier.
 */
public class LicenseTokenTest {

    private static final String EXAMPLE_PUB = "6dGojlBqZ3Qt_roZRBxMRwPLEZfZLgak1iZ8mToDQx8";
    private static final String OTHER_PUB = "mdWpqI_0q2I5EgmU4_1hkhZPoa4QUCJc0GqFPLjcLd8";
    private static final String HWID = "deadbeefdeadbeef";

    private static final String VALID =
            "v2.UlotVEVTVC1LRVkxLUtFWTJ8ZGVhZGJlZWZkZWFkYmVlZnwyMDAwMDAwMDAw"
                    + ".peD2Wb8NDRcZgOzp9jC9_JVnUtxZxiMcBl8wUpNJBjypoQez73paVfH527xJFxy9awak8bo7SSQhPdubnh34DQ";
    private static final String EXPIRED =
            "v2.UlotVEVTVC1LRVkxLUtFWTJ8ZGVhZGJlZWZkZWFkYmVlZnwxMDAwMDAwMDAw"
                    + ".KBo19jjKXvHTZCqWkRg5gGPnzscFvLJgktLWg9MoOYngyzwQ1AMvOUFhvJFpUUJA8lQLjeBPQnLe0JInhMq8CA";
    private static final String OTHER_HWID =
            "v2.UlotVEVTVC1LRVkxLUtFWTJ8ZmZmZmZmZmZlZWVlZWVlZXwyMDAwMDAwMDAw"
                    + ".UQUi0zsJ7c8oZvuzeZNx1uD8oil3XUZA6zs0NzoQ7e3Tm80Paz90qOJVuoQHOg4p0l7ysDXtNLcbgtkNMMBvAw";

    @BeforeEach
    public void pinExampleKey() {
        System.setProperty("roatz.token.pubkey", EXAMPLE_PUB);
    }

    @AfterEach
    public void clearOverride() {
        System.clearProperty("roatz.token.pubkey");
    }

    @Test
    public void validTokenVerifies() {
        LicenseToken.Claims c = LicenseToken.verify(VALID, HWID);
        assertNotNull(c);
        assertEquals("RZ-TEST-KEY1-KEY2", c.key);
        assertEquals(HWID, c.hwid);
        assertEquals(2000000000L, c.expUnix);
    }

    @Test
    public void differentKeyIsRejected() {
        System.setProperty("roatz.token.pubkey", OTHER_PUB);
        assertNull(LicenseToken.verify(VALID, HWID));
    }

    @Test
    public void tamperedClaimsAreRejected() {
        String[] parts = VALID.split("\\.", 3);
        String payload = new String(LicenseToken.b64urlDecode(parts[1]));
        String tampered = payload.replace("RZ-TEST-KEY1-KEY2", "RZ-HACK-KEY1-KEY2");
        String body = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String forged = "v2." + body + "." + parts[2];
        assertNull(LicenseToken.parseAndCheckSig(forged));
        assertNull(LicenseToken.verify(forged, HWID));
    }

    @Test
    public void wrongHwidIsRejected() {
        assertNull(LicenseToken.verify(VALID, "ffffffffeeeeeeee"));
        LicenseToken.Claims other = LicenseToken.parseAndCheckSig(OTHER_HWID);
        assertNotNull(other);
        assertNull(LicenseToken.verify(OTHER_HWID, HWID));
    }

    @Test
    public void expiredIsRejected() {
        LicenseToken.Claims c = LicenseToken.parseAndCheckSig(EXPIRED);
        assertNotNull(c);
        assertNull(LicenseToken.verify(EXPIRED, HWID));
    }

    @Test
    public void hmacV1IsRejected() {
        assertNull(LicenseToken.parseAndCheckSig(
                "v1.UlotVEVTVC1LRVkxLUtFWTJ8ZGVhZGJlZWZkZWFkYmVlZnwyMDAwMDAwMDAw.AAAAAAAAAAAAAAAAAAAAAA"));
        assertNull(LicenseToken.verify(
                "v1.UlotVEVTVC1LRVkxLUtFWTJ8ZGVhZGJlZWZkZWFkYmVlZnwyMDAwMDAwMDAw.AAAAAAAAAAAAAAAAAAAAAA",
                HWID));
    }

    @Test
    public void garbageIsRejected() {
        assertNull(LicenseToken.verify(null, HWID));
        assertNull(LicenseToken.verify("", HWID));
        assertNull(LicenseToken.verify("v2.not.a.token", HWID));
        assertNull(LicenseToken.verify(VALID, null));
    }

    @Test
    public void compiledKeyStillMatchesTheGitExampleUntilRotated() {
        assertEquals(EXAMPLE_PUB, LicenseToken.DEV_EXAMPLE_PUBLIC_KEY_B64);
        assertEquals(EXAMPLE_PUB, LicenseToken.ED25519_PUBLIC_KEY_B64);
        assertTrue(LicenseToken.compiledKeyIsDevExample());
        String fp = LicenseToken.compiledPublicKeyFingerprint();
        assertEquals(16, fp.length());
        assertEquals(fp, LicenseToken.fingerprintOf(EXAMPLE_PUB));
    }
}
