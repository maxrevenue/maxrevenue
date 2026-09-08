/**
 * Cloudflare Worker — serves public assets + /api/picks + /api/health.
 * Deploy: cd pivex-pass && npx wrangler deploy
 */

import { scanPicks, health } from "./scanner.js";
import { DEFAULTS } from "../public/rules.js";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: cors(),
      });
    }

    if (url.pathname === "/api/health") {
      const body = await health();
      return json(body);
    }

    if (url.pathname === "/api/picks" && request.method === "POST") {
      try {
        const body = await request.json();
        const settings = { ...DEFAULTS, ...(body.settings || {}) };
        const result = await scanPicks({
          settings,
          trades: body.trades || [],
          floatingPnl: body.floatingPnl || 0,
          exclude: body.exclude || [],
          refresh: body.refresh !== false,
        });
        return json(result);
      } catch (e) {
        return json({ error: String(e.message || e) }, 500);
      }
    }

    // Static assets via Workers Assets (wrangler [assets]) or ASSETS binding
    if (env.ASSETS) {
      return env.ASSETS.fetch(request);
    }

    return new Response("Not found", { status: 404 });
  },
};

function cors() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
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
