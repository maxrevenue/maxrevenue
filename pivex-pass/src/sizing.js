/**
 * Pass-mode position sizing — calcTicket.
 *
 * Dollar risk = equity * riskPct * (1 - BUFFER_PCT)
 * Lots derived from stopPips × pair pip value, capped at MAX_LOTS.
 * Rejects sub-MIN_STOP_PIPS stops and any ticket whose full SL loss
 * would itself breach remaining daily DD room.
 */

import {
  DEFAULT_RISK_PCT,
  DEFAULT_RR,
  BUFFER_PCT,
  MAX_LOTS,
  MIN_STOP_PIPS,
} from "./constants.js";
import { pairInfo, pipValueUsd, formatPrice } from "../public/fx.js";
import { money } from "./equity.js";

/**
 * @param {object} opts
 * @param {number} opts.equity current equity (closed + floating)
 * @param {number} opts.entry planned entry price
 * @param {number} opts.stopPips stop distance in pips
 * @param {number} [opts.riskPct=DEFAULT_RISK_PCT]
 * @param {number} [opts.rr=DEFAULT_RR]
 * @param {string} opts.pair e.g. "EURUSD"
 * @param {"BUY"|"SELL"|"Long"|"Short"} opts.direction
 * @param {number} opts.dailyDDRoomDollars remaining $ before 4% daily breach
 * @param {number} [opts.mid] mid for pip-value conversion (defaults to entry)
 * @returns {{ ok: true, ticket: object } | { ok: false, ticket: null, reason: string }}
 */
export function calcTicket({
  equity,
  entry,
  stopPips,
  riskPct = DEFAULT_RISK_PCT,
  rr = DEFAULT_RR,
  pair,
  direction,
  dailyDDRoomDollars,
  mid,
} = {}) {
  const p = pairInfo(pair);
  if (!p) {
    return { ok: false, ticket: null, reason: "Unsupported pair: " + pair };
  }

  equity = Number(equity);
  entry = Number(entry);
  stopPips = Number(stopPips);
  riskPct = Number(riskPct);
  rr = Number(rr);
  mid = Number(mid) || entry;
  const room = Number(dailyDDRoomDollars);

  if (!isFinite(equity) || equity <= 0) {
    return { ok: false, ticket: null, reason: "Invalid equity" };
  }
  if (!isFinite(entry) || entry <= 0) {
    return { ok: false, ticket: null, reason: "Invalid entry" };
  }
  if (!isFinite(stopPips)) {
    return { ok: false, ticket: null, reason: "Invalid stopPips" };
  }
  if (stopPips < MIN_STOP_PIPS) {
    return {
      ok: false,
      ticket: null,
      reason:
        "Stop is only " +
        (Math.round(stopPips * 10) / 10) +
        " pips — pass mode requires ≥ " +
        MIN_STOP_PIPS +
        " pips",
    };
  }
  if (!isFinite(riskPct) || riskPct <= 0) {
    return { ok: false, ticket: null, reason: "Invalid riskPct" };
  }
  if (!isFinite(rr) || rr <= 0) {
    return { ok: false, ticket: null, reason: "Invalid rr" };
  }
  if (!isFinite(room) || room < 0) {
    return {
      ok: false,
      ticket: null,
      reason: "dailyDDRoomDollars is required (remaining $ before 4% daily breach)",
    };
  }

  const dirRaw = String(direction || "").toUpperCase();
  let action;
  if (dirRaw === "BUY" || dirRaw === "LONG") action = "BUY";
  else if (dirRaw === "SELL" || dirRaw === "SHORT") action = "SELL";
  else {
    return { ok: false, ticket: null, reason: "direction must be BUY or SELL" };
  }

  // Theoretical dollar risk after buffer headroom.
  const dollarRiskBudget = money(equity * riskPct * (1 - BUFFER_PCT));
  if (dollarRiskBudget < 0.01) {
    return { ok: false, ticket: null, reason: "Risk budget is zero after buffer" };
  }

  const pv1 = pipValueUsd(p.instrument, mid, 1);
  if (!(pv1 > 0)) {
    return { ok: false, ticket: null, reason: "Could not price pip value for " + p.instrument };
  }

  // lots = risk$ / (stopPips * $/pip/lot), floor to 0.01, cap MAX_LOTS
  const rawLots = dollarRiskBudget / (stopPips * pv1);
  let lots = Math.floor(rawLots * 100) / 100;
  let cappedByMaxLots = false;
  if (lots > MAX_LOTS) {
    lots = Math.floor(MAX_LOTS * 100) / 100;
    cappedByMaxLots = true;
  }
  if (lots < 0.01) {
    return {
      ok: false,
      ticket: null,
      reason:
        "Risk budget too small for 0.01 lot at " +
        (Math.round(stopPips * 10) / 10) +
        " pips",
    };
  }

  const dollarRisk = money(lots * stopPips * pv1);
  const dollarReward = money(dollarRisk * rr);

  // Critical guardrail: a full stop must not itself breach remaining daily DD.
  if (dollarRisk > room + 1e-9) {
    return {
      ok: false,
      ticket: null,
      reason:
        "Full stop would risk $" +
        dollarRisk.toFixed(2) +
        " but only $" +
        room.toFixed(2) +
        " remains before the 4% daily DD breach — sit out",
      dollarRisk,
      dailyDDRoomDollars: room,
    };
  }

  const stopDist = stopPips * p.pip;
  const tpDist = stopDist * rr;
  let sl;
  let tp;
  if (action === "BUY") {
    sl = entry - stopDist;
    tp = entry + tpDist;
  } else {
    sl = entry + stopDist;
    tp = entry - tpDist;
  }

  const ticket = {
    pair: p.instrument,
    direction: action,
    lots,
    lotsLabel: lots.toFixed(2),
    entry,
    sl,
    tp,
    entryLabel: formatPrice(p.instrument, entry),
    slLabel: formatPrice(p.instrument, sl),
    tpLabel: formatPrice(p.instrument, tp),
    stopPips: Math.round(stopPips * 10) / 10,
    dollarRisk,
    dollarReward,
    riskPct,
    rr,
    bufferedRiskBudget: dollarRiskBudget,
    cappedByMaxLots,
    maxLots: MAX_LOTS,
    pipValue: Math.round(pv1 * 100) / 100,
  };

  return { ok: true, ticket, reason: null };
}
