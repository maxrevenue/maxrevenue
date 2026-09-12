/**
 * Cloudflare Worker — public assets + /api/picks + /api/health + /api/check-trade.
 * Deploy: cd pivex-pass && npx wrangler deploy
 */

import { scanPicks, health, fetchMid } from "./scanner.js";
import { checkForTrade } from "./checkTrade.js";
import { ensureUtcDaySnapshot } from "./equity.js";
import { consistencyFloatingWarning } from "./consistency.js";
import { DEFAULTS } from "../public/rules.js";
import { START_BALANCE } from "./constants.js";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: cors() });
    }

    if (url.pathname === "/api/health") {
      return json(await health());
    }

    if (url.pathname === "/api/picks" && request.method === "POST") {
      try {
        const body = await request.json();
        const settings = { ...DEFAULTS, ...(body.settings || {}) };
        ensureUtcDaySnapshot({
          now: new Date(),
          trades: body.trades || [],
          startBalance: settings.balance,
        });
        const result = await scanPicks({
          settings,
          trades: body.trades || [],
          floatingPnl: body.floatingPnl || 0,
          exclude: body.exclude || [],
          refresh: body.refresh !== false,
          override: body.override === true,
        });
        return json(result);
      } catch (e) {
        return json({ error: String(e.message || e) }, 500);
      }
    }

    if (url.pathname === "/api/check-trade" && request.method === "POST") {
      try {
        const body = await request.json();
        const settings = { ...DEFAULTS, ...(body.settings || {}) };
        ensureUtcDaySnapshot({
          now: new Date(),
          trades: body.trades || [],
          startBalance: settings.balance,
        });
        let prices = body.prices || null;
        if (body.openTickets?.length && !prices) {
          prices = async (pair) => fetchMid(pair);
        }
        const result = await checkForTrade({
          trades: body.trades || [],
          openTickets: body.openTickets || [],
          floatingPnl: body.floatingPnl || 0,
          prices,
          override: body.override === true,
          riskPct: settings.risk != null ? settings.risk / 100 : undefined,
          rr: settings.rewardR,
          exclude: body.exclude || [],
          refresh: body.refresh !== false,
          startBalance: settings.balance,
        });
        return json(result);
      } catch (e) {
        return json({ error: String(e.message || e) }, 500);
      }
    }

    if (url.pathname === "/api/consistency-warning" && request.method === "POST") {
      try {
        const body = await request.json();
        return json(
          consistencyFloatingWarning({
            trades: body.trades || [],
            todayFloatingPnl: body.floatingPnl || 0,
            startBalance: body.settings?.balance ?? START_BALANCE,
          })
        );
      } catch (e) {
        return json({ error: String(e.message || e) }, 500);
      }
    }

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
