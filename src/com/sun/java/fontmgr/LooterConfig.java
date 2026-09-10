package com.sun.java.fontmgr;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Wild-looter trip configuration. Persisted as a Properties file under
 * {@link Stealth#cacheFile(String)} so geometry / thresholds can be tuned
 * without a rebuild:
 *
 * <pre>
 *   zone.x / zone.y / zone.r        — loot zone centre (world tiles) + half-extent
 *   bank.x / bank.y                 — tile you stand on when banking
 *   pickup.radius                   — max tiles from player for a Take click
 *   value.min                       — pk-point value threshold (per item * qty)
 *   names.always                    — comma list of substrings to ALWAYS grab
 *   trip.freeSlots                  — bank when free inventory slots drop to this
 *   trip.droughtMs                  — bank after N ms without a successful grab
 *   trip.minItems                   — minimum haul before a drought-triggered bank
 *   trip.maxTicks                   — hard cap on one looting trip (in game ticks)
 *   bank.openTickTimeout            — ticks to wait for the bank interface to open
 *   bank.depositRetries             — deposit button clicks before giving up
 * </pre>
 *
 * Coordinate pairs can be captured live with the LOOTER|RECORD socket commands
 * (bank tile = where you stand when banking, zone centre = middle of the wild
 * strip you farm).
 */
public final class LooterConfig {

    // Loot zone (world tile coords).
    public volatile int zoneX = 0;
    public volatile int zoneY = 0;
    public volatile int zoneR = 8;

    // Bank tile — stand on/near this when banking.
    public volatile int bankX = 0;
    public volatile int bankY = 0;
    public volatile int bankTol = 2;

    // Pickup / value rules.
    public volatile int pickupRadius = 12;      // max tiles for a Take click
    public volatile int minPkValue   = 500;     // pk-point value threshold
    public volatile String alwaysNames = "pk point,summer token"; // substrings, always grab

    // Trip cadence.
    public volatile int freeSlotsBank = 2;      // bank when free slots drop to this
    public volatile long droughtMs    = 90_000; // no-grab drought that forces a bank run
    public volatile int  minTripItems = 4;      // min haul before drought forces a bank run
    public volatile int  maxTripTicks = 3_600;  // hard cap per trip (game ticks)

    // Bank interaction tolerances.
    public volatile int bankOpenTicks  = 40;    // wait for interface 29000 after click
    public volatile int depositRetries = 3;

    // Wander pacing.
    public volatile int wanderMinTicks = 2;     // min ticks between re-issue of walk
    public volatile int stuckTicks     = 12;    // standing-still detection before re-issue

    // Optional bank overrides filled after calibration (empty = auto-detect).
    public volatile String bankNpcName  = "";   // e.g. "Banker"
    public volatile String bankObjName  = "";   // e.g. "Bank booth"
    public volatile int    bankClickOpcode = 0; // 0 = auto (by action slot)
    public volatile String bankClickOpt = "";   // e.g. "Bank" / "Use-quickly"

    public void load() {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file().toFile())) {
            p.load(in);
            zoneX = get(p, "zone.x", zoneX);
            zoneY = get(p, "zone.y", zoneY);
            zoneR = get(p, "zone.r", zoneR);
            bankX = get(p, "bank.x", bankX);
            bankY = get(p, "bank.y", bankY);
            bankTol = get(p, "bank.tol", bankTol);
            pickupRadius = get(p, "pickup.radius", pickupRadius);
            minPkValue   = get(p, "value.min", minPkValue);
            alwaysNames  = p.getProperty("names.always", alwaysNames);
            freeSlotsBank = get(p, "trip.freeSlots", freeSlotsBank);
            droughtMs     = get(p, "trip.droughtMs", droughtMs);
            minTripItems  = get(p, "trip.minItems", minTripItems);
            maxTripTicks  = get(p, "trip.maxTicks", maxTripTicks);
            bankOpenTicks = get(p, "bank.openTickTimeout", bankOpenTicks);
            depositRetries= get(p, "bank.depositRetries", depositRetries);
            wanderMinTicks = get(p, "wander.minTicks", wanderMinTicks);
            stuckTicks     = get(p, "wander.stuckTicks", stuckTicks);
            bankNpcName    = p.getProperty("bank.npc", bankNpcName);
            bankObjName    = p.getProperty("bank.object", bankObjName);
            bankClickOpcode= get(p, "bank.clickOpcode", bankClickOpcode);
            bankClickOpt   = p.getProperty("bank.clickOpt", bankClickOpt);
        } catch (Exception ignored) {
            // First run — defaults only.
        }
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("zone.x", String.valueOf(zoneX));
        p.setProperty("zone.y", String.valueOf(zoneY));
        p.setProperty("zone.r", String.valueOf(zoneR));
        p.setProperty("bank.x", String.valueOf(bankX));
        p.setProperty("bank.y", String.valueOf(bankY));
        p.setProperty("bank.tol", String.valueOf(bankTol));
        p.setProperty("pickup.radius", String.valueOf(pickupRadius));
        p.setProperty("value.min", String.valueOf(minPkValue));
        p.setProperty("names.always", alwaysNames == null ? "" : alwaysNames);
        p.setProperty("trip.freeSlots", String.valueOf(freeSlotsBank));
        p.setProperty("trip.droughtMs", String.valueOf(droughtMs));
        p.setProperty("trip.minItems", String.valueOf(minTripItems));
        p.setProperty("trip.maxTicks", String.valueOf(maxTripTicks));
        p.setProperty("bank.openTickTimeout", String.valueOf(bankOpenTicks));
        p.setProperty("bank.depositRetries", String.valueOf(depositRetries));
        p.setProperty("wander.minTicks", String.valueOf(wanderMinTicks));
        p.setProperty("wander.stuckTicks", String.valueOf(stuckTicks));
        p.setProperty("bank.npc", bankNpcName == null ? "" : bankNpcName);
        p.setProperty("bank.object", bankObjName == null ? "" : bankObjName);
        p.setProperty("bank.clickOpcode", String.valueOf(bankClickOpcode));
        p.setProperty("bank.clickOpt", bankClickOpt == null ? "" : bankClickOpt);
        try (OutputStream out = new FileOutputStream(file().toFile())) {
            p.store(out, "wild looter");
        } catch (Exception ignored) {
        }
    }

    public boolean configured() {
        return zoneX != 0 && zoneY != 0 && bankX != 0 && bankY != 0;
    }

    private static Path file() {
        return Stealth.cacheFile("pk");
    }

    private static int get(Properties p, String key, int dflt) {
        String v = p.getProperty(key);
        if (v == null) return dflt;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long get(Properties p, String key, long dflt) {
        String v = p.getProperty(key);
        if (v == null) return dflt;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public String describe() {
        return "zone=(" + zoneX + "," + zoneY + " r" + zoneR + ") bank=(" + bankX + "," + bankY + ")"
                + " radius=" + pickupRadius + " min=" + minPkValue;
    }
}
