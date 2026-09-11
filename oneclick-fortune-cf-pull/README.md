# oneclick-fortune Cloudflare pull

Pulled from account `5c799272b867b7b535e61023c0773713` (subdomain `alec-5c7`) for worker **oneclick-fortune**, focused on `/squeeze`.

## Live URL
- https://oneclick-fortune.alec-5c7.workers.dev/squeeze

## What Cloudflare has for this Worker
- **Type:** Workers Static Assets only (SPA)
- **Script modules / bindings / secrets:** none
- **Assets config:** `not_found_handling = single-page-application`, `serve_directly = true`
- **compatibility_date:** `2026-08-25`
- **Latest version:** `7bc7a1f6-f8c7-4f70-b5b4-e5b4510edc6d` (deployed 2026-09-07 by alec@room23.net)
- **Custom routes/domains:** none (workers.dev only)
- **Related R2/KV/D1 bindings:** none on this Worker

## `/squeeze` behavior
Client-side React Router page. Form fields: first name + email. Submit only navigates to `/magnet-thank-you` — **no API/lead capture on this Worker**.

Alias: `/opt-in` → `/squeeze`.

## Folder layout
- `meta/` — CF API settings, versions, deployments, summary
- `live/` — mirrored production HTML + hashed JS/CSS assets
- `reconstructed/` — readable Squeeze page reconstruction + routes + wrangler.toml stub

## Related (not on this Worker)
- Operator dashboard: https://operator-dashboard-peach.vercel.app
