import * as R from "./rules.js";
import { sizeLots, formatPrice, pairInfo } from "./fx.js";

const STORAGE_KEY = "pivex-pass-v2";
const V1_KEY = "pivex-ledger-data";

const DEFAULTS = { ...R.DEFAULTS };
let state = {
  settings: { ...DEFAULTS },
  trades: [],
  floatingPnl: 0,
  guardOn: true,
  skipped: [],
  sprintStarted: R.utcDateStr(),
};
let loaded = false;
let lastPick = null;
let scanning = false;

function $(id) {
  return document.getElementById(id);
}

async function storageGet(key) {
  if (window.storage?.get) return window.storage.get(key);
  const raw = localStorage.getItem(key);
  return raw ? { value: raw } : null;
}
async function storageSet(key, value) {
  if (window.storage?.set) return window.storage.set(key, value);
  localStorage.setItem(key, value);
}
async function storageRemove(key) {
  if (window.storage?.remove) return window.storage.remove(key);
  localStorage.removeItem(key);
}

function fmt(n) {
  const sign = n < 0 ? "−" : "";
  return sign + "$" + Math.abs(Math.round(n)).toLocaleString();
}
function fmtPct(n) {
  return (Math.round(n * 100) / 100).toFixed(2) + "%";
}
function fmtCountdown(ms) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h + "h " + String(m).padStart(2, "0") + "m";
}
function escapeHtml(str) {
  const d = document.createElement("div");
  d.textContent = str == null ? "" : String(str);
  return d.innerHTML;
}

function snapNow() {
  return R.evaluate(state.settings, state.trades, state.floatingPnl);
}
function sizedNow() {
  return R.maxSafeRisk(state.settings, state.trades, state.floatingPnl);
}
function planNow() {
  // Recompute sprint relative to personal start if set
  const start = state.sprintStarted ? new Date(state.sprintStarted + "T00:00:00Z") : new Date();
  const elapsed = Math.floor((Date.now() - start.getTime()) / 86400000);
  const remainingCal = Math.max(1, (state.settings.sprintDays || 14) - elapsed);
  return R.sprintPlan(
    { ...state.settings, sprintDays: remainingCal },
    state.trades,
    state.floatingPnl
  );
}

function pillClass(status) {
  if (status === "PASSED") return "passed";
  if (status === "TARGET_HIT_NEED_DAYS" || status === "TARGET_HIT_NEED_CONSISTENCY") return "need-days";
  if (status === "FAILED_DAILY" || status === "FAILED_OVERALL") return "failed";
  return "in-progress";
}

async function loadState() {
  try {
    let res = await storageGet(STORAGE_KEY);
    if (!res?.value) res = await storageGet(V1_KEY);
    if (res?.value) {
      const parsed = JSON.parse(res.value);
      state.settings = { ...DEFAULTS, ...(parsed.settings || {}) };
      state.trades = parsed.trades || [];
      state.floatingPnl = Number(parsed.floatingPnl) || 0;
      state.guardOn = parsed.guardOn !== false;
      state.skipped = Array.isArray(parsed.skipped) ? parsed.skipped : [];
      state.sprintStarted = parsed.sprintStarted || R.utcDateStr();
    }
  } catch (_) {}
  const repaired = repairMisdatedSep9Loss();
  loaded = true;
  hydrateSettings();
  $("floatingPnl").value = state.floatingPnl || 0;
  $("guardOn").checked = state.guardOn;
  if (repaired) await persist();
  render();
  fetchPicks();
}

/** The Sep 9 GBPUSD -441.32 was often saved with "today" when clicked on Sep 10. */
function repairMisdatedSep9Loss() {
  const REAL_DATE = "2026-09-09";
  const today = R.utcDateStr();
  let changed = false;
  for (const t of state.trades) {
    const pnl = Number(t.pnl) || 0;
    const isThatLoss =
      Math.abs(pnl + 441.32) < 0.02 &&
      String(t.instrument || "").toUpperCase().includes("GBP");
    if (!isThatLoss) continue;
    if (t.date !== REAL_DATE) {
      t.date = REAL_DATE;
      t.notes = (t.notes || "") + (t.notes ? " · " : "") + "date fixed to 2026-09-09 (trade was yesterday)";
      changed = true;
    }
  }
  // Also clear accidental "today lock" if the only today fill is that misdated loss (already moved)
  return changed;
}

async function persist() {
  try {
    await storageSet(STORAGE_KEY, JSON.stringify(state));
  } catch (e) {
    console.error("save failed", e);
  }
}

function hydrateSettings() {
  $("setBalance").value = state.settings.balance;
  $("setDaily").value = state.settings.dailyDD;
  $("setOverall").value = state.settings.overallDD;
  $("setTarget").value = state.settings.target;
  $("setRisk").value = state.settings.risk;
  $("setBuffer").value = state.settings.buffer;
  $("setReward").value = state.settings.rewardR;
  $("setSprint").value = state.settings.sprintDays;
  $("setSessionMode").value = state.settings.sessionMode === "anytime" ? "anytime" : "strict";
}

function render() {
  const s = state.settings;
  const snap = snapNow();
  const sized = sizedNow();
  const plan = planNow();
  const session = R.sessionClock(new Date(), state.settings);

  $("equityVal").textContent = fmt(snap.equity);
  $("dailyRoom").textContent = fmt(snap.roomDaily);
  $("dailyRoom").className = snap.roomDaily <= (s.balance * s.dailyDD) / 100 * 0.25 ? "neg" : "pos";
  $("overallRoom").textContent = fmt(snap.roomOverall);
  $("overallRoom").className = snap.roomOverall <= (s.balance * s.overallDD) / 100 * 0.25 ? "neg" : "pos";
  $("toTarget").textContent = fmt(snap.toTarget);

  const closedMove = snap.closed - s.balance;
  $("closedPnl").textContent = fmt(closedMove);
  $("closedPnl").className = closedMove >= 0 ? "pos" : "neg";
  $("todayPnl").textContent = fmt(snap.todayClosedPnl);
  $("todayPnl").className =
    snap.todayClosedPnl > 0 ? "pos" : snap.todayClosedPnl < 0 ? "neg" : "";
  $("tradingDays").textContent = snap.tradingDays + " / " + snap.minTradingDays;
  $("tradingDays").className = snap.daysMet ? "pos" : "warn";

  const pill = $("statusPill");
  pill.textContent = snap.statusLabel;
  pill.className = "pill " + pillClass(snap.status);

  renderSprint(plan, session);
  drawGauge(snap, s);
  drawCurve();
  renderRules(snap);
  renderBot(sized, snap, plan);
  renderDayLock(snap, sized);
  renderCoach(snap, sized, plan, session);
  $("calcRisk").value =
    state.guardOn && sized.allowed ? Math.round(sized.riskPct * 100) / 100 : state.settings.risk;
  renderJournal();
  renderClock(snap);
}

function renderCoach(snap, sized, plan, session) {
  const step = $("coachStep");
  const title = $("coachTitle");
  const text = $("coachText");
  const stats = $("coachStats");
  const today = R.todayTrades(state.trades);
  const updateEl = $("stepUpdate");
  const tradeEl = $("stepTrade");

  const statHtml = (label, val) =>
    `<div class="stat"><span class="stat-label">${label}</span><span class="stat-val">${val}</span></div>`;

  if (snap.failed) {
    step.textContent = "Stopped";
    title.textContent = "Challenge failed";
    text.textContent = snap.statusLabel + ". Do not keep trading this account.";
    stats.innerHTML = statHtml("Equity", fmt(snap.equity)) + statHtml("Floor", fmt(snap.floorOverall));
    return;
  }
  if (snap.passed) {
    step.textContent = "Done";
    title.textContent = "You passed";
    text.textContent = "Target and min days are met. Stop trading this challenge account.";
    stats.innerHTML = statHtml("Balance", fmt(snap.closed)) + statHtml("Days", snap.tradingDays + " / " + snap.minTradingDays);
    return;
  }
  if (today.length > 0) {
    const pnl = today.reduce((a, t) => a + (Number(t.pnl) || 0), 0);
    step.textContent = "Done for today";
    title.textContent = pnl < 0 ? "Take the L — stop" : "Win banked — stop";
    text.textContent =
      "You already used today’s one trade (" +
      fmt(pnl) +
      "). Do not open another order on Pivex. Come back after 00:00 UTC.";
    stats.innerHTML =
      statHtml("Today", fmt(pnl)) +
      statHtml("Still need", fmt(snap.toTarget)) +
      statHtml("Safe today", fmt(snap.roomDaily)) +
      statHtml("Days", snap.tradingDays + " / " + snap.minTradingDays);
    if (updateEl) updateEl.style.opacity = "0.55";
    if (tradeEl) tradeEl.style.opacity = "0.55";
    const saveBtn = $("saveTodayLossBtn");
    if (saveBtn) {
      saveBtn.disabled = true;
      saveBtn.textContent = "Already saved — stop for today";
    }
    return;
  }

  if (updateEl) updateEl.style.opacity = "1";
  if (tradeEl) tradeEl.style.opacity = "1";

  if (!sized.allowed) {
    step.textContent = "Wait";
    title.textContent = "Do not trade right now";
    text.textContent = plainReason(sized.reason) + " If you already closed a trade today, save it in step 1 first.";
    stats.innerHTML =
      statHtml("Still need", fmt(snap.toTarget)) +
      statHtml("Weekdays left", String(plan.tradingDaysLeft));
    return;
  }

  if (lastPick && !lastPick.sitOut && lastPick.pick) {
    const p = lastPick.pick;
    step.textContent = "Trade now";
    title.textContent = p.action + " " + p.lotsLabel + " " + p.instrument;
    text.textContent =
      "On Pivex: set Volume to " +
      p.lotsLabel +
      " lots (not 10). Type SL " +
      p.stopLabel +
      " and TP " +
      p.tpLabel +
      ". Tap " +
      p.action +
      " once. Then come back and save the result.";
    stats.innerHTML =
      statHtml("Volume", p.lotsLabel + " lots") +
      statHtml("Risk", fmt(p.riskAmount)) +
      statHtml("Stop", p.stopLabel) +
      statHtml("Take profit", p.tpLabel);
    return;
  }

  step.textContent = "Wait for a setup";
  title.textContent = "No good trade yet";
  text.textContent =
    "Keep Pivex open, but do not force a trade. Tap “Check for a trade” every 30–60 minutes. " +
    (session.anytime ? "Anytime mode is on." : "Best window is 12:00–16:00 UTC.");
  stats.innerHTML =
    statHtml("Still need", fmt(snap.toTarget)) +
    statHtml("Ready risk", sized.allowed ? fmt(sized.riskAmount) : "$0") +
    statHtml("Weekdays left", String(plan.tradingDaysLeft)) +
    statHtml("Wins needed", isFinite(plan.winsNeeded) ? String(plan.winsNeeded) : "—");
}

function plainReason(reason) {
  const r = String(reason || "");
  if (/US data window/i.test(r)) return "News time — sit out for a few minutes.";
  if (/12:00–16:00|Strict mode only/i.test(r)) return "Outside the safe trading hours.";
  if (/Weekend/i.test(r)) return "Markets are closed for the weekend.";
  if (/already has a fill|one ticket/i.test(r)) return "You already traded today.";
  if (/losses in a row/i.test(r)) return "Too many losses in a row — take a break today.";
  if (/daily reset|00:00/i.test(r)) return "Too close to the daily reset — don’t hold a trade overnight into the reset.";
  return r || "Safety lock is on.";
}

function renderDayLock(snap, sized) {
  const box = $("dayLockBanner");
  const today = R.todayTrades(state.trades);
  if (today.length > 0) {
    const pnl = today.reduce((a, t) => a + (Number(t.pnl) || 0), 0);
    box.hidden = false;
    box.className = "day-lock" + (pnl >= 0 ? " ok" : "");
    box.innerHTML =
      `<b>Stop trading for today</b>` +
      `<span>Saved result: ${fmt(pnl)}. Wait until tomorrow (after 00:00 UTC).</span>`;
    return;
  }
  box.hidden = true;
}

function renderSprint(plan, session) {
  $("sprintToTarget").textContent = fmt(plan.toTarget);
  $("sprintDays").textContent = String(plan.tradingDaysLeft);
  $("sprintWins").textContent = isFinite(plan.winsNeeded) ? String(plan.winsNeeded) : "—";
  $("sprintAvg").textContent = fmt(plan.avgPerTradingDay);
  $("sprintCopy").textContent = plan.summary + " · " + plan.paceLabel;
  $("sprintPill").textContent = plan.paceLabel;
  $("sprintPill").className =
    "pill " + (plan.paceLabel.includes("Comfortable") || plan.paceLabel.includes("Done") ? "ok" : "warn");

  const chip = $("sessionChip");
  if (session.anytime && session.phase === "open") {
    chip.textContent = "Anytime open";
    chip.className = "chip open";
  } else if (session.phase === "open") {
    chip.textContent = "Overlap open";
    chip.className = "chip open";
  } else if (session.phase === "news") {
    chip.textContent = "News blackout";
    chip.className = "chip news";
  } else if (session.phase === "weekend") {
    chip.textContent = "Weekend";
    chip.className = "chip closed";
  } else if (session.phase === "pre") {
    chip.textContent = "Pre-overlap";
    chip.className = "chip news";
  } else {
    chip.textContent = session.anytime ? "Session closed" : "Session closed";
    chip.className = "chip closed";
  }
  const pct = Math.max(0, Math.min(100, session.progress * 100));
  $("sessionFill").style.width = pct + "%";
}

function renderClock(snap) {
  $("utcNow").textContent = "UTC " + snap.now.toISOString().slice(0, 16).replace("T", " ");
  $("utcReset").textContent = "Daily reset in " + fmtCountdown(snap.msUntilReset);
}

function renderRules(snap) {
  const rows = [
    {
      label: "Closed balance vs target",
      meta: fmt(snap.closed) + " / " + fmt(snap.targetBalance),
      kind: snap.targetHit ? "ok" : "wait",
      mark: snap.targetHit ? "HIT" : "OPEN",
    },
    {
      label: "Minimum trading days",
      meta: snap.tradingDays + " / " + snap.minTradingDays + " UTC days with a fill",
      kind: snap.daysMet ? "ok" : "wait",
      mark: snap.daysMet ? "MET" : "NEED " + snap.daysRemaining,
    },
    {
      label: "Daily drawdown",
      meta: "Floor " + fmt(snap.floorDaily) + " · SOD " + fmt(snap.dailyStart),
      kind: snap.breachedDaily ? "bad" : "ok",
      mark: snap.breachedDaily ? "BREACH" : "CLEAR",
    },
    {
      label: "Overall static drawdown",
      meta: "Equity must stay above " + fmt(snap.floorOverall),
      kind: snap.breachedOverall ? "bad" : "ok",
      mark: snap.breachedOverall ? "BREACH" : "CLEAR",
    },
    {
      label: "50% consistency",
      meta: snap.consistencyBestDay
        ? "Best day " +
          fmt(snap.consistencyBestDay) +
          " · " +
          (snap.consistencyShare * 100).toFixed(0) +
          "% · max add today ~" +
          (isFinite(snap.consistencyMaxAddToday) ? fmt(snap.consistencyMaxAddToday) : "n/a")
        : "No closed profit yet — first winners look large until diluted",
      kind: snap.targetHit && snap.daysMet && !snap.consistencyOk ? "wait" : "ok",
      mark: snap.consistencyOk ? "OK" : "NEED MORE DAYS",
    },
  ];
  $("ruleChecks").innerHTML = rows
    .map(
      (r) =>
        `<div class="check"><div>${escapeHtml(r.label)}<span class="meta">${escapeHtml(
          r.meta
        )}</span></div><span class="${r.kind}">${escapeHtml(r.mark)}</span></div>`
    )
    .join("");
}

function renderPassPlan(plan) {
  if (!plan) return "";
  return (
    `<div class="pass-plan"><b>Pass plan</b> — ${escapeHtml(plan.summary)}` +
    `<div class="meta">Logged today: ${plan.todayTrades} · consecutive losses: ${plan.consecutiveLosses}` +
    ` · if win ${fmt(plan.ifWin)} · if stop ${fmt(plan.ifLose)}</div></div>`
  );
}

function renderBot(sized, snap, plan) {
  const box = $("botPlan");
  if (snap.failed) {
    box.innerHTML =
      `<div class="r-main">Locked</div><div class="r-sub">Challenge failed. Bot will not size a new trade.</div>` +
      `<div class="warn">${escapeHtml(snap.statusLabel)}</div>`;
    return;
  }
  if (snap.passed) {
    box.innerHTML =
      `<div class="r-main">Passed</div>` +
      `<div class="r-sub">Target and min days met. Do not give back buffer — overall floor stays $94,000 static.</div>`;
    return;
  }
  if (!sized.allowed) {
    const extra = (sized.guard?.reasons || [])
      .slice(1)
      .map((r) => "<br>" + escapeHtml(r))
      .join("");
    box.innerHTML =
      `<div class="r-main">No trade</div>` +
      `<div class="r-sub">${escapeHtml(sized.reason || "")}${extra}</div>` +
      renderPassPlan(plan);
    return;
  }
  const half = sized.guard && sized.guard.riskMult < 1;
  const capNote = sized.capped
    ? half
      ? "Half size after two losses in a row."
      : `Capped by remaining ${sized.binding} room after the ${state.settings.buffer}% buffer.`
    : `Fits inside remaining ${sized.binding} room at ${half ? "half of " : ""}${state.settings.risk}% risk.`;
  const daysNote = snap.targetHit
    ? `Profit target hit. Small trades on ${snap.daysRemaining} more UTC day(s) without touching floors.`
    : `Need ${fmt(snap.toTarget)} more · ~${sized.rToTarget.toFixed(1)}R at this size · expected win ${fmt(
        sized.expectedWin || 0
      )}.`;
  box.innerHTML =
    `<div class="r-main">${fmt(sized.riskAmount)} · ${fmtPct(sized.riskPct)}</div>` +
    `<div class="r-sub">Max stop this ticket. One fill per UTC day — then log it and stop. ${capNote}<br>${daysNote}</div>` +
    renderPassPlan(plan);
}

function drawGauge(snap, s) {
  const svg = $("gaugeSvg");
  const lo = -s.overallDD;
  const hi = s.target;
  const currentPct = ((snap.equity - s.balance) / s.balance) * 100;
  const dailyFloorPct = ((snap.floorDaily - s.balance) / s.balance) * 100;
  const W = 320;
  const trackY = 24;
  const trackH = 8;
  const padX = 6;
  const scale = (v) => padX + ((v - lo) / (hi - lo)) * (W - padX * 2);
  const zeroX = scale(0);
  const lowX = scale(lo);
  const highX = scale(hi);
  const curX = Math.max(lowX, Math.min(highX, scale(Math.max(lo, Math.min(hi, currentPct)))));
  const dailyFloorX = scale(Math.max(lo, Math.min(hi, dailyFloorPct)));
  let ticks = "";
  for (let t = lo; t <= hi; t += 2) {
    const x = scale(t);
    const major = t % 4 === 0;
    ticks += `<line x1="${x}" y1="${trackY - 2}" x2="${x}" y2="${trackY + trackH + (major ? 4 : 1)}" stroke="#2E3A52" stroke-width="1"/>`;
  }
  svg.innerHTML = `
    <rect x="${lowX}" y="${trackY}" width="${zeroX - lowX}" height="${trackH}" fill="#E5736C" opacity="0.35" rx="2"/>
    <rect x="${zeroX}" y="${trackY}" width="${highX - zeroX}" height="${trackH}" fill="#E0A45A" opacity="0.22" rx="2"/>
    ${ticks}
    <line x1="${zeroX}" y1="${trackY - 5}" x2="${zeroX}" y2="${trackY + trackH + 5}" stroke="#8B9BB4" stroke-width="1.5"/>
    <line x1="${dailyFloorX}" y1="${trackY - 3}" x2="${dailyFloorX}" y2="${trackY + trackH + 3}" stroke="#E8EEF7" stroke-width="1" stroke-dasharray="2,2"/>
    <circle cx="${curX}" cy="${trackY + trackH / 2}" r="6" fill="#E8EEF7" stroke="#12161F" stroke-width="1.5"/>
  `;
}

function drawCurve() {
  const svg = $("equityCurve");
  const series = R.equitySeries(state.settings, state.trades);
  if (series.length < 2) {
    svg.innerHTML = `<text x="16" y="64" fill="#8B9BB4" font-family="IBM Plex Mono" font-size="11">Equity curve appears after your first logged fill.</text>`;
    return;
  }
  const W = 320;
  const H = 120;
  const pad = 12;
  const vals = series.map((p) => p.equity);
  const min = Math.min(...vals, state.settings.balance - state.settings.balance * 0.06);
  const max = Math.max(...vals, state.settings.balance + state.settings.balance * 0.1);
  const x = (i) => pad + (i / (series.length - 1)) * (W - pad * 2);
  const y = (v) => H - pad - ((v - min) / (max - min || 1)) * (H - pad * 2);
  let d = "";
  series.forEach((p, i) => {
    d += (i === 0 ? "M" : "L") + x(i).toFixed(1) + " " + y(p.equity).toFixed(1) + " ";
  });
  const floorY = y(state.settings.balance * (1 - state.settings.overallDD / 100));
  const targetY = y(state.settings.balance * (1 + state.settings.target / 100));
  svg.innerHTML = `
    <line x1="${pad}" y1="${floorY}" x2="${W - pad}" y2="${floorY}" stroke="#E5736C" stroke-dasharray="3,3" opacity="0.7"/>
    <line x1="${pad}" y1="${targetY}" x2="${W - pad}" y2="${targetY}" stroke="#6FCFB0" stroke-dasharray="3,3" opacity="0.7"/>
    <path d="${d}" fill="none" stroke="#E0A45A" stroke-width="2.2" stroke-linejoin="round"/>
  `;
}

function renderJournal() {
  const list = $("journalList");
  if (!state.trades.length) {
    list.innerHTML = `<div class="empty">No trades logged yet. Your first entry starts the ledger.</div>`;
    return;
  }
  const sorted = [...state.trades].sort((a, b) => b.id - a.id);
  list.innerHTML = sorted
    .map((t) => {
      const pnlClass = t.pnl >= 0 ? "pos" : "neg";
      const rMult = t.risk && t.risk > 0 ? " · " + (t.pnl / t.risk).toFixed(1) + "R" : "";
      return `<div class="j-row">
        <div>
          <div class="j-instr">${escapeHtml(t.instrument || "—")} <span class="j-meta">${escapeHtml(
            t.direction
          )}</span></div>
          <div class="j-meta">${escapeHtml(t.date)}${rMult}${
            t.notes ? " · " + escapeHtml(t.notes) : ""
          }</div>
        </div>
        <div class="j-right">
          <span class="j-pnl ${pnlClass}">${fmt(t.pnl)}</span>
          <button class="j-del" data-id="${t.id}" type="button" title="Delete">✕</button>
        </div>
      </div>`;
    })
    .join("");
  list.querySelectorAll(".j-del").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const id = Number(btn.getAttribute("data-id"));
      state.trades = state.trades.filter((t) => t.id !== id);
      await persist();
      render();
      fetchPicks();
    });
  });
}

function fieldClass(label, value) {
  const v = String(value || "");
  if (/Then tap/i.test(label) && /BUY/.test(v) && !/NEITHER/.test(v)) return "tap-buy";
  if (/Then tap/i.test(label) && /SELL/.test(v) && !/NEITHER/.test(v)) return "tap-sell";
  if (/NEITHER|Do not|SIT/.test(v)) return "tap-none";
  return "";
}

function renderFields(fields) {
  if (!fields?.length) return "";
  return (
    `<div class="field-list">` +
    fields
      .map((f) => {
        const cls = fieldClass(f.label, f.value);
        return `<div class="field"><div class="flabel">${escapeHtml(f.label)}</div><div class="fval${
          cls ? " " + cls : ""
        }">${escapeHtml(String(f.value))}</div></div>`;
      })
      .join("") +
    `</div>`
  );
}

function renderPick() {
  const pill = $("pickPill");
  const body = $("pickBody");
  if (!lastPick) return;
  if (lastPick.sitOut) {
    pill.textContent = lastPick.status === "passed" ? "Passed" : "Wait";
    pill.className = "pill " + (lastPick.status === "passed" ? "passed" : "need-days");
    body.innerHTML =
      `<div class="result" style="margin-top:0"><div class="r-main">${escapeHtml(
        lastPick.status === "passed" ? "You passed" : "No trade — wait"
      )}</div><div class="r-sub">${escapeHtml(
        plainReason(lastPick.detail) || lastPick.detail || ""
      )}</div></div>` +
      `<p class="note">Do not force a setup. Check again in 30–60 minutes. If you already closed a Pivex trade today, save it in step 1.</p>`;
    renderCoach(snapNow(), sizedNow(), planNow(), R.sessionClock(new Date(), state.settings));
    return;
  }
  const p = lastPick.pick;
  pill.textContent = "Take this";
  pill.className = "pill passed";
  const slSide = p.action === "BUY" ? "below the live price" : "above the live price";
  const tpSide = p.action === "BUY" ? "above the live price" : "below the live price";
  body.innerHTML =
    `<div class="ticket">` +
    `<div class="ticket-action${p.action === "SELL" ? " sell" : ""}">${escapeHtml(p.action)} ${escapeHtml(
      p.instrument
    )}</div>` +
    `<p class="lots-hero">Type volume <span class="lots-num">${escapeHtml(p.lotsLabel)}</span></p>` +
    `<div class="volume-warn">On Pivex, change Volume to <b>${escapeHtml(
      p.lotsLabel
    )}</b>. If you still see 10.xx, change it before you tap.</div>` +
    `<div class="field-list">` +
    `<div class="field"><div class="flabel">1. Pair</div><div class="fval">${escapeHtml(p.instrument)}</div></div>` +
    `<div class="field"><div class="flabel">2. Volume</div><div class="fval">${escapeHtml(p.lotsLabel)} lots</div></div>` +
    `<div class="field"><div class="flabel">3. Stop Loss price</div><div class="fval">${escapeHtml(p.stopLabel)}</div></div>` +
    `<div class="field"><div class="flabel">4. Take Profit price</div><div class="fval">${escapeHtml(p.tpLabel)}</div></div>` +
    `<div class="field"><div class="flabel">5. Then tap</div><div class="fval ${
      p.action === "BUY" ? "tap-buy" : "tap-sell"
    }">${escapeHtml(p.action)} once</div></div>` +
    `</div>` +
    `<ol class="steps">` +
    `<li>Open <b>${escapeHtml(p.instrument)}</b> on Pivex.</li>` +
    `<li>Choose <b>Market</b>.</li>` +
    `<li>Set Volume to <b>${escapeHtml(p.lotsLabel)}</b> (not 10).</li>` +
    `<li>Turn Stop Loss <b>ON</b> → Price → type <b>${escapeHtml(p.stopLabel)}</b> (${slSide}).</li>` +
    `<li>Turn Take Profit <b>ON</b> → Price → type <b>${escapeHtml(p.tpLabel)}</b> (${tpSide}).</li>` +
    `<li>Check risk is about <b>${fmt(p.riskAmount)}</b>, then tap <b>${escapeHtml(p.action)}</b> once.</li>` +
    `<li>When it closes, come back here and save the profit/loss in step 1.</li>` +
    `</ol></div>`;
  applyTicketToForm(p);
  renderCoach(snapNow(), sizedNow(), planNow(), R.sessionClock(new Date(), state.settings));
}

function applyTicketToForm(p) {
  $("calcEntry").value = p.entry;
  $("calcStop").value = p.stop;
  $("jInstr").value = p.instrument;
  $("jDir").value = p.direction;
  $("jRisk").value = Math.round(p.riskAmount);
}

async function fetchPicks(opts = {}) {
  if (scanning && !opts.force) return;
  scanning = true;
  const pill = $("pickPill");
  const body = $("pickBody");
  pill.textContent = "Scanning…";
  pill.className = "pill in-progress";
  try {
    const res = await fetch("/api/picks", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        settings: state.settings,
        trades: state.trades,
        floatingPnl: state.floatingPnl,
        exclude: state.skipped || [],
        refresh: opts.refresh !== false,
      }),
    });
    if (!res.ok) throw new Error("Scanner HTTP " + res.status);
    lastPick = await res.json();
    render();
    renderPick();
  } catch (e) {
    lastPick = null;
    pill.textContent = "Bot offline";
    pill.className = "pill failed";
    body.innerHTML = `<div class="warn">Scanner request failed (${escapeHtml(
      e.message
    )}). Run <b>npm start</b> in pivex-pass, or deploy the Worker. Sit out rather than guessing a ticket.</div>`;
  }
  scanning = false;
}

$("settingsToggle").addEventListener("click", () => {
  const body = $("settingsBody");
  const open = body.hasAttribute("hidden");
  if (open) body.removeAttribute("hidden");
  else body.setAttribute("hidden", "");
  $("settingsToggle").setAttribute("aria-expanded", open ? "true" : "false");
  $("settingsToggle").querySelector(".arrow").style.transform = open ? "rotate(180deg)" : "";
});

$("moreToggle").addEventListener("click", () => {
  const body = $("moreBody");
  const open = body.hasAttribute("hidden");
  if (open) body.removeAttribute("hidden");
  else body.setAttribute("hidden", "");
  $("moreToggle").setAttribute("aria-expanded", open ? "true" : "false");
  $("moreToggle").querySelector(".arrow").style.transform = open ? "rotate(180deg)" : "";
});

$("saveSettings").addEventListener("click", async () => {
  state.settings = {
    ...DEFAULTS,
    balance: parseFloat($("setBalance").value) || DEFAULTS.balance,
    dailyDD: parseFloat($("setDaily").value) || DEFAULTS.dailyDD,
    overallDD: parseFloat($("setOverall").value) || DEFAULTS.overallDD,
    target: parseFloat($("setTarget").value) || DEFAULTS.target,
    risk: parseFloat($("setRisk").value) || DEFAULTS.risk,
    buffer: parseFloat($("setBuffer").value),
    rewardR: parseFloat($("setReward").value) || DEFAULTS.rewardR,
    sprintDays: parseInt($("setSprint").value, 10) || DEFAULTS.sprintDays,
    sessionMode: $("setSessionMode").value === "anytime" ? "anytime" : "strict",
  };
  if (!isFinite(state.settings.buffer)) state.settings.buffer = DEFAULTS.buffer;
  await persist();
  render();
  fetchPicks({ force: true });
});

$("guardOn").addEventListener("change", async (e) => {
  state.guardOn = e.target.checked;
  await persist();
  render();
});

$("floatingPnl").addEventListener("input", async (e) => {
  state.floatingPnl = parseFloat(e.target.value) || 0;
  await persist();
  render();
});

$("calcBtn").addEventListener("click", () => {
  const entry = parseFloat($("calcEntry").value);
  const stop = parseFloat($("calcStop").value);
  const instr = ($("jInstr").value || "EURUSD").toUpperCase();
  const resultBox = $("calcResult");
  const snap = snapNow();
  const sized = sizedNow();
  let riskPct = parseFloat($("calcRisk").value) || state.settings.risk;
  let riskAmount = snap.equity * (riskPct / 100);
  let warn = "";

  if (state.guardOn) {
    if (!sized.allowed) {
      resultBox.hidden = false;
      resultBox.innerHTML = `<div class="warn">${escapeHtml(sized.reason || "Bot will not size this trade.")}</div>`;
      return;
    }
    if (riskAmount > sized.riskAmount + 0.01) {
      warn = `<div class="note">Guard cut this from ${fmt(riskAmount)} to ${fmt(
        sized.riskAmount
      )} so a full stop cannot print through remaining ${sized.binding} room.</div>`;
      riskAmount = sized.riskAmount;
      riskPct = sized.riskPct;
    }
  }

  let html = "";
  if (pairInfo(instr)) {
    const lots = sizeLots(instr, entry, stop, riskAmount, entry, {
      minStopPips: state.settings.minStopPips ?? 10,
      maxLots: state.settings.maxLots ?? 2,
    });
    if (!lots.ok) {
      resultBox.hidden = false;
      resultBox.innerHTML = `<div class="warn">${escapeHtml(lots.error)}</div>`;
      return;
    }
    const capNote = lots.cappedByMaxLots
      ? `<div class="warn">Capped at ${lots.maxLots} lots (pass-mode hard max). Do not type 10.xx on Pivex.</div>`
      : `<div class="note">Pass max is ${lots.maxLots} lots. Overwrite any leftover volume on Pivex.</div>`;
    html = `<div class="r-main">${lots.lotsLabel} lots · ${lots.action} ${lots.instrument}</div>
      <div class="r-sub">Risking ${fmt(lots.actualRisk)} across ${lots.pips} pips (pip ≈ $${lots.pipValue}). SL ${formatPrice(
      instr,
      stop
    )}. Max leverage 1:${state.settings.leverage}. One trade only.</div>${capNote}${warn}`;
    $("jRisk").value = Math.round(lots.actualRisk);
    $("jDir").value = lots.direction;
  } else {
    const pos = R.sizePosition(entry, stop, riskAmount);
    if (!pos.ok) {
      resultBox.hidden = false;
      resultBox.innerHTML = `<div class="warn">${escapeHtml(pos.error)}</div>`;
      return;
    }
    html = `<div class="r-main">${pos.units.toLocaleString(undefined, {
      maximumFractionDigits: 2,
    })} units · ${pos.direction}</div>
      <div class="r-sub">Risking ${fmt(riskAmount)} (${fmtPct(riskPct)}). For FX majors, set instrument to EURUSD etc. for lot sizing.</div>${warn}`;
    $("jRisk").value = Math.round(riskAmount);
    $("jDir").value = pos.direction;
  }
  resultBox.hidden = false;
  resultBox.innerHTML = html;
});

$("addTrade").addEventListener("click", async () => {
  const instrument = $("jInstr").value.trim();
  const direction = $("jDir").value;
  const pnl = parseFloat($("jPnl").value);
  const risk = parseFloat($("jRisk").value) || null;
  const notes = $("jNotes").value.trim();
  if (isNaN(pnl)) {
    alert("Enter a P&L amount for this trade.");
    return;
  }
  const ok = await commitTrade({ instrument, direction, pnl, risk, notes });
  if (!ok) return;
  $("jInstr").value = "";
  $("jPnl").value = "";
  $("jRisk").value = "";
  $("jNotes").value = "";
});

async function commitTrade({ instrument, direction, pnl, risk = null, notes = "", date = null }) {
  const tradeDate = date || R.utcDateStr();
  const preview = R.previewTrade(state.settings, state.trades, pnl, state.floatingPnl);
  if (preview.failed) {
    const go = confirm(
      "This result would fail the challenge (" +
        preview.statusLabel +
        ").\n\nEquity would print " +
        fmt(preview.equity) +
        " vs daily " +
        fmt(preview.floorDaily) +
        " / overall " +
        fmt(preview.floorOverall) +
        ".\n\nLog it anyway?"
    );
    if (!go) return false;
  }
  if (tradeDate === R.utcDateStr()) {
    const todayCount = R.todayTrades(state.trades).length;
    if (todayCount >= (state.settings.maxTradesPerDay || 1)) {
      const go = confirm(
        "You already logged a fill today. Logging another keeps history accurate but you must NOT send another order on Pivex.\n\nLog it anyway?"
      );
      if (!go) return false;
    }
  }
  state.trades.push({
    id: Date.now(),
    date: tradeDate,
    instrument: instrument || "Unnamed",
    direction: direction || "Long",
    pnl: Number(pnl),
    risk,
    notes,
  });
  state.floatingPnl = 0;
  if ($("floatingPnl")) $("floatingPnl").value = 0;
  await persist();
  render();
  fetchPicks({ force: true });
  return true;
}

$("quickLogBtn").addEventListener("click", async () => {
  const pnl = parseFloat($("quickPnl").value);
  if (isNaN(pnl)) {
    alert("Enter the closed P&L from Pivex (e.g. -441.32).");
    return;
  }
  const ok = await commitTrade({
    instrument: ($("quickInstr").value || "GBPUSD").trim(),
    direction: $("quickDir").value,
    pnl,
    risk: Math.abs(pnl),
    notes: "quick log",
  });
  if (ok) {
    $("quickPnl").value = "";
    $("pivexEquity").value = "";
    $("syncPill").textContent = "Saved";
    const btn = $("saveTodayLossBtn");
    if (btn) {
      btn.disabled = true;
      btn.textContent = "Saved — stop for today";
    }
    alert("Saved. Do not take another trade today.");
  }
});

$("saveTodayLossBtn")?.addEventListener("click", async () => {
  const already = state.trades.some(
    (t) =>
      Math.abs((Number(t.pnl) || 0) + 441.32) < 0.02 &&
      String(t.instrument || "").toUpperCase().includes("GBP")
  );
  if (already) {
    // If it exists but on the wrong day, repair and unlock
    const repaired = repairMisdatedSep9Loss();
    if (repaired) {
      await persist();
      render();
      fetchPicks({ force: true });
      alert("Fixed: that −$441.32 is now dated Sep 9 (yesterday). Today is unlocked — one new ticket allowed.");
      return;
    }
    alert("That Sep 9 loss is already saved. Today is a new day — tap Check for a trade.");
    return;
  }
  const ok = await commitTrade({
    instrument: "GBPUSD",
    direction: "Long",
    pnl: -441.32,
    risk: 441.32,
    date: "2026-09-09",
    notes: "closed 2026-09-09 13:23 UTC · open 1.35525 · close 1.35491",
  });
  if (ok) {
    const btn = $("saveTodayLossBtn");
    btn.disabled = true;
    btn.textContent = "Sep 9 loss saved";
    $("syncPill").textContent = "Saved −$441.32 on Sep 9";
    $("quickPnl").value = "";
    alert("Saved as yesterday (Sep 9). Today is unlocked — one new ticket allowed.");
  }
});

$("fixYesterdayBtn")?.addEventListener("click", async () => {
  const repaired = repairMisdatedSep9Loss();
  // Also: if today has any -441.32 alone, force to Sep 9
  let moved = repaired;
  const today = R.utcDateStr();
  for (const t of state.trades) {
    if (t.date === today && Math.abs((Number(t.pnl) || 0) + 441.32) < 0.02) {
      t.date = "2026-09-09";
      moved = true;
    }
  }
  if (!moved) {
    alert("Nothing to fix — or add the −$441.32 with the button above first.");
    return;
  }
  await persist();
  render();
  fetchPicks({ force: true });
  alert("Fixed. Yesterday’s loss is on Sep 9. You can take one trade today.");
});

$("syncEquityBtn").addEventListener("click", async () => {
  const equity = parseFloat($("pivexEquity").value);
  if (!isFinite(equity) || equity <= 0) {
    alert("Paste closed equity from Pivex (header Equity with no open positions).");
    return;
  }
  const current = R.closedEquity(state.settings, state.trades);
  const delta = R.money(equity - current);
  if (Math.abs(delta) < 0.01) {
    $("syncPill").textContent = "Already matched";
    alert("Ledger already matches that equity.");
    return;
  }
  const ok = await commitTrade({
    instrument: ($("quickInstr").value || "SYNC").trim(),
    direction: delta >= 0 ? "Long" : "Short",
    pnl: delta,
    risk: Math.abs(delta),
    notes: "synced from Pivex equity " + equity,
  });
  if (ok) {
    $("syncPill").textContent = "Synced " + fmt(delta);
    $("quickPnl").value = "";
  }
});

$("scanBtn").addEventListener("click", () => fetchPicks({ refresh: true, force: true }));
$("skipBtn").addEventListener("click", async () => {
  const name = lastPick?.pick?.instrument;
  if (name && !state.skipped.includes(name)) state.skipped.push(name);
  state.floatingPnl = 0;
  $("floatingPnl").value = 0;
  await persist();
  render();
  fetchPicks({ refresh: true, force: true });
});
$("resetOpenBtn").addEventListener("click", async () => {
  state.floatingPnl = 0;
  $("floatingPnl").value = 0;
  await persist();
  render();
  fetchPicks({ refresh: true, force: true });
});
$("clearAllBtn").addEventListener("click", async () => {
  if (!confirm("Reset all ledger data, settings, and skips?")) return;
  scanning = false;
  lastPick = null;
  state = {
    settings: { ...DEFAULTS },
    trades: [],
    floatingPnl: 0,
    guardOn: true,
    skipped: [],
    sprintStarted: R.utcDateStr(),
  };
  await storageRemove(STORAGE_KEY);
  await persist();
  hydrateSettings();
  $("floatingPnl").value = 0;
  $("guardOn").checked = true;
  ["calcEntry", "calcStop", "jInstr", "jPnl", "jRisk", "jNotes"].forEach((id) => {
    $(id).value = "";
  });
  $("calcResult").hidden = true;
  render();
  fetchPicks({ refresh: true, force: true });
});
$("copyTicketBtn").addEventListener("click", async () => {
  const text = lastPick?.pick?.copyText || lastPick?.copyText;
  if (!text) {
    alert("No ticket to copy. Scan first, or wait for an A-setup.");
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    $("copyTicketBtn").textContent = "Copied";
    setTimeout(() => {
      $("copyTicketBtn").textContent = "Copy ticket";
    }, 1500);
  } catch (_) {
    alert(text);
  }
});

$("exportBtn").addEventListener("click", () => {
  const blob = new Blob([JSON.stringify(state, null, 2)], { type: "application/json" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = "pivex-pass-" + R.utcDateStr() + ".json";
  a.click();
  URL.revokeObjectURL(a.href);
});

$("setImport").addEventListener("change", async (e) => {
  const file = e.target.files?.[0];
  if (!file) return;
  try {
    const parsed = JSON.parse(await file.text());
    state.settings = { ...DEFAULTS, ...(parsed.settings || {}) };
    state.trades = parsed.trades || [];
    state.floatingPnl = Number(parsed.floatingPnl) || 0;
    state.guardOn = parsed.guardOn !== false;
    state.skipped = Array.isArray(parsed.skipped) ? parsed.skipped : [];
    state.sprintStarted = parsed.sprintStarted || state.sprintStarted;
    await persist();
    hydrateSettings();
    $("floatingPnl").value = state.floatingPnl || 0;
    $("guardOn").checked = state.guardOn;
    render();
    fetchPicks({ force: true });
  } catch (err) {
    alert("Import failed: " + err.message);
  }
});

setInterval(() => {
  if (!loaded) return;
  renderClock(snapNow());
  renderSprint(planNow(), R.sessionClock(new Date(), state.settings));
}, 30000);

setInterval(() => {
  if (loaded) fetchPicks();
}, 5 * 60 * 1000);

loadState();
