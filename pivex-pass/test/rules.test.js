import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULTS,
  evaluate,
  maxSafeRisk,
  passWindow,
  consistency,
  sprintPlan,
  previewTrade,
} from "../public/rules.js";
import { sizeLots, pipValueUsd, priceDistanceToPips } from "../public/fx.js";

describe("rules engine", () => {
  it("starts with $4k daily and $6k overall room", () => {
    const snap = evaluate(DEFAULTS, [], 0, new Date("2026-09-08T13:00:00Z"));
    assert.equal(snap.roomDaily, 4000);
    assert.equal(snap.roomOverall, 6000);
    assert.equal(snap.toTarget, 10000);
    assert.equal(snap.status, "IN_PROGRESS");
  });

  it("fails on floating daily breach", () => {
    const snap = evaluate(DEFAULTS, [], -4000, new Date("2026-09-08T13:00:00Z"));
    assert.equal(snap.failed, true);
    assert.equal(snap.status, "FAILED_DAILY");
  });

  it("fails on overall static floor", () => {
    const trades = [{ id: 1, date: "2026-09-07", pnl: -5000 }];
    const snap = evaluate(DEFAULTS, trades, -1000, new Date("2026-09-08T13:00:00Z"));
    assert.equal(snap.failed, true);
    assert.equal(snap.status, "FAILED_OVERALL");
  });

  it("blocks a second ticket the same UTC day", () => {
    const trades = [{ id: 1, date: "2026-09-08", pnl: 500, instrument: "EURUSD" }];
    const sized = maxSafeRisk(DEFAULTS, trades, 0, new Date("2026-09-08T13:30:00Z"));
    assert.equal(sized.allowed, false);
    assert.match(sized.reason, /one ticket|already has a fill/i);
  });

    it("locks outside overlap window in strict mode", () => {
    const w = passWindow(new Date("2026-09-08T10:00:00Z"), DEFAULTS);
    assert.equal(w.ok, false);
  });

  it("opens during London/NY overlap outside news", () => {
    const w = passWindow(new Date("2026-09-08T14:00:00Z"), DEFAULTS);
    assert.equal(w.ok, true);
  });

  it("allows off-hours in anytime mode", () => {
    const settings = { ...DEFAULTS, sessionMode: "anytime" };
    const early = passWindow(new Date("2026-09-08T10:00:00Z"), settings);
    const news = passWindow(new Date("2026-09-08T12:30:00Z"), settings);
    assert.equal(early.ok, true);
    assert.equal(news.ok, true);
    const sized = maxSafeRisk(settings, [], 0, new Date("2026-09-08T10:00:00Z"));
    assert.equal(sized.allowed, true);
  });

  it("still blocks a second ticket in anytime mode", () => {
    const settings = { ...DEFAULTS, sessionMode: "anytime" };
    const trades = [{ id: 1, date: "2026-09-08", pnl: 500, instrument: "EURUSD" }];
    const sized = maxSafeRisk(settings, trades, 0, new Date("2026-09-08T10:00:00Z"));
    assert.equal(sized.allowed, false);
  });

  it("still blocks weekends in anytime mode", () => {
    const settings = { ...DEFAULTS, sessionMode: "anytime" };
    const w = passWindow(new Date("2026-09-12T14:00:00Z"), settings); // Saturday
    assert.equal(w.ok, false);
  });

  it("flags consistency when one day is over half of profit", () => {
    const trades = [
      { id: 1, date: "2026-09-01", pnl: 6000 },
      { id: 2, date: "2026-09-02", pnl: 1000 },
    ];
    const c = consistency(DEFAULTS, trades);
    assert.equal(c.ok, false);
    assert.ok(c.share > 0.5);
  });

  it("builds a sprint plan with wins needed", () => {
    const plan = sprintPlan(DEFAULTS, [], 0, new Date("2026-09-08T13:00:00Z"));
    assert.ok(plan.tradingDaysLeft >= 8);
    assert.ok(plan.winsNeeded >= 5);
    assert.equal(plan.toTarget, 10000);
  });

  it("previewTrade detects a killing loss", () => {
    const preview = previewTrade(DEFAULTS, [], -4500, 0, new Date("2026-09-08T13:00:00Z"));
    assert.equal(preview.failed, true);
  });
});

describe("fx sizing", () => {
  it("prices EURUSD pip value at $10 per lot", () => {
    assert.equal(pipValueUsd("EURUSD", 1.1, 1), 10);
  });

  it("sizes EURUSD lots from stop distance", () => {
    const lots = sizeLots("EURUSD", 1.1, 1.095, 750, 1.1);
    assert.equal(lots.ok, true);
    assert.ok(lots.lots >= 0.01);
    assert.equal(priceDistanceToPips("EURUSD", 0.005), 50);
  });

  it("sizes USDJPY with JPY quote conversion", () => {
    const lots = sizeLots("USDJPY", 150, 149.5, 1000, 150);
    assert.equal(lots.ok, true);
    assert.ok(lots.pipValue > 0 && lots.pipValue < 10);
  });
});
