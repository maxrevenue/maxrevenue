/**
 * Unit tests for the core check-for-a-trade math:
 * calcTicket, UTC SOD equity snapshot, daily DD with floating P&L,
 * lock, and consistency warning.
 */

import { describe, it, beforeEach } from "node:test";
import assert from "node:assert/strict";

import {
  START_BALANCE,
  TARGET_BALANCE,
  DAILY_DD_PCT,
  OVERALL_DD_FLOOR,
  DEFAULT_RISK_PCT,
  MAX_LOTS,
  MIN_STOP_PIPS,
  DEFAULT_RR,
  BUFFER_PCT,
  CONSISTENCY_MAX_SHARE,
} from "../src/constants.js";
import {
  snapshotStartOfDayEquity,
  getStartOfDaySnapshot,
  clearStartOfDaySnapshots,
  ensureUtcDaySnapshot,
  reconstructStartOfDayEquity,
  getDailyDDRoom,
  getOverallDDRoom,
  floatingPnlForTicket,
  markOpenPositions,
  currentEquity,
  money,
} from "../src/equity.js";
import { calcTicket } from "../src/sizing.js";
import {
  lockDay,
  clearLocks,
  assertTradeUnlocked,
  syncLocksFromTrades,
  unlockDay,
  overrideFromArgv,
} from "../src/lock.js";
import { consistencyFloatingWarning } from "../src/consistency.js";

describe("named constants", () => {
  it("matches the challenge rule sheet", () => {
    assert.equal(START_BALANCE, 100000);
    assert.equal(TARGET_BALANCE, 110000);
    assert.equal(DAILY_DD_PCT, 0.04);
    assert.equal(OVERALL_DD_FLOOR, 94000);
    assert.equal(DEFAULT_RISK_PCT, 0.0075);
    assert.equal(MAX_LOTS, 2);
    assert.equal(MIN_STOP_PIPS, 10);
    assert.equal(DEFAULT_RR, 1.8);
    assert.equal(BUFFER_PCT, 0.15);
    assert.equal(CONSISTENCY_MAX_SHARE, 0.5);
  });
});

describe("calcTicket", () => {
  const base = {
    equity: 100000,
    entry: 1.1,
    stopPips: 20,
    pair: "EURUSD",
    direction: "BUY",
    dailyDDRoomDollars: 4000,
  };

  it("rejects stops under MIN_STOP_PIPS", () => {
    const r = calcTicket({ ...base, stopPips: 9.9 });
    assert.equal(r.ok, false);
    assert.equal(r.ticket, null);
    assert.match(r.reason, /10 pips/i);
  });

  it("accepts exactly MIN_STOP_PIPS", () => {
    const r = calcTicket({ ...base, stopPips: 10 });
    assert.equal(r.ok, true);
    assert.equal(r.ticket.stopPips, 10);
  });

  it("applies BUFFER_PCT to dollar risk budget", () => {
    // equity * 0.0075 * (1 - 0.15) = 100000 * 0.0075 * 0.85 = 637.5
    const r = calcTicket({ ...base, stopPips: 50 });
    assert.equal(r.ok, true);
    assert.equal(r.ticket.bufferedRiskBudget, 637.5);
    // EURUSD: $10/pip/lot → lots = floor(637.5 / (50*10)*100)/100 = 1.27
    assert.equal(r.ticket.lots, 1.27);
    assert.ok(r.ticket.dollarRisk <= 637.5 + 0.01);
  });

  it("caps lots at MAX_LOTS = 2.00", () => {
    // Tiny legal stop + huge equity would otherwise size >> 2
    const r = calcTicket({
      ...base,
      equity: 500000,
      stopPips: 10,
      riskPct: 0.05,
      dailyDDRoomDollars: 50000,
    });
    assert.equal(r.ok, true);
    assert.equal(r.ticket.lots, 2);
    assert.equal(r.ticket.cappedByMaxLots, true);
  });

  it("rejects when full SL would breach remaining daily DD room", () => {
    // 2 lots * 20 pips * $10 = $400 risk; room only $100
    const r = calcTicket({
      ...base,
      stopPips: 20,
      riskPct: 0.01,
      dailyDDRoomDollars: 100,
    });
    assert.equal(r.ok, false);
    assert.match(r.reason, /4% daily|daily DD/i);
  });

  it("places SL/TP from entry, direction, and DEFAULT_RR", () => {
    // FX prices need pip precision — do NOT round SL/TP with money() (cents).
    const buy = calcTicket({ ...base, direction: "BUY", stopPips: 20, rr: 1.8 });
    assert.equal(buy.ok, true);
    assert.equal(buy.ticket.sl, 1.1 - 20 * 0.0001); // 1.098
    assert.equal(buy.ticket.tp, 1.1 + 20 * 0.0001 * 1.8); // 1.1036
    assert.equal(buy.ticket.dollarReward, money(buy.ticket.dollarRisk * 1.8));

    const sell = calcTicket({ ...base, direction: "SELL", stopPips: 20, rr: 1.8 });
    assert.equal(sell.ok, true);
    assert.equal(sell.ticket.sl, 1.1 + 20 * 0.0001); // 1.102
    assert.equal(sell.ticket.tp, 1.1 - 20 * 0.0001 * 1.8); // 1.0964
  });

  it("sizes USDJPY with quote conversion", () => {
    const r = calcTicket({
      equity: 100000,
      entry: 150,
      stopPips: 30,
      pair: "USDJPY",
      direction: "SELL",
      dailyDDRoomDollars: 4000,
      mid: 150,
    });
    assert.equal(r.ok, true);
    assert.ok(r.ticket.lots > 0 && r.ticket.lots <= 2);
    assert.ok(r.ticket.pipValue > 0 && r.ticket.pipValue < 10);
  });
});

describe("UTC day-boundary equity snapshot", () => {
  beforeEach(() => {
    clearStartOfDaySnapshots();
  });

  it("stores { utcDate, startOfDayEquity }", () => {
    const snap = snapshotStartOfDayEquity("2026-09-10", 99450.33);
    assert.equal(snap.utcDate, "2026-09-10");
    assert.equal(snap.startOfDayEquity, 99450.33);
    assert.deepEqual(getStartOfDaySnapshot("2026-09-10").startOfDayEquity, 99450.33);
  });

  it("ensureUtcDaySnapshot is idempotent for the same UTC date", () => {
    const now = new Date("2026-09-10T00:00:00.000Z");
    const a = ensureUtcDaySnapshot({
      now,
      equityAtBoundary: 99500,
    });
    const b = ensureUtcDaySnapshot({
      now: new Date("2026-09-10T15:00:00.000Z"),
      equityAtBoundary: 99999, // must NOT overwrite
    });
    assert.equal(a.startOfDayEquity, 99500);
    assert.equal(b.startOfDayEquity, 99500);
  });

  it("reconstructs SOD from prior-day fills only (not today)", () => {
    const trades = [
      { date: "2026-09-09", pnl: -441.32 },
      { date: "2026-09-10", pnl: -108.35 },
    ];
    const sod = reconstructStartOfDayEquity(trades, "2026-09-10");
    assert.equal(sod, money(100000 - 441.32));
    // Today's fill must not be in SOD
    assert.notEqual(sod, money(100000 - 441.32 - 108.35));
  });

  it("snapshots at exactly 00:00 UTC for a new day", () => {
    const midnight = new Date("2026-09-11T00:00:00.000Z");
    const snap = ensureUtcDaySnapshot({
      now: midnight,
      trades: [{ date: "2026-09-10", pnl: -108.35 }],
    });
    assert.equal(snap.utcDate, "2026-09-11");
    assert.equal(snap.startOfDayEquity, money(100000 - 108.35));
  });
});

describe("daily DD room with floating P&L", () => {
  it("starts with full 4% room at SOD", () => {
    const room = getDailyDDRoom(100000, 100000);
    assert.equal(room.maxLossDollars, 4000);
    assert.equal(room.dailyFloor, 96000);
    assert.equal(room.roomDollars, 4000);
    assert.equal(room.breached, false);
  });

  it("counts floating loss against the daily floor", () => {
    // SOD 100000, closed unchanged, float -3500 → equity 96500 → room 500
    const equity = currentEquity(100000, -3500);
    const room = getDailyDDRoom(equity, 100000);
    assert.equal(equity, 96500);
    assert.equal(room.roomDollars, 500);
    assert.equal(room.usedDollars, 3500);
    assert.equal(room.breached, false);
  });

  it("breaches daily DD on floating alone (no closed loss)", () => {
    const equity = currentEquity(100000, -4000);
    const room = getDailyDDRoom(equity, 100000);
    assert.equal(room.breached, true);
    assert.equal(room.roomDollars, 0);
  });

  it("uses SOD after prior losses, not START_BALANCE", () => {
    // After -$550 closed prior day, SOD = 99450 → 4% = 3978 → floor 95472
    const sod = 99450;
    const room = getDailyDDRoom(99450, sod);
    assert.equal(room.maxLossDollars, money(99450 * 0.04));
    assert.equal(room.dailyFloor, money(99450 - 99450 * 0.04));
  });

  it("marks open ticket to market (EURUSD long)", () => {
    const m = floatingPnlForTicket(
      { pair: "EURUSD", direction: "BUY", lots: 2, entry: 1.1 },
      1.101 // +10 pips * $10 * 2 = +$200
    );
    assert.equal(m.ok, true);
    assert.equal(m.floatingPnl, 200);
  });

  it("marks open ticket to market (USDJPY short)", async () => {
    const { floatingPnl, marks } = await markOpenPositions(
      [{ pair: "USDJPY", direction: "SELL", lots: 1, entry: 150 }],
      { USDJPY: 149.7 } // +30 pips; pv ≈ 1000/150 ≈ 6.666 → ~$200
    );
    assert.equal(marks[0].ok, true);
    assert.ok(floatingPnl > 190 && floatingPnl < 210);
  });

  it("overall room is vs static $94,000 floor", () => {
    const room = getOverallDDRoom(99500);
    assert.equal(room.floor, 94000);
    assert.equal(room.roomDollars, 5500);
    assert.equal(getOverallDDRoom(94000).breached, true);
  });
});

describe("daily lock", () => {
  beforeEach(() => clearLocks());

  it("locks after a logged fill for that UTC date", () => {
    lockDay("2026-09-10");
    const gate = assertTradeUnlocked("2026-09-10");
    assert.equal(gate.action, "locked");
    assert.match(gate.message, /logged fill|one ticket/i);
  });

  it("syncs locks from trades ledger", () => {
    syncLocksFromTrades([{ date: "2026-09-09", pnl: -441.32 }]);
    assert.equal(assertTradeUnlocked("2026-09-09").action, "locked");
    assert.equal(assertTradeUnlocked("2026-09-10").action, "ok");
  });

  it("override requires explicit true — never default", () => {
    lockDay("2026-09-10");
    assert.equal(assertTradeUnlocked("2026-09-10", { override: false }).action, "locked");
    assert.equal(assertTradeUnlocked("2026-09-10", {}).action, "locked");
    assert.equal(assertTradeUnlocked("2026-09-10", { override: true }).action, "ok");
  });

  it("overrideFromArgv only trips on --override", () => {
    assert.equal(overrideFromArgv(["node", "x"]), false);
    assert.equal(overrideFromArgv(["node", "x", "--override"]), true);
  });

  it("unlockDay is explicit repair only", () => {
    lockDay("2026-09-10");
    unlockDay("2026-09-10");
    assert.equal(assertTradeUnlocked("2026-09-10").action, "ok");
  });
});

describe("consistency floating warning", () => {
  it("warns when today's float would push day share above 50%", () => {
    const trades = [
      { date: "2026-09-01", pnl: 1000 },
      { date: "2026-09-02", pnl: 1000 },
    ];
    // Closed profit 2000; float +2500 today → today 2500 / total 4500 > 50%
    const w = consistencyFloatingWarning({
      trades,
      todayFloatingPnl: 2500,
      now: new Date("2026-09-10T14:00:00Z"),
    });
    assert.equal(w.warn, true);
    assert.match(w.message, /50% consistency|partial close/i);
    assert.ok(w.share > 0.5);
  });

  it("does not warn when share stays at or under 50%", () => {
    const trades = [
      { date: "2026-09-01", pnl: 3000 },
      { date: "2026-09-02", pnl: 3000 },
    ];
    const w = consistencyFloatingWarning({
      trades,
      todayFloatingPnl: 2000,
      now: new Date("2026-09-10T14:00:00Z"),
    });
    // today 2000 / (6000+2000) = 0.25
    assert.equal(w.warn, false);
    assert.equal(w.message, null);
  });

  it("does not warn on floating losses", () => {
    const w = consistencyFloatingWarning({
      trades: [{ date: "2026-09-01", pnl: 5000 }],
      todayFloatingPnl: -200,
      now: new Date("2026-09-10T14:00:00Z"),
    });
    assert.equal(w.warn, false);
  });
});
