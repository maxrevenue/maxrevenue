/**
 * Forex helpers for Pivex majors — pip size, pip value, lot sizing, ATR.
 * Assumes standard 100,000-unit lots and USD account.
 */

export const PAIRS = [
  { instrument: "EURUSD", yahoo: "EURUSD=X", digits: 5, pip: 0.0001, quote: "USD", base: "EUR" },
  { instrument: "GBPUSD", yahoo: "GBPUSD=X", digits: 5, pip: 0.0001, quote: "USD", base: "GBP" },
  { instrument: "USDJPY", yahoo: "USDJPY=X", digits: 3, pip: 0.01, quote: "JPY", base: "USD" },
  { instrument: "AUDUSD", yahoo: "AUDUSD=X", digits: 5, pip: 0.0001, quote: "USD", base: "AUD" },
  { instrument: "USDCAD", yahoo: "USDCAD=X", digits: 5, pip: 0.0001, quote: "CAD", base: "USD" },
];

export function pairInfo(instrument) {
  const name = String(instrument || "").toUpperCase().replace(/[^A-Z]/g, "");
  return PAIRS.find((p) => p.instrument === name) || null;
}

export function formatPrice(instrument, price) {
  const p = pairInfo(instrument);
  const digits = p ? p.digits : 5;
  return Number(price).toFixed(digits);
}

/** USD value of 1 pip for 1.00 lot at the given mid (for JPY/CAD quotes). */
export function pipValueUsd(instrument, mid, lot = 1) {
  const p = pairInfo(instrument);
  if (!p || !isFinite(mid) || mid <= 0) return 0;
  const contract = 100000 * lot;
  if (p.quote === "USD") {
    return contract * p.pip; // e.g. EURUSD: 100000 * 0.0001 = $10 / pip / lot
  }
  if (p.base === "USD" && p.quote === "JPY") {
    // USDJPY: pip value in USD = (pip / mid) * contract / contract_unit... 
    // 1 pip move on 1 lot = 100000 * 0.01 = 1000 JPY ≈ 1000/mid USD
    return (contract * p.pip) / mid;
  }
  if (p.base === "USD" && p.quote === "CAD") {
    // USDCAD: 1 pip on 1 lot = 100000 * 0.0001 = 10 CAD ≈ 10/mid USD
    return (contract * p.pip) / mid;
  }
  return contract * p.pip;
}

export function priceDistanceToPips(instrument, distance) {
  const p = pairInfo(instrument);
  if (!p || !isFinite(distance)) return 0;
  return Math.abs(distance) / p.pip;
}

/**
 * Size FX lots so a full stop ≈ riskAmount USD.
 * Rounds down to 0.01 lot, minimum 0.01 if affordable.
 * Pass-mode caps: minStopPips (default 10) and maxLots (default 2).
 */
export function sizeLots(instrument, entry, stop, riskAmount, mid = entry, opts = {}) {
  const p = pairInfo(instrument);
  if (!p) return { ok: false, error: "Unsupported instrument" };
  entry = Number(entry);
  stop = Number(stop);
  riskAmount = Number(riskAmount);
  mid = Number(mid) || entry;
  const minStopPips = Number(opts.minStopPips);
  const maxLots = Number(opts.maxLots);
  const minPips = isFinite(minStopPips) && minStopPips > 0 ? minStopPips : 10;
  const lotCap = isFinite(maxLots) && maxLots > 0 ? maxLots : 2;
  if (!isFinite(entry) || !isFinite(stop) || entry === stop) {
    return { ok: false, error: "Invalid entry/stop" };
  }
  if (!isFinite(riskAmount) || riskAmount <= 0) {
    return { ok: false, error: "No risk budget" };
  }
  const distance = Math.abs(entry - stop);
  const pips = priceDistanceToPips(instrument, distance);
  if (pips < minPips) {
    return {
      ok: false,
      error:
        "Stop is only " +
        (Math.round(pips * 10) / 10) +
        " pips — pass mode needs ≥ " +
        minPips +
        " pips (your 3-pip GBPUSD stop is how 10-lot tickets die)",
      pips,
    };
  }
  const pv1 = pipValueUsd(instrument, mid, 1);
  if (pv1 <= 0) return { ok: false, error: "Could not price pip value" };
  const rawLots = riskAmount / (pips * pv1);
  let lots = Math.floor(rawLots * 100) / 100;
  if (lots < 0.01) {
    return {
      ok: false,
      error: "Risk budget too small for 0.01 lot at this stop distance",
      rawLots,
      pips,
      pipValue: pv1,
    };
  }
  let capped = false;
  if (lots > lotCap) {
    lots = Math.floor(lotCap * 100) / 100;
    capped = true;
  }
  const actualRisk = Math.round(lots * pips * pv1 * 100) / 100;
  const direction = entry > stop ? "Long" : "Short";
  const action = direction === "Long" ? "BUY" : "SELL";
  return {
    ok: true,
    instrument: p.instrument,
    direction,
    action,
    lots,
    lotsLabel: lots.toFixed(2),
    pips: Math.round(pips * 10) / 10,
    pipValue: Math.round(pv1 * 100) / 100,
    actualRisk,
    distance,
    entry,
    stop,
    cappedByMaxLots: capped,
    maxLots: lotCap,
  };
}

export function ema(values, period) {
  const out = new Array(values.length).fill(null);
  if (values.length < period) return out;
  let sum = 0;
  for (let i = 0; i < period; i++) sum += values[i];
  let prev = sum / period;
  out[period - 1] = prev;
  const k = 2 / (period + 1);
  for (let i = period; i < values.length; i++) {
    prev = values[i] * k + prev * (1 - k);
    out[i] = prev;
  }
  return out;
}

export function sma(values, period) {
  const out = new Array(values.length).fill(null);
  let sum = 0;
  for (let i = 0; i < values.length; i++) {
    sum += values[i];
    if (i >= period) sum -= values[i - period];
    if (i >= period - 1) out[i] = sum / period;
  }
  return out;
}

export function atr(highs, lows, closes, period = 14) {
  const tr = new Array(closes.length).fill(null);
  tr[0] = highs[0] - lows[0];
  for (let i = 1; i < closes.length; i++) {
    tr[i] = Math.max(
      highs[i] - lows[i],
      Math.abs(highs[i] - closes[i - 1]),
      Math.abs(lows[i] - closes[i - 1])
    );
  }
  return sma(tr.map((v) => v || 0), period);
}

export function rsi(closes, period = 14) {
  const out = new Array(closes.length).fill(null);
  if (closes.length <= period) return out;
  let gains = 0;
  let losses = 0;
  for (let i = 1; i <= period; i++) {
    const d = closes[i] - closes[i - 1];
    if (d >= 0) gains += d;
    else losses -= d;
  }
  let avgGain = gains / period;
  let avgLoss = losses / period;
  out[period] = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
  for (let i = period + 1; i < closes.length; i++) {
    const d = closes[i] - closes[i - 1];
    const g = d > 0 ? d : 0;
    const l = d < 0 ? -d : 0;
    avgGain = (avgGain * (period - 1) + g) / period;
    avgLoss = (avgLoss * (period - 1) + l) / period;
    out[i] = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
  }
  return out;
}

/** Approximate margin used for lots at 1:leverage. */
export function marginUsed(instrument, lots, mid, leverage = 30) {
  const p = pairInfo(instrument);
  if (!p) return 0;
  const notional = 100000 * lots;
  let usdNotional = notional;
  if (p.quote === "USD") usdNotional = notional * mid; // base * mid
  else if (p.base === "USD") usdNotional = notional; // USD base
  return usdNotional / leverage;
}
