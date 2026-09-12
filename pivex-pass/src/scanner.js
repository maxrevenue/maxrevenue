/**
 * Pass-mode FX scanner — major pairs only.
 *
 * Data source: Yahoo Finance chart API (already wired in this repo).
 * This is a discretion-reduction tool, not an alpha model.
 * Keep conditions simple, inspectable, and commented WHY.
 *
 * Setup: H1 trend (EMA20 vs EMA50) + M15 pullback to EMA20 with
 * rejection candle, ATR-aware stop (≥ MIN_STOP_PIPS), DEFAULT_RR take profit.
 */

import {
  PAIRS,
  ema,
  atr,
  rsi,
  sizeLots,
  formatPrice,
  priceDistanceToPips,
} from "../public/fx.js";
import {
  evaluate,
  maxSafeRisk,
  passGuard,
  passWindow,
  sprintPlan,
  money,
} from "../public/rules.js";
import {
  DEFAULT_RR,
  MIN_STOP_PIPS,
  MAX_LOTS,
  DEFAULT_RISK_PCT,
  BUFFER_PCT,
} from "./constants.js";
import {
  ensureUtcDaySnapshot,
  getDailyDDRoom,
  getOverallDDRoom,
  closedEquityFromTrades,
  currentEquity,
} from "./equity.js";
import { calcTicket } from "./sizing.js";
import { assertTradeUnlocked } from "./lock.js";
import { consistencyFloatingWarning } from "./consistency.js";

const cache = {
  bars: new Map(),
  ttlMs: 60_000,
};

async function fetchYahoo(symbol, interval = "15m", range = "10d") {
  const url =
    "https://query1.finance.yahoo.com/v8/finance/chart/" +
    encodeURIComponent(symbol) +
    `?interval=${interval}&range=${range}`;
  const res = await fetch(url, {
    headers: {
      "User-Agent": "Mozilla/5.0 (compatible; PivexPass/2.0)",
      Accept: "application/json",
    },
  });
  if (!res.ok) throw new Error("Yahoo " + symbol + " HTTP " + res.status);
  const data = await res.json();
  const result = data?.chart?.result?.[0];
  if (!result) throw new Error("Yahoo empty for " + symbol);
  const ts = result.timestamp || [];
  const q = result.indicators?.quote?.[0] || {};
  const candles = [];
  for (let i = 0; i < ts.length; i++) {
    const o = q.open?.[i];
    const h = q.high?.[i];
    const l = q.low?.[i];
    const c = q.close?.[i];
    if (![o, h, l, c].every((v) => typeof v === "number" && isFinite(v))) continue;
    candles.push({ t: ts[i] * 1000, o, h, l, c, v: q.volume?.[i] || 0 });
  }
  return {
    symbol,
    meta: result.meta || {},
    candles,
    price: result.meta?.regularMarketPrice ?? candles.at(-1)?.c,
  };
}

async function getBars(pair, interval, range) {
  const key = pair.yahoo + "|" + interval + "|" + range;
  const hit = cache.bars.get(key);
  if (hit && Date.now() - hit.at < cache.ttlMs) return hit.data;
  const data = await fetchYahoo(pair.yahoo, interval, range);
  cache.bars.set(key, { at: Date.now(), data });
  return data;
}

/** Build H1 bars from M15 so we only hit Yahoo once per pair. */
function aggregateToH1(m15) {
  const buckets = new Map();
  for (const b of m15) {
    const d = new Date(b.t);
    const key = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), d.getUTCHours());
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = { t: key, o: b.o, h: b.h, l: b.l, c: b.c, v: b.v };
      buckets.set(key, bucket);
    } else {
      bucket.h = Math.max(bucket.h, b.h);
      bucket.l = Math.min(bucket.l, b.l);
      bucket.c = b.c;
      bucket.v += b.v;
    }
  }
  return [...buckets.values()].sort((a, b) => a.t - b.t);
}

/**
 * Detect a simple trend + pullback setup.
 * Every early-return reason is intentional — prefer wait over a forced ticket.
 */
function scoreSetup(pair, m15, h1, rewardR = DEFAULT_RR) {
  // WHY: EMAs and ATR need warm-up; short series produce garbage signals.
  if (m15.length < 80 || h1.length < 60) {
    return { ok: false, reason: "Not enough history yet" };
  }

  const hClose = h1.map((b) => b.c);
  const hEma20 = ema(hClose, 20);
  const hEma50 = ema(hClose, 50);
  const iH = h1.length - 1;
  if (hEma20[iH] == null || hEma50[iH] == null) {
    return { ok: false, reason: "EMA not ready" };
  }

  // WHY: H1 EMA20 > EMA50 and price above EMA20 = clear uptrend bias.
  // Opposite for shorts. No trade without a defined trend — reduces chop.
  const trendUp = hEma20[iH] > hEma50[iH] && hClose[iH] > hEma20[iH];
  const trendDown = hEma20[iH] < hEma50[iH] && hClose[iH] < hEma20[iH];
  if (!trendUp && !trendDown) {
    return { ok: false, reason: "No H1 trend (EMA20/50)" };
  }

  const closes = m15.map((b) => b.c);
  const highs = m15.map((b) => b.h);
  const lows = m15.map((b) => b.l);
  const e20 = ema(closes, 20);
  const atrArr = atr(highs, lows, closes, 14);
  const rsiArr = rsi(closes, 14);
  // WHY: use last CLOSED M15 so we don't chase an unfinished candle.
  const i = m15.length - 2;
  const live = m15[m15.length - 1];
  const bar = m15[i];
  const atrNow = atrArr[i];
  const rsiNow = rsiArr[i];
  const emaNow = e20[i];
  if (atrNow == null || emaNow == null || rsiNow == null) {
    return { ok: false, reason: "Indicators not ready" };
  }

  // WHY: pullback entry = price tagged the shorter MA (EMA20) zone.
  // Zone width scales with ATR so quiet pairs aren't impossible / wild pairs aren't always "tagged".
  const zone = atrNow * 0.35;
  const tagged =
    Math.abs(bar.l - emaNow) <= zone ||
    Math.abs(bar.h - emaNow) <= zone ||
    (bar.l <= emaNow && bar.h >= emaNow);

  if (!tagged) {
    return { ok: false, reason: "No M15 pullback into EMA20" };
  }

  const body = Math.abs(bar.c - bar.o);
  const range = bar.h - bar.l || 1e-9;
  const closePos = (bar.c - bar.l) / range;

  let action = null;
  if (trendUp) {
    // WHY: RSI > 68 on a pullback long = late chase; wait for a cooler bar.
    if (rsiNow > 68) return { ok: false, reason: "RSI too hot for long" };
    // WHY: bullish rejection = close in upper half of range AND green candle.
    if (closePos < 0.55 || bar.c < bar.o) {
      return { ok: false, reason: "No bullish rejection off EMA20" };
    }
    action = "BUY";
  } else {
    // WHY: RSI < 32 on a pullback short = washed-out chase.
    if (rsiNow < 32) return { ok: false, reason: "RSI too washed for short" };
    // WHY: bearish rejection = close in lower half AND red candle.
    if (closePos > 0.45 || bar.c > bar.o) {
      return { ok: false, reason: "No bearish rejection off EMA20" };
    }
    action = "SELL";
  }

  // WHY: entry at live mid — ticket is typed manually; mid is the honest reference.
  const entry = live.c;
  const stopPad = atrNow * 1.25;
  // WHY: hard floor at MIN_STOP_PIPS — sub-10 pip stops are spread/noise (how 10-lot tickets die).
  const minStopDist = pair.pip * MIN_STOP_PIPS;
  let stop;
  if (action === "BUY") {
    // WHY: stop below recent swing lows / ATR pad — structure first, ATR second.
    const swing = Math.min(bar.l, m15[i - 1]?.l ?? bar.l, m15[i - 2]?.l ?? bar.l);
    stop = Math.min(swing, entry - stopPad * 0.85);
    if (entry - stop < atrNow * 0.7) stop = entry - atrNow * 0.9;
    if (entry - stop < minStopDist) stop = entry - minStopDist;
  } else {
    const swing = Math.max(bar.h, m15[i - 1]?.h ?? bar.h, m15[i - 2]?.h ?? bar.h);
    stop = Math.max(swing, entry + stopPad * 0.85);
    if (stop - entry < atrNow * 0.7) stop = entry + atrNow * 0.9;
    if (stop - entry < minStopDist) stop = entry + minStopDist;
  }

  const riskDist = Math.abs(entry - stop);
  const tp =
    action === "BUY" ? entry + riskDist * rewardR : entry - riskDist * rewardR;

  // WHY: score only ranks candidates when multiple pairs qualify — not a confidence claim.
  const trendSep = Math.abs(hEma20[iH] - hEma50[iH]) / (atrNow || 1e-9);
  const rejectQuality = body / range;
  const score =
    40 +
    Math.min(25, trendSep * 8) +
    Math.min(20, rejectQuality * 30) +
    (tagged ? 10 : 0) +
    (action === "BUY" ? (50 - Math.abs(rsiNow - 45)) * 0.15 : Math.abs(rsiNow - 55) * 0.1);

  return {
    ok: true,
    instrument: pair.instrument,
    action,
    direction: action === "BUY" ? "Long" : "Short",
    entry,
    stop,
    tp,
    rewardR,
    atr: atrNow,
    rsi: rsiNow,
    score: Math.round(score * 10) / 10,
    session: "London/NY overlap pass window",
    why:
      (action === "BUY"
        ? "H1 uptrend + M15 EMA20 pullback rejection"
        : "H1 downtrend + M15 EMA20 pullback rejection") +
      ` · RSI ${rsiNow.toFixed(0)} · ATR stop · R=${rewardR}`,
  };
}

/**
 * Scan EURUSD, GBPUSD, USDJPY, AUDUSD, USDCAD for one trend+pullback setup.
 * Returns { action: "wait" } or { action: "setup", setup }.
 * Does NOT size — caller runs calcTicket (Task 2).
 */
export async function scanSetups({
  exclude = [],
  refresh = true,
  rewardR = DEFAULT_RR,
} = {}) {
  if (refresh) {
    for (const [k, v] of cache.bars) {
      if (Date.now() - v.at > cache.ttlMs / 2) cache.bars.delete(k);
    }
  }

  const excluded = new Set((exclude || []).map((x) => String(x).toUpperCase()));
  const skipped = [];
  const candidates = [];
  const sources = {};

  for (const pair of PAIRS) {
    if (excluded.has(pair.instrument)) {
      skipped.push({ instrument: pair.instrument, reason: "Skipped by you" });
      continue;
    }
    try {
      const m15data = await getBars(pair, "15m", "10d");
      sources[pair.instrument] = "yahoo";
      const m15 = m15data.candles;
      const h1 = aggregateToH1(m15);
      const setup = scoreSetup(pair, m15, h1, rewardR);
      if (!setup.ok) {
        skipped.push({ instrument: pair.instrument, reason: setup.reason });
        continue;
      }
      candidates.push(setup);
    } catch (e) {
      sources[pair.instrument] = "error";
      skipped.push({ instrument: pair.instrument, reason: String(e.message || e) });
    }
  }

  candidates.sort((a, b) => b.score - a.score);
  const best = candidates[0] || null;

  if (!best) {
    return {
      action: "wait",
      setup: null,
      message:
        "No pass-mode pullback on EURUSD, GBPUSD, USDJPY, AUDUSD, or USDCAD. Sit on your hands.",
      skipped,
      sources,
    };
  }

  return {
    action: "setup",
    setup: best,
    message: best.why,
    skipped,
    sources,
  };
}

function ticketFieldsFromCalc(ticket, setup) {
  return [
    { label: "Instrument", value: ticket.pair },
    { label: "Then tap", value: ticket.direction },
    { label: "Volume (lots) — OVERWRITE", value: ticket.lotsLabel },
    { label: "Stop Loss (Price)", value: ticket.slLabel },
    { label: "Take Profit (Price)", value: ticket.tpLabel },
    { label: "Full-stop $ risk", value: "$" + Math.round(ticket.dollarRisk).toLocaleString() },
    { label: "Stop distance", value: ticket.stopPips + " pips (≥" + MIN_STOP_PIPS + ")" },
  ];
}

/**
 * Full /api/picks path: lock → rooms → scan → calcTicket.
 * Preserves existing response shape for the frontend.
 */
export async function scanPicks({
  settings,
  trades = [],
  floatingPnl = 0,
  exclude = [],
  refresh = true,
  now = new Date(),
  override = false,
} = {}) {
  floatingPnl = Number(floatingPnl) || 0;

  // SOD snapshot (Task 1) — idempotent per UTC date
  const sod = ensureUtcDaySnapshot({
    now,
    trades,
    startBalance: settings?.balance,
  });
  const closed = closedEquityFromTrades(trades, settings?.balance);
  const equity = currentEquity(closed, floatingPnl);
  const dailyRoom = getDailyDDRoom(equity, sod.startOfDayEquity);
  const overallRoom = getOverallDDRoom(equity);

  const consistencyWarn = consistencyFloatingWarning({
    trades,
    todayFloatingPnl: floatingPnl,
    now,
    startBalance: settings?.balance,
  });

  const snap = evaluate(settings, trades, floatingPnl, now);
  // Align snap rooms with constant-based math (static $94k floor + SOD snapshot)
  snap.dailyStart = sod.startOfDayEquity;
  snap.floorDaily = dailyRoom.dailyFloor;
  snap.floorOverall = overallRoom.floor;
  snap.roomDaily = dailyRoom.roomDollars;
  snap.roomOverall = overallRoom.roomDollars;
  snap.bindingRoom = money(Math.min(dailyRoom.roomDollars, overallRoom.roomDollars));
  snap.equity = equity;
  snap.consistencyWarning = consistencyWarn;

  const sized = maxSafeRisk(settings, trades, floatingPnl, now);
  const guard = passGuard(settings, trades, floatingPnl, now);
  const plan = sprintPlan(settings, trades, floatingPnl, now);
  const window = passWindow(now, settings);

  const passPlan = {
    summary: plan.summary,
    todayTrades: plan.todayTrades,
    consecutiveLosses: plan.consecutiveLosses,
    ifWin: plan.ifWin,
    ifLose: plan.ifLose,
    winsNeeded: plan.winsNeeded,
    tradingDaysLeft: plan.tradingDaysLeft,
    paceLabel: plan.paceLabel,
  };

  const rooms = { daily: dailyRoom, overall: overallRoom, sod };

  if (snap.failed || snap.passed) {
    return {
      status: snap.passed ? "passed" : "failed",
      action: snap.passed ? "passed" : "wait",
      sitOut: true,
      headline: snap.passed ? "Challenge passed" : "Challenge failed",
      detail: snap.statusLabel,
      snap,
      sized,
      passPlan,
      rooms,
      consistency: consistencyWarn,
      fields: [],
      skipped: [],
    };
  }

  // Task 4 — daily lock (explicit override only)
  const lock = assertTradeUnlocked(now, { override, trades });
  if (lock.action === "locked") {
    return {
      status: "locked",
      action: "locked",
      sitOut: true,
      headline: "Day locked",
      detail: lock.message,
      message: lock.message,
      snap,
      sized: { ...sized, allowed: false, reason: lock.message },
      passPlan,
      rooms,
      consistency: consistencyWarn,
      fields: [
        { label: "UTC day", value: lock.utcDate },
        { label: "Daily room", value: "$" + Math.round(dailyRoom.roomDollars).toLocaleString() },
        { label: "Overall room", value: "$" + Math.round(overallRoom.roomDollars).toLocaleString() },
      ],
      skipped: [],
      copyText: "LOCKED — " + lock.message,
    };
  }

  if (!sized.allowed) {
    return {
      status: "sit-out",
      action: "wait",
      sitOut: true,
      headline: "No trade",
      detail: sized.reason,
      snap,
      sized,
      passPlan,
      rooms,
      consistency: consistencyWarn,
      fields: [
        { label: "Window", value: window.ok ? "OPEN 12:00–16:00 UTC" : window.reason },
        { label: "Daily room", value: "$" + Math.round(snap.roomDaily).toLocaleString() },
        { label: "Overall room", value: "$" + Math.round(snap.roomOverall).toLocaleString() },
      ],
      skipped: [],
      copyText: "SIT OUT — " + sized.reason,
    };
  }

  const scan = await scanSetups({
    exclude,
    refresh,
    rewardR: Number(settings?.rewardR) || DEFAULT_RR,
  });

  if (!scan.setup) {
    return {
      status: "sit-out",
      action: "wait",
      sitOut: true,
      headline: "No A-setup right now",
      detail: scan.message,
      snap,
      sized,
      passPlan,
      rooms,
      consistency: consistencyWarn,
      fields: [
        { label: "Window", value: window.ok ? "OPEN — waiting for pullback" : window.reason },
        {
          label: "Risk ready",
          value: "$" + Math.round(sized.riskAmount).toLocaleString() + " · " + sized.riskPct.toFixed(2) + "%",
        },
        { label: "Sprint", value: plan.paceLabel },
      ],
      skipped: scan.skipped,
      sources: scan.sources,
      copyText: "SIT OUT — no A-setup. Do not force a ticket.",
    };
  }

  const best = scan.setup;
  const stopPips = priceDistanceToPips(best.instrument, Math.abs(best.entry - best.stop));
  const riskPct =
    settings?.risk != null ? Number(settings.risk) / 100 : DEFAULT_RISK_PCT;
  const rr = Number(settings?.rewardR) || DEFAULT_RR;

  // Task 2 — calcTicket with daily DD room guardrail
  const calc = calcTicket({
    equity,
    entry: best.entry,
    stopPips,
    riskPct,
    rr,
    pair: best.instrument,
    direction: best.action,
    dailyDDRoomDollars: dailyRoom.roomDollars,
    mid: best.entry,
  });

  if (!calc.ok) {
    return {
      status: "sit-out",
      action: "wait",
      sitOut: true,
      headline: "Setup found but cannot size",
      detail: calc.reason,
      snap,
      sized,
      passPlan,
      rooms,
      consistency: consistencyWarn,
      skipped: [{ instrument: best.instrument, reason: calc.reason }, ...(scan.skipped || [])],
      sources: scan.sources,
      copyText: "SIT OUT — " + calc.reason,
    };
  }

  const t = calc.ticket;
  const pick = {
    ok: true,
    instrument: t.pair,
    action: t.direction,
    direction: t.direction === "BUY" ? "Long" : "Short",
    entry: t.entry,
    stop: t.sl,
    tp: t.tp,
    entryLabel: t.entryLabel,
    stopLabel: t.slLabel,
    tpLabel: t.tpLabel,
    lots: t.lots,
    lotsLabel: t.lotsLabel,
    volumeHint: "overwrite leftover volume — never keep 10.xx lots",
    riskAmount: t.dollarRisk,
    riskPct: (t.dollarRisk / equity) * 100,
    dollarReward: t.dollarReward,
    pips: t.stopPips,
    fields: ticketFieldsFromCalc(t, best),
    why: best.why + (t.cappedByMaxLots ? ` · capped at ${MAX_LOTS} lots` : ""),
    session: best.session,
    score: best.score,
    copyText: [
      "PIVEX PASS TICKET",
      `${t.direction} ${t.lotsLabel} ${t.pair}`,
      `SL ${t.slLabel} · TP ${t.tpLabel}`,
      `Risk ~$${Math.round(t.dollarRisk)} · Reward ~$${Math.round(t.dollarReward)} (R=${rr})`,
      `Buffer ${(BUFFER_PCT * 100).toFixed(0)}% · OVERWRITE volume — do NOT leave 10.xx.`,
      `One ticket today. Do not move SL wider. Log fill after close.`,
    ].join("\n"),
  };

  // Soft consistency: trim TP if a full win would concentrate today above 50%
  if (
    isFinite(snap.consistencyMaxAddToday) &&
    snap.consistencyMaxAddToday > 0 &&
    money(t.dollarReward) > snap.consistencyMaxAddToday &&
    snap.toTarget < t.dollarReward
  ) {
    const dist = Math.abs(pick.entry - pick.stop);
    const maxWin = snap.consistencyMaxAddToday;
    const r = maxWin / t.dollarRisk;
    pick.tp =
      pick.action === "BUY"
        ? pick.entry + dist * Math.min(r, rr)
        : pick.entry - dist * Math.min(r, rr);
    pick.tpLabel = formatPrice(pick.instrument, pick.tp);
    pick.fields = pick.fields.map((f) =>
      f.label === "Take Profit (Price)" ? { ...f, value: pick.tpLabel } : f
    );
    pick.why += " · TP trimmed for consistency headroom";
  }

  return {
    status: "trade",
    action: "trade",
    sitOut: false,
    headline: `${pick.action} ${pick.lotsLabel} ${pick.instrument}`,
    detail: pick.why,
    pick,
    ticket: t,
    snap,
    sized,
    passPlan,
    rooms,
    consistency: consistencyWarn,
    skipped: scan.skipped,
    sources: scan.sources,
    copyText: pick.copyText,
  };
}

export async function health() {
  const sources = {};
  let ok = 0;
  for (const pair of PAIRS) {
    try {
      await getBars(pair, "15m", "1d");
      sources[pair.instrument] = "yahoo";
      ok++;
    } catch {
      sources[pair.instrument] = "error";
    }
  }
  return {
    ok: ok > 0,
    cached: [...cache.bars.values()].some((v) => Date.now() - v.at < cache.ttlMs),
    symbols: ok,
    sources,
    ageMs: Math.min(
      ...[...cache.bars.values()].map((v) => Date.now() - v.at),
      Number.MAX_SAFE_INTEGER
    ),
  };
}

export function clearCache() {
  cache.bars.clear();
}

/** Latest mid for a pair (Yahoo) — used for open-ticket MTM. */
export async function fetchMid(instrument) {
  const pair = PAIRS.find((p) => p.instrument === String(instrument).toUpperCase());
  if (!pair) throw new Error("Unknown pair " + instrument);
  const data = await getBars(pair, "15m", "1d");
  return data.price ?? data.candles.at(-1)?.c;
}
