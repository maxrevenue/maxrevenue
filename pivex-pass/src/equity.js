/**
 * Equity / UTC day-boundary tracking for the Pivex challenge.
 *
 * Daily DD is measured against equity snapshotted at 00:00 UTC
 * (floating P&L included). Overall floor is static OVERALL_DD_FLOOR.
 */

import {
  START_BALANCE,
  DAILY_DD_PCT,
  OVERALL_DD_FLOOR,
} from "./constants.js";
import { pairInfo, pipValueUsd, priceDistanceToPips } from "../public/fx.js";

/** @type {Map<string, { utcDate: string, startOfDayEquity: number, snappedAt: string }>} */
const sodSnapshots = new Map();

export function money(n) {
  return Math.round((Number(n) || 0) * 100) / 100;
}

/** YYYY-MM-DD in UTC. Day boundary is exactly 00:00:00.000Z. */
export function utcDateStr(d = new Date()) {
  return new Date(d).toISOString().slice(0, 10);
}

/**
 * Reconstruct start-of-day equity from the closed ledger when no
 * live 00:00 UTC snapshot exists yet.
 *
 * SOD = START_BALANCE + sum(pnl of fills with date < utcDate).
 * Today's fills and live float are NOT part of SOD — they move
 * current equity after the boundary.
 */
export function reconstructStartOfDayEquity(trades = [], utcDate, startBalance = START_BALANCE) {
  let prior = 0;
  for (const t of trades) {
    if (t.date && t.date < utcDate) prior += Number(t.pnl) || 0;
  }
  return money(startBalance + prior);
}

/**
 * Persist a start-of-UTC-day equity snapshot.
 * Call at (or as soon as possible after) 00:00 UTC.
 */
export function snapshotStartOfDayEquity(utcDate, startOfDayEquity, now = new Date()) {
  const record = {
    utcDate: String(utcDate),
    startOfDayEquity: money(startOfDayEquity),
    snappedAt: new Date(now).toISOString(),
  };
  sodSnapshots.set(record.utcDate, record);
  return { ...record };
}

export function getStartOfDaySnapshot(utcDate) {
  const hit = sodSnapshots.get(String(utcDate));
  return hit ? { ...hit } : null;
}

export function clearStartOfDaySnapshots() {
  sodSnapshots.clear();
}

/**
 * Scheduled / cron entry: ensure today's UTC date has a SOD snapshot.
 *
 * - If a snapshot already exists for utcDate → return it (idempotent).
 * - Else store reconstructed SOD from closed fills before today.
 *   (Preferred when the process was not awake at exactly 00:00 UTC;
 *   overnight float that existed at midnight should be passed via
 *   `equityAtBoundary` when known.)
 *
 * @param {object} opts
 * @param {Date} [opts.now]
 * @param {Array} [opts.trades] closed ledger
 * @param {number} [opts.equityAtBoundary] true equity at/near 00:00 UTC
 *   (closed + overnight float). When provided, used instead of reconstruct.
 * @param {number} [opts.startBalance]
 */
export function ensureUtcDaySnapshot({
  now = new Date(),
  trades = [],
  equityAtBoundary = null,
  startBalance = START_BALANCE,
} = {}) {
  const utcDate = utcDateStr(now);
  const existing = getStartOfDaySnapshot(utcDate);
  if (existing) return existing;

  const sod =
    equityAtBoundary != null && isFinite(Number(equityAtBoundary))
      ? money(equityAtBoundary)
      : reconstructStartOfDayEquity(trades, utcDate, startBalance);

  return snapshotStartOfDayEquity(utcDate, sod, now);
}

/**
 * Dollars and % of SOD remaining before the 4% daily DD breach.
 * currentEquity MUST include floating (open) P&L.
 *
 * Floor = startOfDayEquity * (1 - DAILY_DD_PCT)
 * Room  = max(0, currentEquity - floor)
 */
export function getDailyDDRoom(currentEquity, startOfDayEquity) {
  const sod = money(startOfDayEquity);
  const equity = money(currentEquity);
  const maxLossDollars = money(sod * DAILY_DD_PCT);
  const dailyFloor = money(sod - maxLossDollars); // ≡ sod * (1 - DAILY_DD_PCT)
  const usedDollars = money(Math.max(0, sod - equity));
  const roomDollars = money(Math.max(0, equity - dailyFloor));
  const roomPctOfSod = sod > 0 ? roomDollars / sod : 0;
  return {
    startOfDayEquity: sod,
    currentEquity: equity,
    dailyFloor,
    maxLossDollars,
    usedDollars,
    roomDollars,
    /** Remaining room as a fraction of SOD (e.g. 0.04 = full 4% still available). */
    roomPct: roomPctOfSod,
    breached: equity <= dailyFloor,
  };
}

/**
 * Dollars remaining before the static $94,000 overall floor.
 * currentEquity MUST include floating P&L.
 */
export function getOverallDDRoom(currentEquity) {
  const equity = money(currentEquity);
  const roomDollars = money(Math.max(0, equity - OVERALL_DD_FLOOR));
  return {
    currentEquity: equity,
    floor: OVERALL_DD_FLOOR,
    roomDollars,
    breached: equity <= OVERALL_DD_FLOOR,
  };
}

/**
 * Mark an open ticket to market using current mid.
 * Long:  (mid - entry) / pip * pipValue * lots
 * Short: (entry - mid) / pip * pipValue * lots
 */
export function floatingPnlForTicket(ticket, mid) {
  const pair = ticket.pair || ticket.instrument;
  const p = pairInfo(pair);
  if (!p || !isFinite(mid) || mid <= 0) {
    return { ok: false, floatingPnl: 0, reason: "Missing pair or mid" };
  }
  const entry = Number(ticket.entry);
  const lots = Number(ticket.lots);
  if (!isFinite(entry) || !isFinite(lots) || lots <= 0) {
    return { ok: false, floatingPnl: 0, reason: "Invalid entry/lots" };
  }
  const dir = String(ticket.direction || ticket.action || "").toUpperCase();
  const isLong = dir === "BUY" || dir === "LONG";
  const isShort = dir === "SELL" || dir === "SHORT";
  if (!isLong && !isShort) {
    return { ok: false, floatingPnl: 0, reason: "Need BUY/SELL direction" };
  }
  const signedDist = isLong ? mid - entry : entry - mid;
  const pips = signedDist / p.pip; // signed
  const pv = pipValueUsd(pair, mid, 1);
  const floatingPnl = money(pips * pv * lots);
  return {
    ok: true,
    floatingPnl,
    pips: Math.round(pips * 10) / 10,
    mid,
    pair: p.instrument,
    lots,
    entry,
  };
}

/**
 * Sum mark-to-market P&L for all open tickets.
 * priceByPair: { EURUSD: 1.0850, ... } or async getter via priceFn(pair).
 *
 * @param {Array} openTickets
 * @param {Record<string, number> | ((pair: string) => number|Promise<number>)} prices
 */
export async function markOpenPositions(openTickets = [], prices = {}) {
  const marks = [];
  let total = 0;
  for (const ticket of openTickets) {
    const pair = String(ticket.pair || ticket.instrument || "").toUpperCase();
    let mid;
    if (typeof prices === "function") {
      mid = await prices(pair);
    } else {
      mid = prices[pair];
    }
    const m = floatingPnlForTicket({ ...ticket, pair }, Number(mid));
    marks.push(m);
    if (m.ok) total += m.floatingPnl;
  }
  return { floatingPnl: money(total), marks };
}

/**
 * Convenience: closed equity + floating = current challenge equity.
 */
export function currentEquity(closedEquity, floatingPnl = 0) {
  return money((Number(closedEquity) || 0) + (Number(floatingPnl) || 0));
}

export function closedEquityFromTrades(trades = [], startBalance = START_BALANCE) {
  let pnl = 0;
  for (const t of trades) pnl += Number(t.pnl) || 0;
  return money(startBalance + pnl);
}

/** Exported for tests that need pip helpers without pulling fx directly. */
export { priceDistanceToPips };
