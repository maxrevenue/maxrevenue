/**
 * Ed25519 license tokens: {@code v2.<base64url(key|hwid|exp)>.<base64url(sig)>}.
 *
 * Private material lives only in {@code TOKEN_PRIVATE_JWK} (wrangler secret /
 * .dev.vars). The Java client verifies with the matching public key.
 */

export async function signToken(
  privateJwkJson: string,
  key: string,
  hwid: string,
  exp: number,
): Promise<string> {
  if (!privateJwkJson) throw new Error("TOKEN_PRIVATE_JWK missing");
  let jwk: { kty?: string; crv?: string; d?: string; x?: string };
  try {
    jwk = JSON.parse(privateJwkJson) as { kty?: string; crv?: string; d?: string; x?: string };
  } catch {
    throw new Error("TOKEN_PRIVATE_JWK is not JSON");
  }
  if (jwk.kty !== "OKP" || jwk.crv !== "Ed25519" || !jwk.d || !jwk.x) {
    throw new Error("TOKEN_PRIVATE_JWK must be an Ed25519 JWK with d and x");
  }
  const cryptoKey = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "Ed25519" },
    false,
    ["sign"],
  );
  const payload = `${key}|${hwid}|${exp}`;
  const body = b64url(new TextEncoder().encode(payload));
  const signing = new TextEncoder().encode(`v2.${body}`);
  const sig = new Uint8Array(await crypto.subtle.sign("Ed25519", cryptoKey, signing));
  return `v2.${body}.${b64url(sig)}`;
}

export function b64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
