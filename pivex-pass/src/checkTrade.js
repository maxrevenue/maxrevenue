/**
 * End-to-end "check for a trade" flow.
 *
 * 1. Ensure UTC SOD equity snapshot
 * 2. Mark open tickets to market (floating)
 * 3. Daily lock gate (override only if explicit)
 * 4. Scan majors for trend+pullback
 * 5. Size via calcTicket against remaining daily DD room
 * 6. Attach consistency floating warning (never auto-close)
 *
 * No live Pivex execution — output is a ticket the user types in manually.
 */

import {
  START_BALANCE,
  TARGET_BALANCE,
  OVERALL_DD_FLOOR,
  DEFAULT_RISK_PCT,
  DEFAULT_RR,
  BUFFER_PCT,
  MAX_LOTS,
  MIN_STOP_PIPS,
} from "./constants.js";
import {
  ensureUtcDaySnapshot,
  getDailyDDRoom,
  getOverallDDRoom,
  currentEquity,
  closedEquityFromTrades,
  markOpenPositions,
  money,
  utcDateStr,
} from "./equity.js";
import { calcTicket } from "./sizing.js";
import { assertTradeUnlocked } from "./lock.js";
import { consistencyFloatingWarning } from "./consistency.js";
import { scanSetups } from "./scanner.js";
import { priceDistanceToPips } from "../public/fx.js";

/**
 * @param {object} opts
 * @param {Array} [opts.trades]
 * @param {Array} [opts.openTickets] open positions to MTM
 * @param {number} [opts.floatingPnl] explicit float if no openTickets
 * @param {Record<string,number>|Function} [opts.prices] mids for MTM / scanner
 * @param {boolean} [opts.override] explicit lock override
 * @param {Date} [opts.now]
 * @param {number} [opts.riskPct]
 * @param {number} [opts.rr]
 * @param {string[]} [opts.exclude]
 * @param {boolean} [opts.refresh]
 */
export async function checkForTrade({
  trades = [],
  openTickets = [],
  floatingPnl = 0,
  prices = null,
  override = false,
  now = new Date(),
  riskPct = DEFAULT_RISK_PCT,
  rr = DEFAULT_RR,
  exclude = [],
  refresh = true,
  startBalance = START_BALANCE,
} = {}) {
  const utcDate = utcDateStr(now);

  // --- Task 1: SOD snapshot + rooms (floating included) ---
  const closed = closedEquityFromTrades(trades, startBalance);
  let floatPnl = Number(floatingPnl) || 0;
  let marks = null;
  if (openTickets.length) {
    const priced =
      prices ||
      (async (pair) => {
        // Scanner exposes yahoo fetch via scan path; for MTM without
        // prices, leave float at the explicit floatingPnl.
        return NaN;
      });
    if (prices) {
      marks = await markOpenPositions(openTickets, prices);
      floatPnl = marks.floatingPnl;
    }
  }

  const sod = ensureUtcDaySnapshot({ now, trades, startBalance });
  const equity = currentEquity(closed, floatPnl);
  const daily = getDailyDDRoom(equity, sod.startOfDayEquity);
  const overall = getOverallDDRoom(equity);

  const consistency = consistencyFloatingWarning({
    trades,
    todayFloatingPnl: floatPnl,
    now,
    startBalance,
  });

  const rooms = { daily, overall, equity, closed, floatingPnl: floatPnl, sod };

  if (overall.breached || daily.breached) {
    return {
      action: "wait",
      message: overall.breached
        ? "Overall DD floor breached ($" + OVERALL_DD_FLOOR.toLocaleString() + ")"
        : "Daily 4% DD breached vs SOD equity",
      rooms,
      consistency,
      marks,
    };
  }

  // --- Task 4: daily lock ---
  const lock = assertTradeUnlocked(utcDate, { override, trades });
  if (lock.action === "locked") {
    return {
      action: "locked",
      message: lock.message,
      utcDate,
      rooms,
      consistency,
      marks,
    };
  }

  // --- Task 3: scanner ---
  const scan = await scanSetups({ exclude, refresh, now });
  if (!scan.setup) {
    return {
      action: "wait",
      message: scan.message || "No qualifying trend+pullback setup",
      skipped: scan.skipped,
      sources: scan.sources,
      rooms,
      consistency,
      marks,
    };
  }

  const setup = scan.setup;
  const stopPips = priceDistanceToPips(setup.instrument, Math.abs(setup.entry - setup.stop));

  // --- Task 2: size against remaining daily DD room ---
  const sized = calcTicket({
    equity,
    entry: setup.entry,
    stopPips,
    riskPct,
    rr,
    pair: setup.instrument,
    direction: setup.action,
    dailyDDRoomDollars: daily.roomDollars,
    mid: setup.entry,
  });

  if (!sized.ok) {
    return {
      action: "wait",
      message: sized.reason,
      setup,
      skipped: scan.skipped,
      sources: scan.sources,
      rooms,
      consistency,
      marks,
    };
  }

  const ticket = sized.ticket;
  return {
    action: "trade",
    message: setup.why,
    ticket,
    setup,
    skipped: scan.skipped,
    sources: scan.sources,
    rooms,
    consistency,
    marks,
    copyText: [
      "PIVEX PASS TICKET",
      `${ticket.direction} ${ticket.lotsLabel} ${ticket.pair}`,
      `SL ${ticket.slLabel} · TP ${ticket.tpLabel}`,
      `Risk ~$${Math.round(ticket.dollarRisk)} · Reward ~$${Math.round(ticket.dollarReward)} (R=${rr})`,
      `OVERWRITE volume — do NOT leave 10.xx from a prior order.`,
      `One ticket today. Do not move SL wider. Log fill after close.`,
    ].join("\n"),
  };
}

export {
  START_BALANCE,
  TARGET_BALANCE,
  OVERALL_DD_FLOOR,
  DEFAULT_RISK_PCT,
  DEFAULT_RR,
  BUFFER_PCT,
  MAX_LOTS,
  MIN_STOP_PIPS,
  money,
};
