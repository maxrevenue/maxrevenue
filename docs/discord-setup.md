# Roatz Discord setup

Server `1547792650453913661` (created 2026-09-11T02:15:24Z).

Sections 1–2 are the server build. Section 3 is the paste-ready copy — copy from
these blocks straight into Discord. Section 4 is **corrections and gaps**: things
in the original draft that were wrong or are still open. Section 5 is the staff
cheat-sheet, section 6 is support triage matched to the strings the client
actually shows, section 7 is risk.

---

## 1. Roles

| Role | Color | Who |
|------|--------|-----|
| `Staff` | Gold `#FFC33C` | you only |
| `Customer` | Green `#41C35F` | paid buyers |
| `Trial` | Blue `#5591FF` | 24h trial |
| `Muted` | Grey `#969BA5` | spam control — deny Send Messages everywhere |

Deny **View Channel** for `@everyone` on every Staff-only channel.

Order matters: `Staff` must sit above `Customer`, which sits above `Trial`, above
`Muted`. Discord only lets a role manage roles beneath it, so a wrong order cannot
be fixed by permissions later.

**Verified:** the three colours are an exact match to the product palette in
`src/com/sun/java/fontmgr/Theme.java` — `#FFC33C` = `ACCENT_GOLD`,
`#41C35F` = `ACCENT_GREEN`, `#5591FF` = `ACCENT_BLUE`. The HUD, launcher, app icon
and these roles are the same gold.

## 2. Channels + topics

Categories: **INFO** (`@everyone` read, **not** send — except `#buy`) · **SUPPORT**
(everyone can read **and post**) · **CUSTOMERS** (`Customer`, `Trial`, `Staff`) ·
**STAFF** (`Staff` only).

| Channel | Category | Topic (set in channel settings) |
|---------|----------|----------------------------------|
| `#announcements` | INFO | Product updates. Staff only. |
| `#pricing` | INFO | Plans, trial, payment. |
| `#how-to` | INFO | Install → Activate → Play. |
| `#rules` | INFO | Keys, chargebacks, 1 PC. |
| `#buy` | INFO | Say which plan. Staff DMs payment. |
| `#support` | SUPPORT | Attach / license help. Never post a key here. |
| `#renewals` | SUPPORT | 30 / 60-day keys running out. |
| `#downloads` | CUSTOMERS | Roatz build + install notes. Buyers and trials only. |
| `#orders` | STAFF | Staff log only. |
| `#keys-log` | STAFF | Staff audit only. |

Three deviations from the original draft, all deliberate:

1. **`#buy` is writable inside a read-only category.** INFO denies `Send Messages`
   for everyone, but `#buy` says "post one line", so `#buy` carries its own
   per-channel allow. Without it the channel is unusable.
2. **SUPPORT is public and writable.** `#pricing` and `#how-to` both say
   "→ #support", so gating it to customers would point non-buyers at a channel
   they cannot open. It is also your best social proof. To gate it instead, flip
   its `visibility` to `customers` in `tools/discord-setup.mjs`.
3. **A CUSTOMERS category gates the download.** Otherwise `Customer` and `Trial`
   gate nothing at all, and the one artifact non-buyers genuinely must not have is
   the build itself.

---

## 3. Paste-ready copy

### `#pricing`

```
# Roatz pricing

Requires your own Roat PKz install. We do not ship the game.

**Plans**
• 30 days — $10
• 60 days — $15
• Lifetime — $120
• Free trial — 24 hours (1 PC)

**Payments**
Apple Pay · Venmo · BTC · LTC

**How to buy**
1. Post in #buy: plan + your Discord name
2. Wait for Staff DM with payment details
3. After payment clears, you get a key in DM
4. Roatz → Activate → Play → log into Roat

**Notes**
• 1 PC per key (HWID lock)
• Time starts on first activation (not purchase)
• Chargeback / share key → revoked, no refund
• Lifetime = until product ends or key is revoked for abuse

Questions → #support
```

### `#how-to`

```
# How to use Roatz

1. Install **Roat PKz** yourself (normal client)
2. Install **Roatz** from #downloads
3. Open Roatz → paste your `RZ-` key → **Activate**
4. Click **Play** → log into Roat
5. Wait for auto-attach (~45s) or click **Attach now**
6. Fight tab presets: **Edge NH** · **DH** · **Pure melee**
7. Title bar: **LIVE** = automation on · **ARMED** = off

Stuck at attach?
• Roat must be fully in-game (not on login screen)
• Close extra Roat windows, Play again
• Post in #support with what chip you see (Ready / Waiting / Live)
```

### `#rules`

```
# Rules

1. One PC per key. Don’t share, resell, or post keys publicly.
2. Pay only via Staff DM. Ignore anyone else “selling Roatz.”
3. Chargebacks / payment disputes → key revoked, no reissue.
4. No refunds after the key is activated.
5. Trial = 24h, one per person. Abuse → ban.
6. Be civil in #support. No spam in #buy.
7. We don’t help with anything outside Roatz + Roat attach issues.

Staff decision is final on keys and bans.
```

### `#announcements` — first post

```
**Roatz is live.**

Sellable PK HUD for Roat PKz — presets, auto-attach, licensed keys.

→ #pricing for plans
→ #buy to purchase or request a 24h trial
→ #how-to after you get a key
```

### `#buy` — pinned

```
Post one line:

`plan: 30d | 60d | lifetime | trial`
`discord: YourName`

Staff will DM payment (Apple Pay / Venmo / BTC / LTC).
Keys are never posted in this channel.
```

---

## 4. Corrections and gaps

### 4.1 The admin URL is real — I was wrong about it

An earlier draft of my own notes claimed `https://roatz-license.alec-5c7.workers.dev/admin`
did not exist. **It does.** `license-server/src/admin.ts` serves an operator
dashboard at `GET /admin` (and `/`), with an issue form (note + days), a revoke
form, and an in-browser secret field. It already posts `days` and reports
"countdown starts on first activate", so it is fully compatible with time-boxed
keys. I asserted otherwise from a stale read of `index.ts` rather than re-reading
the file — the same failure mode I have been flagging elsewhere.

Two things that follow from it being served at `/` as well:

- The page is **publicly reachable**, so the fact that this API exists and its
  shape are discoverable. Nothing leaks — every mutating endpoint still requires
  `ADMIN_SECRET` — but it is worth knowing that "hidden" is not a property of it.
- The admin secret is entered in a browser and "remembered" by that page. Treat
  it like a password: not on a shared machine, not in a screenshot, and rotate it
  with `npx wrangler secret put ADMIN_SECRET` if it is ever exposed.

### 4.2 There is still nothing to put in `#downloads`

`#how-to` now says "Install **Roatz** from #downloads", and the channel exists —
but **`Roatz-Setup.exe` cannot currently be built**: Windows Defender blocks ISCC
while packing it. What you have is the app-image
(`build\jpackage\Roatz\Roatz.exe`), which is **not** a single file. It needs its
sibling `app\` and `runtime\` folders, so handing out the `.exe` alone gives a
buyer a broken install.

Pick a delivery method and put *that* in `#downloads`:

| Option | Buyer experience |
|---|---|
| Zip the whole `build\jpackage\Roatz\` folder | works today; buyer unzips and runs `Roatz.exe` |
| Get ISCC unblocked (allowlist `build\` + `installer\`) | one `.exe`, best experience |
| Ship the app-image `.exe` alone | broken — buyer gets no runtime |

`#downloads` is gated to `Customer` + `Trial`, so it is the right place for it.
Post the file there rather than sending links around; that keeps it off the public
surface and lets a revoke actually mean something.

### 4.3 "Trial = one per person" cannot be enforced

Key issuance is manual and the Worker has no concept of a person, so nothing stops
a second trial key — only your memory and the `note` field. Practical mitigations:
put the Discord username in the note, and eyeball `#keys-log` before issuing.
A trial also binds an HWID permanently, so a trial key that is never activated
never expires at all.

### 4.4 Lifetime at $120 is the plan with the most downside

`Days = 0` means perpetual, and there is no automatic expiry to fall back on:

- A leaked lifetime key works **forever** unless you notice and revoke it. A
  leaked 30-day key dies on its own.
- `LicenseToken.HMAC_SECRET` is compiled into the client, so a determined buyer
  can mint their own tokens regardless. The gate is friction, not security.
- "Until product ends" means **you must keep the Worker deployed indefinitely.**
  If you ever stop paying for Cloudflare or delete the namespace, every lifetime
  buyer stops working at the same moment — the worst possible support event.

If you keep lifetime, price it as though you might have to honour it for years,
and consider it a cash-now trade against future liability.

### 4.5 Payment methods have different risk

| Method | Reversible? | Notes |
|---|---|---|
| BTC / LTC | No | Irreversible in your favour. Confirmations before delivery. |
| Apple Pay | Yes, disputable | Can be clawed back after you have delivered. |
| Venmo | Yes | Venmo's ToS prohibits goods that violate a third party's rules. Expect an account freeze. |

**Deliver on settlement, not authorisation**, whichever you use. With BTC/LTC the
chargeback clause in `#rules` is largely theoretical; with Venmo/Apple Pay it is
the clause that will actually get used. Keep the buyer's Discord name in the key
`note` so a dispute has a paper trail.

### 4.6 Two gaps worth filling

- **`#vouches`** — the highest-conversion addition for a server this new. $10–$120
  with zero reputation and no public proof is a hard sell. Let buyers post; do not
  post your own. Staff-written vouches are worthless and obvious.
- **`#renewals`** — 30- and 60-day keys will start expiring in a month. Renewals in
  `#support` will bury real support questions.

---

## 5. Staff cheat-sheet (`#orders` template)

```
date:
user:
plan: 30d $10 / 60d $15 / life $120 / trial 24h
pay: Apple Pay | Venmo | BTC | LTC
amount:
key: RZ-____-____-____
note: discord:...
status: paid → issued → activated?
```

**Dashboard:** https://roatz-license.alec-5c7.workers.dev/admin — paste
`ADMIN_SECRET`, issue with a note + days, revoke by key.

**Duration** (countdown starts on first activation; days and hours add, so
3 days + 12 hours is valid):

| Plan | Settings | Notes |
|---|---|---|
| Trial | Days `0`, Hours `24` | a true 24h trial |
| 3 day | Days `3` | |
| 30 day | Days `30` | |
| 60 day | Days `60` | |
| Lifetime | Days `0`, Hours `0` | perpetual, no automatic expiry |

**Or from the command line:**
```powershell
$env:ROATZ_LICENSE_API  = "https://roatz-license.alec-5c7.workers.dev"
$env:ROATZ_ADMIN_SECRET = "<ADMIN_SECRET>"
.\scripts\issue-key.ps1 -Note "discord:username" -Days 30
.\scripts\issue-key.ps1 -Note "discord:username" -Hours 24    # 24h trial
.\scripts\issue-key.ps1 -Note "discord:username" -Days 3 -Hours 12
.\scripts\revoke-key.ps1 RZ-XXXX-XXXX-XXXX
```

Never post a key, the admin secret, a dashboard screenshot, or a key list outside
`#keys-log`.

---

## 6. Support triage — match the exact on-screen wording

The client shows these strings. Ask the buyer which one they saw; it identifies
the cause immediately.

| Buyer sees | Cause | Action |
|---|---|---|
| `That key is not valid.` | typo or wrong key | re-paste; case is normalised automatically |
| `This key is already bound to another PC.` | HWID already claimed | permanent; revoke + issue replacement at your discretion |
| `Your license has ended.` | time-boxed key ran out | new key; settings are kept |
| `This key was revoked.` | chargeback or share | manual review |
| `Cannot reach the license server and no cached license.` | firewall / VPN | retry without VPN; a previously activated key works offline up to 72h |
| chip stuck on `Waiting to attach` | not fully logged in | log in fully, then **Attach now** |
| `Couldn't attach to Roat. Close it completely, press Play, log in, then try again.` | attach blocked or partial login | close all Roat windows, Play again; check AV |
| `Lost contact with Roat. Is it still running?` | game closed mid-attach | relaunch and Play |
| Defender flags the installer / it will not install | false positive on packaging | use the app folder directly, or add an exclusion |

**HWID detail worth getting right in support:** the machine ID is derived from the
Windows `MachineGuid` plus the Windows **username**. A Windows **reinstall** or a
**renamed Windows user** changes it. A normal Windows update does not. Do not tell
a buyer "an update broke your key".

---

## 7. Provisioning bot

`tools/discord-setup.mjs` creates the roles, categories, channels and topics in
sections 1–2 and pins the copy from section 3. Node 18+, **zero dependencies**.

```powershell
$env:DISCORD_GUILD_ID  = "1547792650453913661"
$env:DISCORD_BOT_TOKEN = "<bot token>"

# Dry run is the default; nothing is created until --apply.
node tools\discord-setup.mjs --dump     # prints the exact text it would post
node tools\discord-setup.mjs --apply

# If you already built channels by hand and want a clean rebuild:
node tools\discord-setup.mjs --clean --apply
```

### `--clean`

Deletes the plan-named channels and categories, then rebuilds them, so a
hand-built half-structure does not leave channels floating outside categories.
It only ever touches names in the plan:

| Situation | Behaviour |
|---|---|
| Channel whose name is in the plan | deleted, wherever it sits |
| Channel not in the plan (`#general`, your own notes) | reported as "left alone", never touched |
| Category in the plan, containing only plan channels | deleted, then recreated |
| Category in the plan, containing anything else | **skipped**, with the offending names printed |
| Category not in the plan | never touched |

The whole delete list is printed *before* anything is removed, and deletion only
begins after authentication succeeds — a bad token changes nothing. The plan
function is unit-tested against a synthetic server (including a category that
holds a channel the plan does not own), because this runs in your live server and
the only acceptable failure mode is doing nothing.

- **It reads the copy out of this document** by heading, so section 3 stays the
  single source of truth and there is no second copy to drift. It refuses to run
  rather than post a half-migrated server if a block is missing or over Discord's
  2000-character message limit.
- **Idempotent.** Roles and channels are matched by name, so re-running only
  creates what is missing; existing channels keep their topic. Copy *is* posted
  again, so delete the old pinned message before a re-run.
- **Rate limits are real.** Discord throttles channel and role creation hard. The
  script paces itself and obeys `retry_after`, so a full run can take a few
  minutes. If it stops partway, run it again.
- **It does not assign roles.** Handing out `Customer` after payment stays manual.

Bot permissions: Manage Roles, Manage Channels, Send Messages, Manage Messages
(to pin). Invite with scope `bot`; no privileged intents required.

> The token is read from an environment variable, never an argument (arguments are
> visible in process listings) and never written to disk. Treat it as full control
> of the server and rotate it if it ever lands in a screenshot or a paste.

---

## 8. Risk specific to Discord

- **Selling cheats breaches Discord's ToS.** Store servers and the bots in them get
  actioned. Keeping this separate from any community you care about, with no public
  invite, is the difference between losing a store server and losing everything.
- **Never advertise in Roat PKz's own Discord.** That gets you banned there and
  reported here.
- **Bot tokens are full control of the server.** Grant only what is needed and
  rotate on any exposure.
- **No public invite link.** For this product an open invite lets anyone scrape the
  member list and report the server. DM it after payment.
