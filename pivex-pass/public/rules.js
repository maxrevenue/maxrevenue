/**
 * Pivex $100K Challenge — rules + sprint engine.
 *
 * Single-phase evaluation:
 *   - 10% profit target on closed balance ($110,000)
 *   - unlimited firm time (sprint deadline is personal)
 *   - minimum 5 UTC trading days
 *   - 4% daily DD of start-of-day equity (00:00 UTC), floating
 *   - 6% overall STATIC DD — equity never below $94,000
 *   - 50% consistency — no UTC day > half of total closed profit
 *
 * Drawdowns print on floating equity. Hitting a floor fails even if
 * the position later recovers.
 */

export const DEFAULTS = {
  balance: 100000,
  dailyDD: 4,
  overallDD: 6,
  target: 10,
  risk: 0.75,
  buffer: 20,
  minTradingDays: 5,
  leverage: 30,
  maxTradesPerDay: 1,
  maxConsecutiveLosses: 3,
  lockMinutesBeforeReset: 90,
  rewardR: 1.5,
  sprintDays: 14,
  /**
   * "strict" — London/NY overlap only, news blackout, weekend/Friday lock, late-float lock
   * "anytime" — time filters off; still one ticket/day + floor-safe size + loss-streak cuts
   */
  sessionMode: "strict",
  /** Hard cap so leftover Pivex volume like 10.03 lots can never be a "valid" pass size. */
  maxLots: 2,
  /** Stops tighter than this are spread/noise — reject for pass mode. */
  minStopPips: 10,
};

export function money(n) {
  return Math.round((Number(n) || 0) * 100) / 100;
}

export function utcDateStr(d = new Date()) {
  return d.toISOString().slice(0, 10);
}

export function utcShiftDate(now = new Date(), days = 0) {
  return utcDateStr(
    new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + days))
  );
}

export function msUntilUtcMidnight(now = new Date()) {
  const next = Date.UTC(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate() + 1,
    0,
    0,
    0,
    0
  );
  return Math.max(0, next - now.getTime());
}

export function closedEquity(settings, trades) {
  let pnl = 0;
  for (const t of trades) pnl += Number(t.pnl) || 0;
  return money(settings.balance + pnl);
}

export function equityNow(settings, trades, floatingPnl = 0) {
  return money(closedEquity(settings, trades) + (Number(floatingPnl) || 0));
}

export function dailyStartEquity(settings, trades, now = new Date()) {
  const today = utcDateStr(now);
  let prior = 0;
  for (const t of trades) {
    if (t.date < today) prior += Number(t.pnl) || 0;
  }
  return money(settings.balance + prior);
}

export function todayClosedPnl(trades, now = new Date()) {
  const today = utcDateStr(now);
  let sum = 0;
  for (const t of trades) {
    if (t.date === today) sum += Number(t.pnl) || 0;
  }
  return money(sum);
}

export function tradingDaySet(trades) {
  const days = {};
  for (const t of trades) {
    if (t.date) days[t.date] = true;
  }
  return Object.keys(days).sort();
}

export function floors(settings, trades, now = new Date()) {
  const dStart = dailyStartEquity(settings, trades, now);
  const overallLoss = money(settings.balance * settings.overallDD / 100);
  const dailyLoss = money(dStart * settings.dailyDD / 100);
  const targetGain = money(settings.balance * settings.target / 100);
  return {
    dailyStart: dStart,
    overall: money(settings.balance - overallLoss),
    daily: money(dStart - dailyLoss),
    target: money(settings.balance + targetGain),
  };
}

/**
 * 50% consistency: at review, no single UTC day may be more than half
 * of total closed profit. Breach is not an instant fail — keep trading
 * until other days dilute the biggest day under 50%.
 */
export function consistency(settings, trades) {
  const map = {};
  for (const t of trades) {
    if (!t.date) continue;
    map[t.date] = (map[t.date] || 0) + (Number(t.pnl) || 0);
  }
  const dates = Object.keys(map);
  let bestDay = 0;
  let bestDate = null;
  for (const d of dates) {
    if (map[d] > bestDay) {
      bestDay = map[d];
      bestDate = d;
    }
  }
  bestDay = money(bestDay);
  const closed = closedEquity(settings, trades);
  const totalProfit = money(Math.max(0, closed - settings.balance));
  const share = totalProfit > 0 ? bestDay / totalProfit : 0;
  const ok = totalProfit <= 0 || bestDay <= money(totalProfit * 0.5) + 0.0001;

  // Max additional profit allowed today without pushing today's day
  // over 50% of (current profit + that add), ignoring other days.
  const today = utcDateStr();
  const todayPnl = money(map[today] || 0);
  // Solve: max(bestOther, todayPnl + x) <= 0.5 * (totalProfit + x)
  // Cases depend on whether today becomes the best day.
  let maxAddToday = Infinity;
  const bestOther = Math.max(
    0,
    ...dates.filter((d) => d !== today).map((d) => map[d] || 0)
  );
  // If today stays <= bestOther: bestOther <= 0.5*(P+x) => x >= 2*bestOther - P
  // That's a floor on total, not a cap on today. Cap when today is best:
  // todayPnl + x <= 0.5 * (P + x) => 2*todayPnl + 2x <= P + x => x <= P - 2*todayPnl
  if (totalProfit <= 0 && todayPnl <= 0) {
    maxAddToday = Infinity; // first profit day will always be 100% until diluted
  } else {
    maxAddToday = money(totalProfit - 2 * todayPnl);
    if (maxAddToday < 0) maxAddToday = 0;
    // Also ensure after add, bestOther still ok: bestOther <= 0.5*(P+x) => x >= 2*bestOther - P
    // That's required progress, not a max.
  }

  return {
    bestDay,
    bestDate,
    bestOther: money(bestOther),
    todayPnl,
    totalProfit,
    share,
    ok,
    maxAddToday,
    dayMap: map,
  };
}

export function evaluate(settings, trades, floatingPnl = 0, now = new Date()) {
  floatingPnl = Number(floatingPnl) || 0;
  const f = floors(settings, trades, now);
  const closed = closedEquity(settings, trades);
  const equity = money(closed + floatingPnl);
  const days = tradingDaySet(trades);
  const dayCount = days.length;

  const roomOverall = money(Math.max(0, equity - f.overall));
  const roomDaily = money(Math.max(0, equity - f.daily));
  const bindingRoom = money(Math.min(roomDaily, roomOverall));
  const toTarget = money(Math.max(0, f.target - closed));

  const cons = consistency(settings, trades);
  const breachedOverall = equity <= f.overall;
  const breachedDaily = equity <= f.daily;
  const targetHit = closed >= f.target;
  const daysMet = dayCount >= settings.minTradingDays;

  let status;
  let statusLabel;
  if (breachedOverall) {
    status = "FAILED_OVERALL";
    statusLabel = "Failed — overall drawdown";
  } else if (breachedDaily) {
    status = "FAILED_DAILY";
    statusLabel = "Failed — daily drawdown";
  } else if (targetHit && daysMet && cons.ok) {
    status = "PASSED";
    statusLabel = "Passed — target + " + settings.minTradingDays + " days";
  } else if (targetHit && daysMet && !cons.ok) {
    status = "TARGET_HIT_NEED_CONSISTENCY";
    statusLabel = "Target hit — dilute best day under 50% of total profit";
  } else if (targetHit && !daysMet) {
    status = "TARGET_HIT_NEED_DAYS";
    statusLabel =
      "Target hit — need " +
      (settings.minTradingDays - dayCount) +
      " more trading day" +
      (settings.minTradingDays - dayCount === 1 ? "" : "s");
  } else {
    status = "IN_PROGRESS";
    statusLabel = "In progress";
  }

  return {
    now,
    utcDate: utcDateStr(now),
    msUntilReset: msUntilUtcMidnight(now),
    closed,
    equity,
    floatingPnl,
    dailyStart: f.dailyStart,
    floorOverall: f.overall,
    floorDaily: f.daily,
    targetBalance: f.target,
    roomOverall,
    roomDaily,
    bindingRoom,
    toTarget,
    tradingDays: dayCount,
    tradingDayList: days,
    minTradingDays: settings.minTradingDays,
    daysRemaining: Math.max(0, settings.minTradingDays - dayCount),
    todayClosedPnl: todayClosedPnl(trades, now),
    breachedOverall,
    breachedDaily,
    targetHit,
    daysMet,
    failed: breachedOverall || breachedDaily,
    passed: status === "PASSED",
    consistencyOk: cons.ok,
    consistencyBestDay: cons.bestDay,
    consistencyBestDate: cons.bestDate,
    consistencyShare: cons.share,
    consistencyMaxAddToday: cons.maxAddToday,
    status,
    statusLabel,
  };
}

export function todayTrades(trades, now = new Date()) {
  const today = utcDateStr(now);
  return trades.filter((t) => t.date === today);
}

export function sortedTrades(trades) {
  return trades.slice().sort((a, b) => {
    if (a.date === b.date) return (Number(a.id) || 0) - (Number(b.id) || 0);
    return a.date < b.date ? -1 : 1;
  });
}

export function consecutiveLosses(trades) {
  const sorted = sortedTrades(trades);
  let n = 0;
  for (let i = sorted.length - 1; i >= 0; i--) {
    const pnl = Number(sorted[i].pnl) || 0;
    if (pnl < 0) n++;
    else if (pnl > 0) break;
  }
  return n;
}

export function lastTradeDate(trades) {
  const sorted = sortedTrades(trades);
  if (!sorted.length) return null;
  return sorted[sorted.length - 1].date || null;
}

export function inUsDataWindow(now = new Date()) {
  const mins = now.getUTCHours() * 60 + now.getUTCMinutes();
  return mins >= 12 * 60 + 20 && mins < 13 * 60 + 5;
}

export function isAnytimeMode(settings) {
  return String(settings?.sessionMode || "strict").toLowerCase() === "anytime";
}

/**
 * Strict mode: London/NY overlap only — nights, weekends, Friday late, and the
 * US data print are how challenge accounts die.
 * Anytime mode: session filters off (weekend still blocked — majors are closed).
 */
export function passWindow(now = new Date(), settings = DEFAULTS) {
  if (isAnytimeMode(settings)) {
    const day = now.getUTCDay();
    if (day === 0 || day === 6) {
      return {
        ok: false,
        reason: "Weekend — majors are closed. Wait for Monday (anytime mode still needs a live session).",
      };
    }
    return { ok: true, reason: null, mode: "anytime" };
  }

  const h = now.getUTCHours();
  const day = now.getUTCDay();
  if (day === 0 || day === 6) {
    return { ok: false, reason: "Weekend — pass mode does not trade. Wait for Monday 12:00 UTC." };
  }
  if (day === 5 && h >= 16) {
    return { ok: false, reason: "Friday after 16:00 UTC — do not hold a challenge trade into the weekend gap." };
  }
  if (inUsDataWindow(now)) {
    return {
      ok: false,
      reason: "US data window (12:20–13:05 UTC). Spreads blow out — sit this print out.",
    };
  }
  if (h < 12 || h >= 16) {
    return { ok: false, reason: "Strict mode only trades 12:00–16:00 UTC (London/NY overlap)." };
  }
  return { ok: true, reason: null, mode: "strict" };
}

export function sessionClock(now = new Date(), settings = DEFAULTS) {
  const mins = now.getUTCHours() * 60 + now.getUTCMinutes();
  const open = 12 * 60;
  const close = 16 * 60;
  const newsStart = 12 * 60 + 20;
  const newsEnd = 13 * 60 + 5;
  const day = now.getUTCDay();
  const isWeekend = day === 0 || day === 6;
  const anytime = isAnytimeMode(settings);
  let phase = "closed";
  if (!isWeekend) {
    if (!anytime && mins >= newsStart && mins < newsEnd) phase = "news";
    else if (anytime) phase = "open";
    else if (mins >= open && mins < close) phase = "open";
    else if (mins < open) phase = "pre";
    else phase = "after";
  } else {
    phase = "weekend";
  }
  const window = passWindow(now, settings);
  return {
    phase,
    window,
    mins,
    open,
    close,
    newsStart,
    newsEnd,
    anytime,
    progress: anytime
      ? isWeekend
        ? 0
        : 1
      : phase === "open" || phase === "news"
        ? (mins - open) / (close - open)
        : phase === "pre"
          ? 0
          : 1,
  };
}

export function passGuard(settings, trades, floatingPnl = 0, now = new Date()) {
  const snap = evaluate(settings, trades, floatingPnl, now);
  const today = todayTrades(trades, now);
  const consec = consecutiveLosses(trades);
  const reasons = [];
  const maxPerDay = settings.maxTradesPerDay || 1;
  const maxConsec = settings.maxConsecutiveLosses || 3;
  const lockMins = settings.lockMinutesBeforeReset || 90;
  const anytime = isAnytimeMode(settings);
  const window = passWindow(now, settings);

  if (today.length >= maxPerDay) {
    const lastPnl = Number(today[today.length - 1].pnl) || 0;
    reasons.push(
      lastPnl < 0
        ? "Today already has a losing fill — stop. A second ticket is how the 4% floating daily rule dies."
        : "Today already has a fill — one UTC day, one ticket. Bank it and wait for 00:00 UTC."
    );
  }
  if (consec >= maxConsec) {
    const lastDate = lastTradeDate(trades);
    const todayStr = utcDateStr(now);
    const yesterday = utcShiftDate(now, -1);
    if (lastDate === todayStr || lastDate === yesterday) {
      reasons.push(
        maxConsec + " losses in a row — sit this UTC day. Do not revenge-trade the $94k floor."
      );
    }
  }
  // Late-float lock is a strict-mode survival rule (daily DD resets at 00:00 UTC).
  if (!anytime && snap.msUntilReset < lockMins * 60 * 1000) {
    reasons.push(
      "Inside " +
        lockMins +
        " minutes of the daily reset. Open float can print through the 4% floor at 00:00 UTC."
    );
  }
  if (!window.ok) reasons.push(window.reason);

  const riskMult = consec >= 2 ? 0.5 : 1;
  return {
    locked: reasons.length > 0,
    reasons,
    todayTrades: today.length,
    consecutiveLosses: consec,
    riskMult,
    maxTradesPerDay: maxPerDay,
    window,
    sessionMode: anytime ? "anytime" : "strict",
    snap,
  };
}

export function maxSafeRisk(settings, trades, floatingPnl = 0, now = new Date()) {
  const snap = evaluate(settings, trades, floatingPnl, now);
  const guard = passGuard(settings, trades, floatingPnl, now);
  if (snap.failed) {
    return {
      allowed: false,
      reason: snap.statusLabel,
      riskAmount: 0,
      riskPct: 0,
      binding: snap.breachedOverall ? "overall" : "daily",
      snap,
      guard,
    };
  }
  if (guard.locked) {
    return {
      allowed: false,
      reason: guard.reasons[0],
      riskAmount: 0,
      riskPct: 0,
      desired: 0,
      usable: 0,
      binding: snap.roomDaily <= snap.roomOverall ? "daily" : "overall",
      snap,
      guard,
    };
  }

  const bufferPct = Math.max(0, Math.min(90, Number(settings.buffer) || 0)) / 100;
  const usable = money(snap.bindingRoom * (1 - bufferPct));
  const desired = money(snap.equity * (settings.risk / 100) * guard.riskMult);
  let riskAmount = money(Math.min(desired, usable));

  // Soft-cap by consistency: prefer not to size a win that would leave
  // today as >50% of total if TP hits — still allow trade if first profits.
  const rewardR = Number(settings.rewardR) || 1.5;
  const expectedWin = money(riskAmount * rewardR);
  if (
    isFinite(snap.consistencyMaxAddToday) &&
    snap.consistencyMaxAddToday > 0 &&
    expectedWin > snap.consistencyMaxAddToday &&
    snap.toTarget > 0
  ) {
    const capped = money(snap.consistencyMaxAddToday / rewardR);
    if (capped >= 1 && capped < riskAmount) {
      riskAmount = capped;
    }
  }

  if (riskAmount < 1) {
    return {
      allowed: false,
      reason: "No usable room after the " + settings.buffer + "% safety buffer",
      riskAmount: 0,
      riskPct: 0,
      desired,
      usable,
      binding: snap.roomDaily <= snap.roomOverall ? "daily" : "overall",
      snap,
      guard,
    };
  }

  return {
    allowed: true,
    reason: null,
    riskAmount,
    riskPct: (riskAmount / snap.equity) * 100,
    desired,
    usable,
    capped: riskAmount < desired - 0.01 || guard.riskMult < 1,
    binding: snap.roomDaily <= snap.roomOverall ? "daily" : "overall",
    stopsLeftToday: Math.floor(usable / riskAmount),
    stopsLeftOverall: Math.floor((snap.roomOverall * (1 - bufferPct)) / riskAmount),
    rToTarget: riskAmount > 0 ? snap.toTarget / riskAmount : Infinity,
    expectedWin: money(riskAmount * rewardR),
    snap,
    guard,
  };
}

export function sizePosition(entry, stop, riskAmount) {
  entry = Number(entry);
  stop = Number(stop);
  riskAmount = Number(riskAmount);
  if (!isFinite(entry) || !isFinite(stop) || entry === stop) {
    return { ok: false, error: "Enter a valid entry and stop (they can't be equal)." };
  }
  if (!isFinite(riskAmount) || riskAmount <= 0) {
    return { ok: false, error: "No risk budget available." };
  }
  const distance = Math.abs(entry - stop);
  const units = Math.round((riskAmount / distance) * 1e8) / 1e8;
  const direction = entry > stop ? "Long" : "Short";
  return { ok: true, direction, distance, units, riskAmount };
}

export function previewTrade(settings, trades, pnl, floatingPnl = 0, now = new Date()) {
  const next = trades.concat([{ date: utcDateStr(now), pnl: Number(pnl) || 0 }]);
  return evaluate(settings, next, floatingPnl, now);
}

/** Count Mon–Fri UTC days from start (inclusive) spanning sprintDays calendar days. */
export function tradingDaysInSprint(now = new Date(), sprintDays = 14) {
  let count = 0;
  const dates = [];
  for (let i = 0; i < sprintDays; i++) {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + i));
    const day = d.getUTCDay();
    if (day !== 0 && day !== 6) {
      count++;
      dates.push(utcDateStr(d));
    }
  }
  return { count, dates, endDate: utcShiftDate(now, sprintDays - 1) };
}

/**
 * Personal 2-week path-to-pass plan. Firm has no deadline; this is the
 * operator's own clock so size and patience stay coherent.
 */
export function sprintPlan(settings, trades, floatingPnl = 0, now = new Date()) {
  const snap = evaluate(settings, trades, floatingPnl, now);
  const sized = maxSafeRisk(settings, trades, floatingPnl, now);
  const sprintDays = settings.sprintDays || 14;
  const cal = tradingDaysInSprint(now, sprintDays);
  const rewardR = Number(settings.rewardR) || 1.5;
  const risk = sized.allowed ? sized.riskAmount : money(snap.equity * (settings.risk / 100));
  const win$ = money(risk * rewardR);
  const winsNeeded = win$ > 0 ? Math.ceil(snap.toTarget / win$) : Infinity;
  const lossBudgetStops = sized.allowed ? sized.stopsLeftOverall : 0;
  const avgPerTradingDay = cal.count > 0 ? money(snap.toTarget / cal.count) : snap.toTarget;

  let paceLabel = "On pace if selective";
  if (snap.passed) paceLabel = "Done — protect the buffer";
  else if (snap.failed) paceLabel = "Account failed";
  else if (winsNeeded > cal.count) paceLabel = "Need higher R or slightly more risk — still one ticket/day";
  else if (winsNeeded <= Math.max(5, Math.floor(cal.count * 0.6))) paceLabel = "Comfortable path — do not force B-setups";

  const summary =
    snap.toTarget <= 0
      ? "Target already hit on closed balance. Finish min days / consistency without giving back equity."
      : `Need ${money(snap.toTarget).toLocaleString("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 })} closed · ~${winsNeeded} wins of ${win$.toLocaleString("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 })} at ${rewardR}R · ${cal.count} weekday sessions left in a ${sprintDays}-day sprint.`;

  return {
    sprintDays,
    endDate: cal.endDate,
    tradingDaysLeft: cal.count,
    tradingDates: cal.dates,
    toTarget: snap.toTarget,
    riskAmount: risk,
    rewardR,
    winAmount: win$,
    winsNeeded,
    lossBudgetStops,
    avgPerTradingDay,
    paceLabel,
    summary,
    ifWin: money(snap.closed + win$),
    ifLose: money(snap.closed - risk),
    todayTrades: todayTrades(trades, now).length,
    consecutiveLosses: consecutiveLosses(trades),
    consistencyMaxAddToday: snap.consistencyMaxAddToday,
  };
}

export function equitySeries(settings, trades) {
  const sorted = sortedTrades(trades);
  const points = [{ date: null, equity: settings.balance, pnl: 0, cumulative: 0 }];
  let eq = settings.balance;
  let cum = 0;
  for (const t of sorted) {
    const pnl = Number(t.pnl) || 0;
    eq = money(eq + pnl);
    cum = money(cum + pnl);
    points.push({ date: t.date, equity: eq, pnl, cumulative: cum, id: t.id });
  }
  return points;
}

const api = {
  DEFAULTS,
  money,
  utcDateStr,
  utcShiftDate,
  msUntilUtcMidnight,
  closedEquity,
  equityNow,
  dailyStartEquity,
  todayClosedPnl,
  tradingDaySet,
  floors,
  consistency,
  evaluate,
  todayTrades,
  sortedTrades,
  consecutiveLosses,
  lastTradeDate,
  inUsDataWindow,
  isAnytimeMode,
  passWindow,
  sessionClock,
  passGuard,
  maxSafeRisk,
  sizePosition,
  previewTrade,
  tradingDaysInSprint,
  sprintPlan,
  equitySeries,
};

export default api;
