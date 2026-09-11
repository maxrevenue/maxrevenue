/**
 * Roatz license API: issue / activate / check / revoke.
 *
 * Secrets (wrangler secret put, or .dev.vars locally):
 *   ADMIN_SECRET  — operator scripts
 *   TOKEN_SECRET  — must match LicenseToken.HMAC_SECRET
 *
 * KV binding: LICENSES  (namespace id pinned in wrangler.jsonc)
 */

interface Env {
  LICENSES: KVNamespace;
  ADMIN_SECRET: string;
  TOKEN_SECRET: string;
}

interface LicenseRecord {
  status: "issued" | "active" | "revoked";
  hwid: string | null;
  createdAt: number;
  activatedAt: number | null;
  note: string;
}

const TOKEN_TTL_SECONDS = 72 * 3600;
const KEY_RE = /^RZ-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$/;
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/v1/health") {
      return json({ ok: true, name: "roatz-license" });
    }
    if (request.method !== "POST") {
      return json({ ok: false, error: "method" }, 405);
    }

    const path = url.pathname;
    try {
      if (path === "/v1/issue") return await issue(request, env);
      if (path === "/v1/revoke") return await revoke(request, env);
      if (path === "/v1/activate") return await activate(request, env);
      if (path === "/v1/check") return await check(request, env);
      return json({ ok: false, error: "not_found" }, 404);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "error";
      return json({ ok: false, error: "server", detail: msg }, 500);
    }
  },
};

async function issue(request: Request, env: Env): Promise<Response> {
  if (!adminOk(request, env)) return json({ ok: false, error: "unauthorized" }, 401);
  const body = await readJson(request);
  const note = typeof body.note === "string" ? body.note.slice(0, 200) : "";
  for (let i = 0; i < 8; i++) {
    const key = randomKey();
    const existing = await env.LICENSES.get(kvKey(key));
    if (existing) continue;
    const rec: LicenseRecord = {
      status: "issued",
      hwid: null,
      createdAt: Date.now(),
      activatedAt: null,
      note,
    };
    await env.LICENSES.put(kvKey(key), JSON.stringify(rec));
    return json({ ok: true, key });
  }
  return json({ ok: false, error: "issue_failed" }, 500);
}

async function revoke(request: Request, env: Env): Promise<Response> {
  if (!adminOk(request, env)) return json({ ok: false, error: "unauthorized" }, 401);
  const body = await readJson(request);
  const key = normalizeKey(body.key);
  if (!key || !KEY_RE.test(key)) return json({ ok: false, error: "invalid" }, 400);
  const raw = await env.LICENSES.get(kvKey(key));
  if (!raw) return json({ ok: false, error: "invalid" }, 401);
  const rec = JSON.parse(raw) as LicenseRecord;
  rec.status = "revoked";
  await env.LICENSES.put(kvKey(key), JSON.stringify(rec));
  return json({ ok: true, key, status: "revoked" });
}

async function activate(request: Request, env: Env): Promise<Response> {
  return bindAndToken(request, env, true);
}

async function check(request: Request, env: Env): Promise<Response> {
  return bindAndToken(request, env, false);
}

async function bindAndToken(request: Request, env: Env, allowBind: boolean): Promise<Response> {
  const body = await readJson(request);
  const key = normalizeKey(body.key);
  const hwid = typeof body.hwid === "string" ? body.hwid.trim().toLowerCase() : "";
  if (!key || !KEY_RE.test(key) || !hwid || hwid.length < 8) {
    return json({ ok: false, error: "invalid" }, 401);
  }
  const raw = await env.LICENSES.get(kvKey(key));
  if (!raw) return json({ ok: false, error: "invalid" }, 401);
  const rec = JSON.parse(raw) as LicenseRecord;
  if (rec.status === "revoked") return json({ ok: false, error: "revoked" }, 403);

  if (rec.hwid && rec.hwid !== hwid) {
    return json({ ok: false, error: "other_pc" }, 409);
  }
  if (!rec.hwid) {
    if (!allowBind) return json({ ok: false, error: "invalid" }, 401);
    rec.hwid = hwid;
    rec.status = "active";
    rec.activatedAt = Date.now();
    await env.LICENSES.put(kvKey(key), JSON.stringify(rec));
  }

  const exp = Math.floor(Date.now() / 1000) + TOKEN_TTL_SECONDS;
  const token = await signToken(env.TOKEN_SECRET, key, hwid, exp);
  return json({ ok: true, token, exp, key });
}

async function signToken(secret: string, key: string, hwid: string, exp: number): Promise<string> {
  if (!secret) throw new Error("TOKEN_SECRET missing");
  const payload = `${key}|${hwid}|${exp}`;
  const body = b64url(new TextEncoder().encode(payload));
  const signing = new TextEncoder().encode(`v1.${body}`);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, signing));
  return `v1.${body}.${b64url(sig)}`;
}

function adminOk(request: Request, env: Env): boolean {
  const got = request.headers.get("X-Roatz-Admin") || "";
  const want = env.ADMIN_SECRET || "";
  if (!want || got.length !== want.length) {
    // still compare to keep timing flatter when lengths match
    return false;
  }
  return timingEqual(got, want);
}

function timingEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const len = Number(request.headers.get("content-length") || "0");
  if (len > 8192) throw new Error("body too large");
  const text = await request.text();
  if (!text) return {};
  if (text.length > 8192) throw new Error("body too large");
  const parsed: unknown = JSON.parse(text);
  if (typeof parsed !== "object" || parsed === null) return {};
  return parsed as Record<string, unknown>;
}

function normalizeKey(raw: unknown): string {
  if (typeof raw !== "string") return "";
  return raw.trim().toUpperCase().replace(/\s+/g, "");
}

function kvKey(key: string): string {
  return "lic:" + key;
}

function randomKey(): string {
  return `RZ-${group()}-${group()}-${group()}`;
}

function group(): string {
  const buf = new Uint8Array(4);
  crypto.getRandomValues(buf);
  let s = "";
  for (const b of buf) s += ALPHABET[b % ALPHABET.length];
  return s;
}

function b64url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
