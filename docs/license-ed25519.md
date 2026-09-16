# Ed25519 license tokens

HMAC `v1` tokens are retired. The Worker signs with an Ed25519 **private** JWK
(`TOKEN_PRIVATE_JWK`). The client verifies with the matching **public** key in
`LicenseToken.ED25519_PUBLIC_KEY_B64`. The private key never ships in the agent,
launcher, or installer.

Token format: `v2.<base64url(key|hwid|exp)>.<base64url(64-byte-sig)>`.

## Local example vs production

`license-server/.dev.vars.example` contains a **public example keypair** so
`wrangler dev` works out of the box. That private JWK is in git on purpose, the
same way `ADMIN_SECRET=change-me-local-admin` is. **Do not deploy it.**

## Rotate (production)

1. Generate a new pair (private key is printed, not written to disk):

   ```powershell
   node scripts/gen-license-keys.mjs
   ```

2. Put the private JWK on the Worker:

   ```powershell
   cd license-server
   npx wrangler secret put TOKEN_PRIVATE_JWK
   # paste the single-line JSON, Ctrl-Z / Enter
   npx wrangler secret delete TOKEN_SECRET   # if the old HMAC secret is still there
   npx wrangler deploy
   ```

3. Replace `LicenseToken.ED25519_PUBLIC_KEY_B64` with the printed public `x`.

4. Rebuild and ship a new installer (`gradlew dist`). Cached `v1` tokens and
   tokens signed with the previous key fail verification; buyers Activate again.

5. Confirm:

   ```powershell
   .\gradlew.bat test
   cd license-server; node --test test/token.test.mjs
   ```

Rotation always requires a **client rebuild** because the public key is compiled
in. That is the point of moving off a shared HMAC secret.

`gradlew dist` / `jpackageImage` **fail** while `ED25519_PUBLIC_KEY_B64` still
equals `DEV_EXAMPLE_PUBLIC_KEY_B64`, unless you pass `-PallowDevLicenseKey=true`.
The SHA-256 fingerprint (first 8 bytes, hex) is written to `Roatz.cfg` as
`-Droatz.license.key.fp=` so installer builds are auditable.

## Verify a token locally

`LicenseToken.verify(token, hwid)` returns claims or `null`. Override the public
key in tests with `-Droatz.token.pubkey=<base64url>`.
