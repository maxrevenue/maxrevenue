/**
 * One UTC day → one logged fill → locked for new tickets.
 *
 * Override is NEVER the default path: callers must pass
 * `{ override: true }` (CLI `--override`) or an explicit UI confirm.
 */

import { utcDateStr } from "./equity.js";

/** @type {Record<string, true>} */
const locked = Object.create(null);

/**
 * A logged fill for UTC-date D sets locked[D] = true.
 */
export function lockDay(utcDate) {
  const d = String(utcDate);
  locked[d] = true;
  return d;
}

/**
 * Derive locks from the closed ledger (any fill on D locks D).
 * Merges into the in-memory map; does not clear other dates.
 */
export function syncLocksFromTrades(trades = []) {
  for (const t of trades) {
    if (t.date) locked[String(t.date)] = true;
  }
  return { ...locked };
}

export function isDayLocked(utcDate) {
  return locked[String(utcDate)] === true;
}

export function getLockMap() {
  return { ...locked };
}

export function clearLocks() {
  for (const k of Object.keys(locked)) delete locked[k];
}

/**
 * Explicit unlock — only for tests or a confirmed override repair path.
 */
export function unlockDay(utcDate) {
  delete locked[String(utcDate)];
}

/**
 * Gate for "check for a trade".
 *
 * @param {string|Date} [utcDateOrNow]
 * @param {{ override?: boolean, trades?: Array }} [opts]
 * @returns {{ action: "ok" } | { action: "locked", message: string }}
 */
export function assertTradeUnlocked(utcDateOrNow = new Date(), opts = {}) {
  const override = opts.override === true; // must be explicit boolean true
  const date =
    typeof utcDateOrNow === "string" ? utcDateOrNow : utcDateStr(utcDateOrNow);

  // When the client sends a trades ledger, that ledger is the source of
  // truth for whether *this* UTC day is locked — avoids stale in-memory
  // locks from a prior request poisoning a clean session.
  if (Array.isArray(opts.trades)) {
    const hasFillToday = opts.trades.some((t) => t.date === date);
    if (hasFillToday) lockDay(date);
    else unlockDay(date);
    syncLocksFromTrades(opts.trades);
  }

  if (isDayLocked(date) && !override) {
    return {
      action: "locked",
      message:
        "UTC day " +
        date +
        " already has a logged fill — one ticket per day. " +
        "Bank it and wait for 00:00 UTC. Override requires an explicit confirmation.",
      utcDate: date,
    };
  }

  return { action: "ok", utcDate: date, override };
}

/**
 * Parse CLI argv for an explicit --override flag (never implied).
 */
export function overrideFromArgv(argv = process.argv) {
  return argv.includes("--override");
}
