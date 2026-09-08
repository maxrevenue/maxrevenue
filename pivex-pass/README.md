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

| Rule | Value |
|------|-------|
| Target | +10% closed ($110,000) |
| Daily DD | 4% of SOD equity, **floating** |
| Overall DD | 6% static ($94,000 floor) |
| Min days | 5 UTC trading days |
| Consistency | No UTC day > 50% of total profit |
| Pass pairs | Majors listed above |
| Pass window | 12:00–16:00 UTC |

## 2-week path (example)

At ~0.75% risk (~$750) and 1.5R wins (~$1,125): you need roughly **9 winners** across ~10 weekday sessions. Skip anything that is not an A pullback. One revenge ticket is how these accounts die.
