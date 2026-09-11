# OneClick Fortune

Cloudflare Worker for `https://oneclick-fortune.alec-5c7.workers.dev`.

## Squeeze lead capture

`/squeeze` posts to `POST /api/leads` which:

1. Validates name + email
2. Stores the lead in KV (`LEADS`)
3. Emails the setup map via Resend when `RESEND_API_KEY` is set
4. Always exposes the map at `/setup-map`

### Secrets

```bash
npx wrangler secret put RESEND_API_KEY
npx wrangler secret put ADMIN_SECRET
```

Optional wrangler vars: `CONTACT_FROM_EMAIL`, `ADMIN_EMAIL`.

### Admin list

```bash
curl -H "Authorization: Bearer $ADMIN_SECRET" \
  https://oneclick-fortune.alec-5c7.workers.dev/api/leads
```

### Deploy

```bash
npm install
npx wrangler deploy
```
