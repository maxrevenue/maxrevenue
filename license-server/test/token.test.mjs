import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";

/**
 * Mirrors license-server/src/token.ts against Node WebCrypto (same API as
 * Cloudflare Workers). Keep the signing string (`v2.` + body) in sync.
 */

const EXAMPLE_JWK = {
  kty: "OKP",
  crv: "Ed25519",
  d: "AecpNXQnt_omUgK9gH5fBrjrJLYlwO9OAlLk_K2-eYM",
  x: "6dGojlBqZ3Qt_roZRBxMRwPLEZfZLgak1iZ8mToDQx8",
};

const GOLDEN =
  "v2.UlotVEVTVC1LRVkxLUtFWTJ8ZGVhZGJlZWZkZWFkYmVlZnwyMDAwMDAwMDAw.peD2Wb8NDRcZgOzp9jC9_JVnUtxZxiMcBl8wUpNJBjypoQez73paVfH527xJFxy9awak8bo7SSQhPdubnh34DQ";

function b64url(bytes) {
  return Buffer.from(bytes).toString("base64url");
}

async function signToken(jwk, key, hwid, exp) {
  if (!jwk) throw new Error("TOKEN_PRIVATE_JWK missing");
  const cryptoKey = await webcrypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "Ed25519" },
    false,
    ["sign"],
  );
  const payload = `${key}|${hwid}|${exp}`;
  const body = b64url(Buffer.from(payload, "utf8"));
  const signing = Buffer.from(`v2.${body}`, "utf8");
  const sig = new Uint8Array(await webcrypto.subtle.sign("Ed25519", cryptoKey, signing));
  return `v2.${body}.${b64url(sig)}`;
}

test("signToken produces v2 tokens with 64-byte signatures", async () => {
  const token = await signToken(EXAMPLE_JWK, "RZ-TEST-KEY1-KEY2", "deadbeefdeadbeef", 2000000000);
  const parts = token.split(".");
  assert.equal(parts[0], "v2");
  assert.equal(parts.length, 3);
  assert.equal(Buffer.from(parts[2], "base64url").length, 64);
});

test("WebCrypto mint matches the golden Node crypto.sign token", async () => {
  const token = await signToken(EXAMPLE_JWK, "RZ-TEST-KEY1-KEY2", "deadbeefdeadbeef", 2000000000);
  assert.equal(token, GOLDEN);
});

test("missing JWK throws", async () => {
  await assert.rejects(() => signToken(null, "k", "h", 1), /TOKEN_PRIVATE_JWK missing/);
});
