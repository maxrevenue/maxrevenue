/**
 * oneclick-fortune Worker
 * - Serves SPA assets
 * - POST /api/leads — capture squeeze-page opt-ins
 * - GET  /api/leads — admin list (requires ADMIN_SECRET bearer token)
 * - GET  /api/health
 */

const SETUP_MAP_PATH = "/setup-map";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS" && url.pathname.startsWith("/api/")) {
      return new Response(null, { status: 204, headers: cors() });
    }

    if (url.pathname === "/api/health") {
      return json({
        ok: true,
        service: "oneclick-fortune",
        leads: Boolean(env.LEADS),
        email: Boolean(env.RESEND_API_KEY),
      });
    }

    if (url.pathname === "/api/leads" && request.method === "POST") {
      return captureLead(request, env, url);
    }

    if (url.pathname === "/api/leads" && request.method === "GET") {
      return listLeads(request, env);
    }

    if (env.ASSETS) {
      return env.ASSETS.fetch(request);
    }

    return new Response("Not found", { status: 404 });
  },
};

async function captureLead(request, env, url) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "Invalid JSON body" }, 400);
  }

  const name = String(body?.name || "").trim().slice(0, 120);
  const email = String(body?.email || "").trim().toLowerCase().slice(0, 254);
  const source = String(body?.source || "squeeze").trim().slice(0, 64);

  if (!name) return json({ error: "First name is required" }, 400);
  if (!isValidEmail(email)) return json({ error: "Valid email is required" }, 400);
  if (!env.LEADS) return json({ error: "Lead storage is not configured" }, 503);

  const id = crypto.randomUUID();
  const createdAt = new Date().toISOString();
  const record = {
    id,
    name,
    email,
    source,
    createdAt,
    userAgent: request.headers.get("user-agent") || "",
    country: request.cf?.country || "",
  };

  // email as secondary key for simple dedupe lookup; keep all submissions under id + index
  const emailKey = `email:${email}`;
  const existing = await env.LEADS.get(emailKey, { type: "json" });
  await env.LEADS.put(`lead:${id}`, JSON.stringify(record));
  await env.LEADS.put(emailKey, JSON.stringify({ ...record, previousId: existing?.id || null }));

  // Maintain a recent index (newest first, capped)
  const indexKey = "index:recent";
  const recent = (await env.LEADS.get(indexKey, { type: "json" })) || [];
  const next = [{ id, email, name, createdAt, source }, ...recent.filter((r) => r.email !== email)].slice(0, 500);
  await env.LEADS.put(indexKey, JSON.stringify(next));

  const mapUrl = new URL(SETUP_MAP_PATH, url.origin).toString();
  let emailed = false;
  let emailError = null;

  if (env.RESEND_API_KEY) {
    try {
      await sendLeadEmails(env, { name, email, mapUrl });
      emailed = true;
    } catch (err) {
      emailError = String(err?.message || err);
      console.error("lead email failed", emailError);
    }
  }

  return json({
    ok: true,
    id,
    mapUrl,
    emailed,
    emailError: emailed ? null : emailError,
  });
}

async function listLeads(request, env) {
  const auth = request.headers.get("authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!env.ADMIN_SECRET || token !== env.ADMIN_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }
  if (!env.LEADS) return json({ error: "Lead storage is not configured" }, 503);
  const recent = (await env.LEADS.get("index:recent", { type: "json" })) || [];
  return json({ ok: true, count: recent.length, leads: recent });
}

async function sendLeadEmails(env, { name, email, mapUrl }) {
  const from = env.CONTACT_FROM_EMAIL || "OneClick Fortune <onboarding@resend.dev>";
  const notifyTo = env.ADMIN_EMAIL || env.CONTACT_TO_EMAIL || "";

  const leadHtml = `
    <div style="font-family:Georgia,serif;line-height:1.5;color:#0f172a">
      <p>Hi ${escapeHtml(name)},</p>
      <p>Here is your free beginner setup map for OneClick Fortune.</p>
      <p><a href="${escapeHtml(mapUrl)}" style="display:inline-block;background:#0f172a;color:#fff;padding:12px 20px;border-radius:999px;text-decoration:none;font-family:sans-serif;font-weight:700">Open your setup map</a></p>
      <p style="font-size:14px;color:#475569">If the button does not work, copy this link:<br/>${escapeHtml(mapUrl)}</p>
      <p style="font-size:12px;color:#94a3b8">You asked for the free guide only — no sales call authorization.</p>
    </div>
  `;

  await resend(env.RESEND_API_KEY, {
    from,
    to: [email],
    subject: "Your OneClick Fortune setup map",
    html: leadHtml,
  });

  if (notifyTo) {
    await resend(env.RESEND_API_KEY, {
      from,
      to: [notifyTo],
      subject: `New squeeze lead: ${name} <${email}>`,
      html: `<p>New /squeeze lead</p><ul><li>Name: ${escapeHtml(name)}</li><li>Email: ${escapeHtml(email)}</li><li>Map: ${escapeHtml(mapUrl)}</li></ul>`,
    });
  }
}

async function resend(apiKey, payload) {
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Resend ${res.status}: ${text}`);
  }
  return res.json();
}

function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function cors() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
  };
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...cors(),
    },
  });
}
