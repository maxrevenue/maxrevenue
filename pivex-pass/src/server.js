/**
 * Local Node server — same routes as the Cloudflare Worker.
 * npm start → http://127.0.0.1:8787
 */

import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { scanPicks, health } from "./scanner.js";
import { DEFAULTS } from "../public/rules.js";

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

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host}`);
  try {
    if (req.method === "OPTIONS") return send(res, 204, "");

    if (url.pathname === "/api/health" && req.method === "GET") {
      return send(res, 200, await health());
    }

    if (url.pathname === "/api/picks" && req.method === "POST") {
      const body = await readBody(req);
      const settings = { ...DEFAULTS, ...(body.settings || {}) };
      const result = await scanPicks({
        settings,
        trades: body.trades || [],
        floatingPnl: body.floatingPnl || 0,
        exclude: body.exclude || [],
        refresh: body.refresh !== false,
      });
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
});
