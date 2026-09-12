# Pivex Pass · $100K command center

A stricter rebuild of the [original ledger](https://pivex-ledger.alec-5c7.workers.dev) aimed at finishing a Pivex $100K challenge without blowing the floors.

## Why this is different

- **2-week sprint clock** — weekday sessions left, wins needed at your R multiple, avg $/session
- **Working Yahoo FX scanner** — H1 EMA trend + M15 EMA20 pullback rejection on EURUSD / GBPUSD / USDJPY / AUDUSD / USDCAD
- **Real lot sizing** — pip value aware (incl. JPY/CAD), rounds to 0.01 lots
- **Tighter defaults** — 0.75% risk, 20% buffer, 1.5R targets (safer than flat 1%)
- **Consistency soft-cap** — sizes/TP aware of the 50% best-day rule
- **Pass guards** — one ticket/UTC day, overlap-only 12:00–16:00 UTC, news blackout 12:20–13:05, no late-float near midnight, half-size after 2 losses, sit after 3
- **Equity curve + import/export** — imports v1 `pivex-ledger-data` automatically if present

This cannot guarantee a pass. It can keep a full stop from failing the 4% daily / 6% overall floors if you type the ticket exactly and do not send a second order.

## Quick start (local)

```bash
cd pivex-pass
npm start
# open http://127.0.0.1:8787
```

```bash
npm test
```

## Deploy to Cloudflare Workers

```bash
cd pivex-pass
npx wrangler login
npx wrangler deploy
```

Uses Workers static assets (`public/`) + `/api/picks` + `/api/health`.

## Challenge math this encodes

Named constants in `src/constants.js` (no magic numbers in sizing/DD/lock):

| Constant | Value |
|----------|-------|
| `START_BALANCE` | $100,000 |
| `TARGET_BALANCE` | $110,000 (+10% closed) |
| `DAILY_DD_PCT` | 4% of **00:00 UTC SOD equity** (floating included) |
| `OVERALL_DD_FLOOR` | $94,000 static (never moves after recovery) |
| `MIN_TRADING_DAYS` | 5 UTC days with a logged fill |
| `CONSISTENCY_MAX_SHARE` | 50% best-day soft rule |
| `DEFAULT_RISK_PCT` | 0.75% (low end of 0.75–1%) |
| `MAX_LOTS` / `MIN_STOP_PIPS` | 2.00 lots / 10 pips |
| `DEFAULT_RR` / `BUFFER_PCT` | 1.8R / 15% headroom |

### Core “check for a trade” modules (`src/`)

1. **`equity.js`** — UTC SOD snapshot `{ utcDate, startOfDayEquity }`, `getDailyDDRoom`, `getOverallDDRoom`, open-ticket mark-to-market  
2. **`sizing.js`** — `calcTicket` (buffer, lot cap, min stop, **rejects if full SL would breach remaining daily DD**)  
3. **`scanner.js`** — Yahoo candles, H1 trend + M15 EMA20 pullback; `{ action: "wait" }` or ticket via `calcTicket`  
4. **`lock.js`** — logged fill locks the UTC day; override only via explicit `override: true` / `--override` / confirm dialog  
5. **`consistency.js`** — floating P&L warning: “Closing now may violate the 50% consistency rule — consider partial close.” (never auto-closes)  
6. **`checkTrade.js`** — end-to-end orchestration for `/api/check-trade`

No live Pivex auto-execution — output stays a ticket you type in manually.

## 2-week path (example)

At ~0.75% risk (~$750) and 1.5R wins (~$1,125): you need roughly **9 winners** across ~10 weekday sessions. Skip anything that is not an A pullback. One revenge ticket is how these accounts die.
