/**
 * Soft consistency warning — never auto-closes.
 *
 * If today's floating profit would push
 *   (today's profit) / (running total closed profit + today's floating)
 * above CONSISTENCY_MAX_SHARE (50%), surface a warning so the operator
 * can consider a partial close.
 */

import { CONSISTENCY_MAX_SHARE, START_BALANCE } from "./constants.js";
import { money, utcDateStr } from "./equity.js";

/**
 * @param {object} opts
 * @param {Array<{date:string,pnl:number}>} opts.trades closed fills
 * @param {number} opts.todayFloatingPnl live open P&L (can be negative)
 * @param {Date|string} [opts.now]
 * @param {number} [opts.startBalance]
 * @returns {{ warn: boolean, message: string|null, share: number, todayProfit: number, totalProfit: number }}
 */
export function consistencyFloatingWarning({
  trades = [],
  todayFloatingPnl = 0,
  now = new Date(),
  startBalance = START_BALANCE,
} = {}) {
  const today = typeof now === "string" ? now : utcDateStr(now);
  const floatPnl = Number(todayFloatingPnl) || 0;

  let closedTotal = 0;
  let todayClosed = 0;
  for (const t of trades) {
    const pnl = Number(t.pnl) || 0;
    closedTotal += pnl;
    if (t.date === today) todayClosed += pnl;
  }

  const todayProfit = money(todayClosed + floatPnl);
  // Running total profit for the share check includes today's float
  // (what equity would show if you closed now).
  const totalProfit = money(closedTotal + floatPnl);

  // Only warn when both sides are positive profit — a losing float
  // cannot create a consistency concentration problem.
  if (todayProfit <= 0 || totalProfit <= 0) {
    return {
      warn: false,
      message: null,
      share: 0,
      todayProfit,
      totalProfit,
      todayClosed: money(todayClosed),
      threshold: CONSISTENCY_MAX_SHARE,
    };
  }

  const share = todayProfit / totalProfit;
  if (share > CONSISTENCY_MAX_SHARE + 1e-12) {
    return {
      warn: true,
      message:
        "Closing now may violate the 50% consistency rule — consider partial close.",
      share,
      todayProfit,
      totalProfit,
      todayClosed: money(todayClosed),
      threshold: CONSISTENCY_MAX_SHARE,
    };
  }

  return {
    warn: false,
    message: null,
    share,
    todayProfit,
    totalProfit,
    todayClosed: money(todayClosed),
    threshold: CONSISTENCY_MAX_SHARE,
  };
}
