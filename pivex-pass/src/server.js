/**
 * Local Node server — same routes as the Cloudflare Worker.
 * npm start → http://127.0.0.1:8787
 *
 * Also runs a lightweight UTC day-boundary check so SOD equity
 * is snapshotted as soon as the process observes a new UTC date.
 */

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { scanPicks, health, fetchMid } from "./scanner.js";
import { checkForTrade } from "./checkTrade.js";
import {
  ensureUtcDaySnapshot,
  getStartOfDaySnapshot,
  utcDateStr,
  markOpenPositions,
} from "./equity.js";
import { lockDay, assertTradeUnlocked, overrideFromArgv } from "./lock.js";
import { consistencyFloatingWarning } from "./consistency.js";
import { DEFAULTS } from "../public/rules.js";
import { START_BALANCE } from "./constants.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");
const PORT = Number(process.env.PORT) || 8787;

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".webmanifest": "application/manifest+json",
};

function send(res, status, body, type = "application/json; charset=utf-8") {
  const buf = typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(status, {
    "Content-Type": type,
    "Cache-Control": status === 200 && type.includes("json") ? "no-store" : "public, max-age=60",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
  });
  res.end(buf);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch (e) {
        reject(e);
      }
    });
    req.on("error", reject);
  });
}

function serveStatic(req, res, urlPath) {
  let rel = urlPath === "/" ? "/index.html" : urlPath;
  rel = decodeURIComponent(rel.split("?")[0]);
  if (rel.includes("..")) return send(res, 400, { error: "bad path" });
  const file = path.join(publicDir, rel);
  if (!file.startsWith(publicDir)) return send(res, 400, { error: "bad path" });
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    return send(res, 404, { error: "not found" });
  }
  const ext = path.extname(file);
  send(res, 200, fs.readFileSync(file), MIME[ext] || "application/octet-stream");
}

/** Last UTC date we snapshotted via the scheduled checker. */
let lastBoundaryDate = null;

/**
 * Task 1 scheduled check: at/after 00:00 UTC, snapshot SOD equity once per day.
 * Polls every 30s so we land close to the boundary without a heavy cron dep.
 */
function runUtcBoundaryCheck(equityAtBoundary = null) {
  const now = new Date();
  const today = utcDateStr(now);
  if (lastBoundaryDate === today && getStartOfDaySnapshot(today)) return getStartOfDaySnapshot(today);
  const snap = ensureUtcDaySnapshot({
    now,
    trades: [],
    equityAtBoundary: equityAtBoundary ?? START_BALANCE,
    startBalance: START_BALANCE,
  });
  lastBoundaryDate = today;
  return snap;
}

setInterval(() => {
  try {
    runUtcBoundaryCheck();
  } catch (e) {
    console.error("UTC boundary check failed", e);
  }
}, 30_000);
runUtcBoundaryCheck();

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  try {
    if (req.method === "OPTIONS") return send(res, 204, "");

    if (url.pathname === "/api/health" && req.method === "GET") {
      return send(res, 200, await health());
    }

    if (url.pathname === "/api/sod" && req.method === "GET") {
      const today = utcDateStr();
      return send(res, 200, {
        utcDate: today,
        snapshot: getStartOfDaySnapshot(today) || runUtcBoundaryCheck(),
      });
    }

    if (url.pathname === "/api/picks" && req.method === "POST") {
      const body = await readBody(req);
      const settings = { ...DEFAULTS, ...(body.settings || {}) };
      // Refresh SOD from this account's ledger when the client posts.
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
      return send(res, 200, result);
    }

    if (url.pathname === "/api/check-trade" && req.method === "POST") {
      const body = await readBody(req);
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
      return send(res, 200, result);
    }

    if (url.pathname === "/api/consistency-warning" && req.method === "POST") {
      const body = await readBody(req);
      const warn = consistencyFloatingWarning({
        trades: body.trades || [],
        todayFloatingPnl: body.floatingPnl || 0,
        startBalance: body.settings?.balance ?? START_BALANCE,
      });
      return send(res, 200, warn);
    }

    if (url.pathname === "/api/lock-fill" && req.method === "POST") {
      const body = await readBody(req);
      const date = body.utcDate || utcDateStr();
      lockDay(date);
      return send(res, 200, { locked: true, utcDate: date });
    }

    if (url.pathname === "/api/mark-open" && req.method === "POST") {
      const body = await readBody(req);
      const tickets = body.openTickets || [];
      const result = await markOpenPositions(tickets, async (pair) => fetchMid(pair));
      return send(res, 200, result);
    }

    if (req.method === "GET") return serveStatic(req, res, url.pathname);
    return send(res, 404, { error: "not found" });
  } catch (e) {
    console.error(e);
    return send(res, 500, { error: String(e.message || e) });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Pivex Pass listening on http://127.0.0.1:${PORT}`);
  if (overrideFromArgv()) {
    console.log("Note: --override is present on process argv (CLI only; API still requires body.override=true).");
  }
  // silence unused in non-CLI path
  void assertTradeUnlocked;
});
