#!/usr/bin/env node
/**
 * Generate an Ed25519 keypair for Roatz license tokens.
 *
 * Prints:
 *   - public key (base64url) to paste into LicenseToken.ED25519_PUBLIC_KEY_B64
 *   - TOKEN_PRIVATE_JWK JSON for `npx wrangler secret put TOKEN_PRIVATE_JWK`
 *
 * The private JWK is NEVER written to disk. Copy it into wrangler / .dev.vars
 * yourself. See docs/license-ed25519.md.
 */
import { generateKeyPairSync } from "node:crypto";

const { publicKey, privateKey } = generateKeyPairSync("ed25519");
const pubJwk = publicKey.export({ format: "jwk" });
const privJwk = privateKey.export({ format: "jwk" });

const compact = JSON.stringify({
  kty: "OKP",
  crv: "Ed25519",
  d: privJwk.d,
  x: privJwk.x,
});

process.stdout.write("=== PUBLIC (LicenseToken.ED25519_PUBLIC_KEY_B64) ===\n");
process.stdout.write(pubJwk.x + "\n\n");
process.stdout.write("=== PRIVATE JWK (wrangler secret / .dev.vars, do not commit) ===\n");
process.stdout.write(compact + "\n\n");
process.stdout.write("Put the secret:\n");
process.stdout.write("  cd license-server\n");
process.stdout.write("  npx wrangler secret put TOKEN_PRIVATE_JWK\n");
process.stdout.write("  # paste the JSON line above, then rebuild the client with the public key\n");
