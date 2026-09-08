/**
 * Pass-mode FX scanner — major pairs only, London/NY overlap bias.
 *
 * Setup: H1 trend (EMA20 vs EMA50) + M15 pullback toward EMA20 with
 * rejection, ATR stop, fixed reward-R take profit. Sits out unless A-setup.
 */

import {
  PAIRS,
  ema,
  atr,
  rsi,
  sizeLots,
  formatPrice,
  pairInfo,
} from "../public/fx.js";
import {
  evaluate,
  maxSafeRisk,
  passGuard,
  passWindow,
  sprintPlan,
  money,
} from "../public/rules.js";

const cache = {
  bars: new Map(), // yahoo -> { at, candles }
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

function scoreSetup(pair, m15, h1) {
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
  const i = m15.length - 2; // last closed M15
  const live = m15[m15.length - 1];
  const bar = m15[i];
  const atrNow = atrArr[i];
  const rsiNow = rsiArr[i];
  const emaNow = e20[i];
  if (atrNow == null || emaNow == null || rsiNow == null) {
    return { ok: false, reason: "Indicators not ready" };
  }

  // Pullback: price tagged EMA20 zone recently, then rejected in trend direction
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
    if (rsiNow > 68) return { ok: false, reason: "RSI too hot for long" };
    if (closePos < 0.55 || bar.c < bar.o) {
      return { ok: false, reason: "No bullish rejection off EMA20" };
    }
    action = "BUY";
  } else {
    if (rsiNow < 32) return { ok: false, reason: "RSI too washed for short" };
    if (closePos > 0.45 || bar.c > bar.o) {
      return { ok: false, reason: "No bearish rejection off EMA20" };
    }
    action = "SELL";
  }

  const entry = live.c;
  const stopPad = atrNow * 1.15;
  let stop;
  let tp;
  if (action === "BUY") {
    const swing = Math.min(bar.l, m15[i - 1]?.l ?? bar.l, m15[i - 2]?.l ?? bar.l);
    stop = Math.min(swing, entry - stopPad * 0.85);
    // Ensure stop is below entry by at least 0.6 ATR
    if (entry - stop < atrNow * 0.6) stop = entry - atrNow * 0.85;
  } else {
    const swing = Math.max(bar.h, m15[i - 1]?.h ?? bar.h, m15[i - 2]?.h ?? bar.h);
    stop = Math.max(swing, entry + stopPad * 0.85);
    if (stop - entry < atrNow * 0.6) stop = entry + atrNow * 0.85;
  }

  const riskDist = Math.abs(entry - stop);
  const rewardR = 1.5;
  tp = action === "BUY" ? entry + riskDist * rewardR : entry - riskDist * rewardR;

  // Quality score
  const trendSep = Math.abs(hEma20[iH] - hEma50[iH]) / (atrNow || 1e-9);
  const rejectQuality = body / range;
  const score =
    40 +
    Math.min(25, trendSep * 8) +
    Math.min(20, rejectQuality * 30) +
    (tagged ? 10 : 0) +
    (action === "BUY" ? (50 - Math.abs(rsiNow - 45)) * 0.15 : (Math.abs(rsiNow - 55)) * 0.1);

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
      (action === "BUY" ? "H1 uptrend + M15 EMA20 pullback rejection" : "H1 downtrend + M15 EMA20 pullback rejection") +
      ` · RSI ${rsiNow.toFixed(0)} · ATR stop`,
  };
}

function buildTicket(setup, sized, settings) {
  const sizedLots = sizeLots(setup.instrument, setup.entry, setup.stop, sized.riskAmount, setup.entry);
  if (!sizedLots.ok) {
    return { ok: false, reason: sizedLots.error };
  }

  const entryLabel = formatPrice(setup.instrument, setup.entry);
  const stopLabel = formatPrice(setup.instrument, setup.stop);
  const tpLabel = formatPrice(setup.instrument, setup.tp);
  const fields = [
    { label: "Instrument", value: setup.instrument },
    { label: "Then tap", value: setup.action },
    { label: "Volume (lots)", value: sizedLots.lotsLabel },
    { label: "Stop Loss (Price)", value: stopLabel },
    { label: "Take Profit (Price)", value: tpLabel },
    { label: "Full-stop $ risk", value: "$" + Math.round(sizedLots.actualRisk).toLocaleString() },
    { label: "Stop distance", value: sizedLots.pips + " pips" },
  ];

  const copyText = [
    `PIVEX PASS TICKET`,
    `${setup.action} ${sizedLots.lotsLabel} ${setup.instrument}`,
    `SL ${stopLabel} · TP ${tpLabel}`,
    `Risk ~$${Math.round(sizedLots.actualRisk)} (${sized.riskPct.toFixed(2)}%)`,
    `One ticket today. Do not move SL wider. Log fill after close.`,
  ].join("\n");

  return {
    ok: true,
    instrument: setup.instrument,
    action: setup.action,
    direction: setup.direction,
    entry: setup.entry,
    stop: setup.stop,
    tp: setup.tp,
    entryLabel,
    stopLabel,
    tpLabel,
    lots: sizedLots.lots,
    lotsLabel: sizedLots.lotsLabel,
    volumeHint: "standard lots",
    riskAmount: sizedLots.actualRisk,
    riskPct: sized.riskPct,
    pips: sizedLots.pips,
    fields,
    why: setup.why,
    session: setup.session,
    score: setup.score,
    copyText,
  };
}

export async function scanPicks({
  settings,
  trades = [],
  floatingPnl = 0,
  exclude = [],
  refresh = true,
  now = new Date(),
} = {}) {
  if (refresh) {
    // Soft refresh: expire cache entries older than half TTL by clearing
    for (const [k, v] of cache.bars) {
      if (Date.now() - v.at > cache.ttlMs / 2) cache.bars.delete(k);
    }
  }

  const snap = evaluate(settings, trades, floatingPnl, now);
  const sized = maxSafeRisk(settings, trades, floatingPnl, now);
  const guard = passGuard(settings, trades, floatingPnl, now);
  const plan = sprintPlan(settings, trades, floatingPnl, now);
  const window = passWindow(now);

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

  if (snap.failed || snap.passed) {
    return {
      status: snap.passed ? "passed" : "failed",
      sitOut: true,
      headline: snap.passed ? "Challenge passed" : "Challenge failed",
      detail: snap.statusLabel,
      snap,
      sized,
      passPlan,
      fields: [],
      skipped: [],
    };
  }

  if (!sized.allowed) {
    return {
      status: "sit-out",
      sitOut: true,
      headline: "No trade",
      detail: sized.reason,
      snap,
      sized,
      passPlan,
      fields: [
        { label: "Window", value: window.ok ? "OPEN 12:00–16:00 UTC" : window.reason },
        { label: "Daily room", value: "$" + Math.round(snap.roomDaily).toLocaleString() },
        { label: "Overall room", value: "$" + Math.round(snap.roomOverall).toLocaleString() },
      ],
      skipped: [],
      copyText: "SIT OUT — " + sized.reason,
    };
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
      const setup = scoreSetup(pair, m15, h1);
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
  const best = candidates[0];

  if (!best) {
    return {
      status: "sit-out",
      sitOut: true,
      headline: "No A-setup right now",
      detail:
        "No pass-mode pullback on EURUSD, GBPUSD, USDJPY, AUDUSD, or USDCAD. Sit on your hands. Scan again in 30–60 minutes rather than forcing a B-trade.",
      snap,
      sized,
      passPlan,
      fields: [
        { label: "Window", value: window.ok ? "OPEN — waiting for pullback" : window.reason },
        { label: "Risk ready", value: "$" + Math.round(sized.riskAmount).toLocaleString() + " · " + sized.riskPct.toFixed(2) + "%" },
        { label: "Sprint", value: plan.paceLabel },
      ],
      skipped,
      sources,
      copyText: "SIT OUT — no A-setup. Do not force a ticket.",
    };
  }

  const ticket = buildTicket(best, sized, settings);
  if (!ticket.ok) {
    return {
      status: "sit-out",
      sitOut: true,
      headline: "Setup found but cannot size",
      detail: ticket.reason,
      snap,
      sized,
      passPlan,
      skipped: [{ instrument: best.instrument, reason: ticket.reason }, ...skipped],
      sources,
    };
  }

  // Cap TP by consistency soft limit when near target
  if (
    isFinite(snap.consistencyMaxAddToday) &&
    snap.consistencyMaxAddToday > 0 &&
    money(ticket.riskAmount * best.rewardR) > snap.consistencyMaxAddToday &&
    snap.toTarget < ticket.riskAmount * best.rewardR
  ) {
    const dist = Math.abs(ticket.entry - ticket.stop);
    const maxWin = snap.consistencyMaxAddToday;
    const r = maxWin / ticket.riskAmount;
    ticket.tp =
      ticket.action === "BUY" ? ticket.entry + dist * Math.min(r, best.rewardR) : ticket.entry - dist * Math.min(r, best.rewardR);
    ticket.tpLabel = formatPrice(ticket.instrument, ticket.tp);
    ticket.fields = ticket.fields.map((f) =>
      f.label === "Take Profit (Price)" ? { ...f, value: ticket.tpLabel } : f
    );
    ticket.why += " · TP trimmed for consistency headroom";
  }

  return {
    status: "trade",
    sitOut: false,
    headline: `${ticket.action} ${ticket.lotsLabel} ${ticket.instrument}`,
    detail: ticket.why,
    pick: ticket,
    snap,
    sized,
    passPlan,
    skipped,
    sources,
    copyText: ticket.copyText,
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
