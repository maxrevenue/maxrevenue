#!/usr/bin/env node
/**
 * Provision the Roatz Discord server: roles, categories, channels, topics, and the
 * pinned buyer copy.
 *
 * Zero dependencies — plain fetch against the Discord REST API, so there is
 * nothing to install and nothing to keep updated.
 *
 * Usage (dry run is the DEFAULT — nothing is created until you pass --apply):
 *
 *   DISCORD_BOT_TOKEN=xxx DISCORD_GUILD_ID=1547792650453913661 node tools/discord-setup.mjs
 *   DISCORD_BOT_TOKEN=xxx DISCORD_GUILD_ID=1547792650453913661 node tools/discord-setup.mjs --apply
 *
 * The token is never written anywhere, never printed, and never sent anywhere
 * except api.discord.com. Pass it as an environment variable, not an argument
 * (arguments are visible in process listings).
 *
 * Required bot permissions: Manage Roles, Manage Channels, Send Messages,
 * Manage Messages (to pin). Invite it with scope `bot` and those permissions.
 *
 * ── Where the content lives ──────────────────────────────────────────────────
 * The long buyer-facing copy (#pricing, #how-to, #rules, #announcements, #buy) is
 * read out of docs/discord-setup.md by heading, so the doc stays the single source
 * of truth and there is no second copy to drift. Channel *topics* are short
 * operational strings and live in the PLAN below.
 *
 * Idempotent: existing roles/channels are matched by name and left alone, so
 * re-running only fixes what is missing.
 *
 * Discord rate-limits channel and role creation hard, so this paces itself and
 * honours 429 `retry_after`. A full run may take several minutes. If it stops
 * partway, just run it again.
 */

import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

const API = "https://discord.com/api/v10";
const HERE = dirname(fileURLToPath(import.meta.url));
const DOC = join(HERE, "..", "docs", "discord-setup.md");

const APPLY = process.argv.includes("--apply");
const DUMP = process.argv.includes("--dump");
const CLEAN = process.argv.includes("--clean");
const TOKEN = process.env.DISCORD_BOT_TOKEN || "";
const GUILD = process.env.DISCORD_GUILD_ID || "";

// ── Permission bits ──────────────────────────────────────────────────────────
const VIEW_CHANNEL = 1n << 10n;
const SEND_MESSAGES = 1n << 11n;
const MANAGE_MESSAGES = 1n << 13n;

// ── Palette (must match src/com/sun/java/fontmgr/Theme.java) ─────────────────
const GOLD = 0xffc33c;
const GREEN = 0x41c35f;
const BLUE = 0x5591ff;
const GREY = 0x969ba5;

/**
 * Visibility presets, applied as overwrites on the CATEGORY so children inherit:
 *   public   — everyone can read, nobody can post (except #buy, see below)
 *   customers— Customer + Trial + Staff only
 *   staff    — Staff only
 */
const VISIBILITY = {
  public: "public",
  customers: "customers",
  staff: "staff",
};

const ROLES = [
  { name: "Staff", color: GOLD, hoist: true },
  { name: "Customer", color: GREEN, hoist: false },
  { name: "Trial", color: BLUE, hoist: false },
  { name: "Muted", color: GREY, hoist: false },
];

const PLAN = [
  {
    category: "INFO",
    visibility: VISIBILITY.public,
    channels: [
      { name: "announcements", topic: "Product updates. Staff only.", copy: "#announcements" },
      { name: "pricing", topic: "Plans, trial, payment.", copy: "#pricing" },
      { name: "how-to", topic: "Install → Activate → Play.", copy: "#how-to" },
      { name: "rules", topic: "Keys, chargebacks, 1 PC.", copy: "#rules" },
      // #buy has to be writable, which the read-only INFO category denies, so it
      // gets an explicit per-channel allow below.
      { name: "buy", topic: "Say which plan. Staff DMs payment.", copy: "#buy", allowEveryonePost: true },
    ],
  },
  {
    category: "SUPPORT",
    // Public, and writable: #pricing and #how-to both point here, and their spec
    // never gated SUPPORT. A ticket-free support channel is also your best social
    // proof. If you would rather only buyers could post, change this to
    // VISIBILITY.customers — that is the whole change.
    visibility: VISIBILITY.public,
    allowPost: true,
    channels: [
      { name: "support", topic: "Attach / license help. Never post a key here." },
      { name: "renewals", topic: "30 / 60-day keys running out." },
    ],
  },
  {
    category: "CUSTOMERS",
    // Without this the Customer and Trial roles gate nothing. Gate the build
    // itself, which is the one artifact non-buyers must not have.
    visibility: VISIBILITY.customers,
    channels: [
      { name: "downloads", topic: "Roatz build + install notes. Buyers and trials only." },
    ],
  },
  {
    category: "STAFF",
    visibility: VISIBILITY.staff,
    channels: [
      { name: "orders", topic: "Staff log only." },
      { name: "keys-log", topic: "Staff audit only." },
    ],
  },
];

// ── Content extraction ───────────────────────────────────────────────────────

/** Pulls the first ``` fenced block that follows a `### ` heading containing `key`. */
function extractBlock(markdown, key) {
  const lines = markdown.split(/\r?\n/);
  const head = lines.findIndex((l) => l.startsWith("### ") && l.includes(key));
  if (head < 0) return null;
  const open = lines.findIndex((l, i) => i > head && l.trim().startsWith("```"));
  if (open < 0) return null;
  const close = lines.findIndex((l, i) => i > open && l.trim() === "```");
  if (close < 0) return null;
  return lines.slice(open + 1, close).join("\n").trim();
}

// ── Cleanup planning ─────────────────────────────────────────────────────────

/**
 * Works out what `--clean` may safely delete.
 *
 * Pure and exported so it can be tested against a synthetic server without
 * touching Discord. The safety rules, in order of importance:
 *
 *   1. Only channels whose NAME is in the plan are ever deleted. Anything else is
 *      reported as "foreign" and left alone. This runs in the user's real server,
 *      so the failure mode has to be "did nothing", never "deleted #general".
 *   2. A category is only deleted if every child inside it is also in the plan.
 *      A category holding a channel we do not own is blocked, because deleting a
 *      category takes its children with it.
 *   3. Categories that are not in the plan are never touched at all.
 */
export function planCleanup(existing, planChannelNames, planCategoryNames) {
  const channelsToDelete = [];
  const categoriesToDelete = [];
  const foreign = [];
  const blockedCategories = [];

  const childrenOf = new Map();
  for (const ch of existing) {
    if (ch.type === 4) continue;
    const k = ch.parent_id ?? null;
    if (!childrenOf.has(k)) childrenOf.set(k, []);
    childrenOf.get(k).push(ch);
  }

  for (const ch of existing) {
    if (ch.type === 4) continue;
    if (planChannelNames.has(ch.name)) {
      channelsToDelete.push({ id: ch.id, name: ch.name });
    } else {
      foreign.push({ id: ch.id, name: ch.name, parent: ch.parent_id ?? null });
    }
  }

  for (const cat of existing) {
    if (cat.type !== 4) continue;
    if (!planCategoryNames.has(cat.name)) continue; // not ours
    const kids = childrenOf.get(cat.id) ?? [];
    const foreignKids = kids.filter((k) => !planChannelNames.has(k.name));
    if (foreignKids.length) {
      blockedCategories.push({
        id: cat.id,
        name: cat.name,
        children: foreignKids.map((f) => f.name),
      });
      continue;
    }
    categoriesToDelete.push({ id: cat.id, name: cat.name });
  }

  return { channelsToDelete, categoriesToDelete, foreign, blockedCategories };
}

/**
 * Overwrites for a single channel.
 *
 * The bot's allow is set EXPLICITLY on every channel, not left to inherit from
 * the category: Discord copies a category's overwrites onto channels created
 * inside it, so a read-only channel ends up with its own `@everyone -SEND`, and
 * depending on how those two layers resolve is exactly the subtlety that broke
 * the first real run. A member overwrite stated on the channel itself wins
 * outright.
 */
export function channelOverwrites(ch, everyoneId, botId) {
  const out = [];
  if (ch.allowEveryonePost) {
    out.push({
      id: everyoneId,
      type: 0,
      allow: (VIEW_CHANNEL | SEND_MESSAGES).toString(),
      deny: "0",
    });
  }
  if (botId) {
    out.push({
      id: botId,
      type: 1,
      allow: (VIEW_CHANNEL | SEND_MESSAGES | MANAGE_MESSAGES).toString(),
      deny: "0",
    });
  }
  return out;
}

// ── Discord REST ─────────────────────────────────────────────────────────────

let calls = 0;

async function api(method, path, body) {
  for (let attempt = 0; attempt < 5; attempt++) {
    calls++;
    const res = await fetch(API + path, {
      method,
      headers: {
        Authorization: `Bot ${TOKEN}`,
        "Content-Type": "application/json",
        "User-Agent": "RoatzSetup (https://github.com/Tonic-Box/Roatz, 1.0.0)",
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 429) {
      // Discord tells us exactly how long to wait. Obey it rather than guessing.
      const info = await res.json().catch(() => ({}));
      const wait = Math.ceil(((info.retry_after ?? 1) + 0.5) * 1000);
      process.stdout.write(`  rate limited, waiting ${(wait / 1000).toFixed(1)}s\n`);
      await sleep(wait);
      continue;
    }
    const text = await res.text();
    if (!res.ok) {
      throw new Error(`${method} ${path} -> ${res.status} ${text.slice(0, 400)}`);
    }
    return text ? JSON.parse(text) : null;
  }
  throw new Error(`${method} ${path} -> still rate limited after 5 attempts`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Creation is the rate-limited part; write paths get a small gap between them. */
const PACE_MS = 1100;

// ── Overwrites ───────────────────────────────────────────────────────────────

export function overwritesFor(visibility, everyoneId, rolesByName, allowPost, botId) {
  const allow = (bits) => ({ allow: bits.toString(), deny: "0" });
  const deny = (bits) => ({ allow: "0", deny: bits.toString() });
  const out = [];
  const ow = (id, type, bits, isAllow) => ({
    id,
    type,
    ...(isAllow ? allow(bits) : deny(bits)),
  });

  if (visibility === VISIBILITY.public) {
    // Readable. Read-only unless the category opts into posting, and #buy
    // re-allows posting for itself on top of the read-only INFO category.
    out.push({
      id: everyoneId,
      type: 0,
      allow: VIEW_CHANNEL.toString(),
      deny: allowPost ? "0" : SEND_MESSAGES.toString(),
    });
  } else if (visibility === VISIBILITY.customers) {
    out.push(ow(everyoneId, 0, VIEW_CHANNEL, false));
    for (const name of ["Customer", "Trial", "Staff"]) {
      const role = rolesByName.get(name);
      if (role) out.push(ow(role.id, 0, VIEW_CHANNEL, true));
    }
  } else {
    out.push(ow(everyoneId, 0, VIEW_CHANNEL, false));
    const staff = rolesByName.get("Staff");
    if (staff) out.push(ow(staff.id, 0, VIEW_CHANNEL, true));
  }

  // The bot is itself subject to the @everyone denies above, and a member-level
  // overwrite beats a role-level one. Without this the bot cannot post its own
  // pinned copy into the read-only channels it just created (403 Missing
  // Permissions on the message POST), nor see the channels it must pin in.
  if (botId) {
    out.push({
      id: botId,
      type: 1, // member overwrite
      allow: (VIEW_CHANNEL | SEND_MESSAGES | MANAGE_MESSAGES).toString(),
      deny: "0",
    });
  }
  return out;
}

// ── Main ─────────────────────────────────────────────────────────────────────

async function main() {
  const doc = await readFile(DOC, "utf8");

  // Resolve and validate all copy up front: a missing block should stop the run
  // before anything is created, not half way through.
  const content = new Map();
  const problems = [];
  for (const cat of PLAN) {
    for (const ch of cat.channels) {
      if (!ch.copy) continue;
      const body = extractBlock(doc, ch.copy);
      if (!body) {
        problems.push(`no \`${ch.copy}\` block found in ${DOC}`);
        continue;
      }
      // Discord's hard limit per message.
      if (body.length > 2000) {
        problems.push(`\`${ch.copy}\` block is ${body.length} chars (Discord max 2000)`);
      }
      content.set(ch.name, body);
    }
  }
  if (problems.length) {
    console.error("Cannot run:");
    for (const p of problems) console.error("  - " + p);
    process.exit(1);
  }

  console.log(`Roatz Discord setup — ${APPLY ? "APPLY" : "DRY RUN"}`);
  console.log(`doc: ${DOC}`);
  console.log(`copy blocks found: ${[...content.keys()].join(", ")}`);
  console.log("");
  for (const cat of PLAN) {
    console.log(`${cat.category}  (${cat.visibility}${cat.allowPost ? ", posting allowed" : ""})`);
    for (const ch of cat.channels) {
      const body = content.get(ch.name);
      const extra = body ? `copy ${String(body.length).padStart(4)} chars` : "no post";
      const post = ch.allowEveryonePost ? "  [+everyone may post]" : "";
      console.log(`  #${ch.name.padEnd(14)} ${extra}${post}`);
    }
  }
  console.log("");
  console.log(`roles: ${ROLES.map((r) => r.name).join(", ")}`);
  if (CLEAN) {
    console.log("");
    console.log("clean: on — plan-named channels and categories will be deleted, then rebuilt");
  }

  if (!APPLY) {
    if (DUMP) {
      console.log("");
      console.log("=== exact text that would be posted ===");
      for (const [name, body] of content) {
        console.log("");
        console.log(`---------- #${name} ----------`);
        console.log(body);
      }
      console.log("");
    }
    console.log("DRY RUN — nothing was created. Re-run with --apply to make changes.");
    return;
  }

  if (!TOKEN) {
    console.error("Set DISCORD_BOT_TOKEN (env var, not an argument).");
    process.exit(1);
  }
  if (!GUILD || !/^\d{17,20}$/.test(GUILD)) {
    console.error("Set DISCORD_GUILD_ID to the server id (17-20 digits).");
    process.exit(1);
  }

  console.log("");
  console.log("Connecting…");
  const me = await api("GET", "/users/@me");
  let pinFailures = [];
  const guild = await api("GET", `/guilds/${GUILD}`);
  const everyoneId = guild.id; // @everyone role id equals the guild id
  console.log(`  guild: ${guild.name}`);
  console.log(`  bot:   ${me.username} (${me.id})`);

  const planChannelNames = new Set(PLAN.flatMap((c) => c.channels.map((ch) => ch.name)));
  const planCategoryNames = new Set(PLAN.map((c) => c.category));

  // --- optional cleanup, before anything is created ---
  if (CLEAN) {
    console.log("  cleaning…");
    const existingNow = await api("GET", `/guilds/${GUILD}/channels`);
    const plan = planCleanup(existingNow, planChannelNames, planCategoryNames);

    // Print the whole plan before deleting anything: this runs in a real server,
    // so the destructive step is always preceded by its own listing.
    if (plan.channelsToDelete.length) {
      console.log(`    will delete channels: ${plan.channelsToDelete.map((c) => "#" + c.name).join(", ")}`);
    }
    if (plan.categoriesToDelete.length) {
      console.log(`    will delete categories: ${plan.categoriesToDelete.map((c) => c.name).join(", ")}`);
    }
    for (const cat of plan.blockedCategories) {
      console.log(`    SKIP category ${cat.name} — holds channels not in the plan: ${cat.children.join(", ")}`);
    }
    if (plan.foreign.length) {
      console.log(`    left alone (not in the plan): ${plan.foreign.map((f) => "#" + f.name).join(", ")}`);
    }
    if (!plan.channelsToDelete.length && !plan.categoriesToDelete.length) {
      console.log("    nothing to delete");
    }

    for (const ch of plan.channelsToDelete) {
      await api("DELETE", `/channels/${ch.id}`);
      console.log(`    deleted #${ch.name}`);
      await sleep(PACE_MS);
    }
    for (const cat of plan.categoriesToDelete) {
      await api("DELETE", `/channels/${cat.id}`);
      console.log(`    deleted category ${cat.name}`);
      await sleep(PACE_MS);
    }
  }

  // --- roles ---
  const existingRoles = await api("GET", `/guilds/${GUILD}/roles`);
  const rolesByName = new Map(existingRoles.map((r) => [r.name, r]));
  for (const role of ROLES) {
    if (rolesByName.has(role.name)) {
      console.log(`  role ${role.name}: exists`);
      continue;
    }
    const made = await api("POST", `/guilds/${GUILD}/roles`, {
      name: role.name,
      color: role.color,
      hoist: role.hoist,
      mentionable: false,
      permissions: "0",
    });
    rolesByName.set(made.name, made);
    console.log(`  role ${role.name}: created`);
    await sleep(PACE_MS);
  }

  // --- categories and channels ---
  const existing = await api("GET", `/guilds/${GUILD}/channels`);
  const byName = new Map(existing.map((c) => [c.name, c]));

  for (const cat of PLAN) {
    let category = byName.get(cat.category);
    if (!category) {
      category = await api("POST", `/guilds/${GUILD}/channels`, {
        name: cat.category,
        type: 4, // GUILD_CATEGORY
        permission_overwrites: overwritesFor(cat.visibility, everyoneId, rolesByName, cat.allowPost, me.id),
      });
      byName.set(category.name, category);
      console.log(`  category ${cat.category}: created`);
      await sleep(PACE_MS);
    } else {
      // Reconcile rather than skip. The first run against a real server failed
      // because an existing category kept its old overwrites, so a fix to the
      // permission model would never reach it. Converging on the plan is what a
      // provisioning tool is for.
      await api("PATCH", `/channels/${category.id}`, {
        permission_overwrites: overwritesFor(cat.visibility, everyoneId, rolesByName, cat.allowPost, me.id),
      });
      console.log(`  category ${cat.category}: exists, permissions reconciled`);
      await sleep(PACE_MS);
    }

    for (const ch of cat.channels) {
      const body = { name: ch.name, type: 0, parent_id: category.id, topic: ch.topic };
      const chOw = channelOverwrites(ch, everyoneId, me.id);
      if (chOw.length) body.permission_overwrites = chOw;
      let chan = byName.get(ch.name);
      if (!chan) {
        chan = await api("POST", `/guilds/${GUILD}/channels`, body);
        byName.set(chan.name, chan);
        console.log(`    #${ch.name}: created`);
        await sleep(PACE_MS);
      } else {
        const patch = { topic: ch.topic };
        const chOw = channelOverwrites(ch, everyoneId, me.id);
        if (chOw.length) patch.permission_overwrites = chOw;
        await api("PATCH", `/channels/${chan.id}`, patch);
        console.log(`    #${ch.name}: exists, topic + permissions reconciled`);
        await sleep(PACE_MS);
      }

      const text = content.get(ch.name);
      if (text) {
        // Idempotent posting: re-running must not stack duplicate copies of the
        // copy in the channel. Look for a message we already posted verbatim.
        let msg = null;
        try {
          const recent = await api("GET", `/channels/${chan.id}/messages?limit=50`);
          msg = (recent || []).find(
            (m) => m.author && m.author.id === me.id && m.content === text
          ) || null;
        } catch (e) {
          // Not fatal — worst case we post a duplicate rather than fail the run.
          console.log(`    #${ch.name}: could not read history (${e.message.slice(0, 60)})`);
        }

        if (msg) {
          console.log(`    #${ch.name}: copy already posted, skipping`);
        } else {
          try {
            msg = await api("POST", `/channels/${chan.id}/messages`, { content: text });
            console.log(`    #${ch.name}: posted (${text.length} chars)`);
          } catch (e) {
            if (/50013|Missing Permissions/.test(String(e.message))) {
              throw new Error(`cannot post in #${ch.name} — the bot lacks Send Messages `
                + `there. ` + String(e.message));
            }
            throw e;
          }
          await sleep(PACE_MS);
        }

        // Pinning is cosmetic. Measured against a bare channel with no overwrites
        // the endpoint still returns 403 despite the bot holding MANAGE_MESSAGES
        // guild-wide, so it gates on something this invite does not grant. A
        // missing pin must never abort provisioning.
        try {
          await api("PUT", `/channels/${chan.id}/pins/${msg.id}`);
          console.log(`    #${ch.name}: pinned`);
        } catch (e) {
          pinFailures.push(ch.name);
          console.log(`    #${ch.name}: PIN FAILED — ${e.message.slice(0, 70)}`);
        }
        await sleep(PACE_MS);
      }
    }
  }

  console.log("");
  console.log(`Done. ${calls} API calls.`);
  if (pinFailures.length) {
    console.log("");
    console.log(`Could not pin: ${pinFailures.map((n) => "#" + n).join(", ")}`);
    console.log("The messages are posted; pin them by hand (right-click → Pin) if you want them pinned.");
  }
  console.log("Next: assign the Customer role to buyers after payment — the bot does not do that.");
}

const isMain = process.argv[1] &&
  fileURLToPath(import.meta.url) === resolve(process.argv[1]);

if (isMain) {
  main().catch((e) => {
  console.error("");
    console.error("FAILED: " + (e && e.message ? e.message : e));
    // Deliberately not "nothing was changed": a failure partway through leaves
    // everything created so far in place. Re-running skips whatever exists.
    console.error("Stopped partway. Anything already created is kept — re-run and it skips what exists.");
    // NOTE: deliberately not process.exit(). Calling it while undici still holds a
    // keep-alive socket trips a libuv assertion on Windows
    // ("!(handle->flags & UV_HANDLE_CLOSING)"). Setting exitCode lets the loop
    // drain and the process still exits non-zero.
    process.exitCode = 1;
  });
}
