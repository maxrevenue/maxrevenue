package com.sun.java.fontmgr;

import java.util.List;

/**
 * Wild looter state machine — runs alongside {@link CombatScript} as a second
 * {@link TickListener}.
 *
 * <pre>
 *  RETURN_ZONE → SWEEP → GRAB → SWEEP … (bank trigger) → TO_BANK → OPEN_BANK
 *              → DEPOSIT → RETURN_ZONE
 * </pre>
 *
 * Loot filter mirrors the Ground Items plugin numbers: any item whose
 * pk-point price (from the client's own price table) × stack qty is ≥ the
 * threshold is grabbed, plus any name in the always-grab list ("pk point",
 * "summer token" by default). One Take click per trip target uses opcode 234,
 * which makes the client path there and pick it up exactly like a human click.
 *
 * Banking is pluggable:
 *   - mode "npc":       auto-find a Banker NPC and click its Bank option
 *                       (opcode by action slot), then press the bank's
 *                       Deposit inventory button (29012), then Close.
 *   - mode "tileload":  replay a ground "Load … (Tile)" click (calibrated
 *                       constants), which deposits inventory + restocks a kit.
 */
public final class LooterScript implements TickListener {

    private enum St {
        IDLE, RETURN_ZONE, SWEEP, GRAB, TO_BANK, OPEN_BANK, DEPOSIT, POST_BANK
    }

    public final LooterConfig cfg = new LooterConfig();
    public volatile boolean enabled = false;

    private final LooterApi api;
    private St state = St.IDLE;

    // Per-trip counters.
    private int tripStartTick = -1;
    private int tripItems = 0;
    private long lastGrabMs = 0;
    private long lastActionMs = 0;

    // Grab bookkeeping.
    private LooterApi.Loot target;
    private int grabTicks = 0;

    // Movement bookkeeping.
    private int wanderTargetX, wanderTargetY;
    private int wanderTick = 0;
    private int noMoveTicks = 0;
    private int lastPosX, lastPosY;
    private long lastIssueWalkMs = 0;

    // Bank bookkeeping.
    private int bankWaitTicks = 0;
    private int depositTicks = 0;
    private int depositRetriesLeft = 0;
    private int invCountOnArrive = 0;
    private boolean bankOpenedThisTrip = false;

    private String status = "off";
    private boolean cfgWarned = false;
    private int tick = 0;

    public LooterScript(Object clientInstance) {
        this.api = new LooterApi(clientInstance);
        cfg.load();
        if (cfg.configured()) {
            status = "idle (cfg ok)";
        } else {
            status = "not configured — record zone + bank (LOOTER|RECORD|...)";
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public control (socket / UI)
    // ════════════════════════════════════════════════════════════════════════

    public void setEnabled(boolean on) {
        if (on && !cfg.configured()) {
            status = "need zone+bank: LOOTER|RECORD|ZONE then LOOTER|RECORD|BANK";
            return;
        }
        this.enabled = on;
        if (!on) {
            state = St.IDLE;
            status = "disabled";
        } else {
            state = cfg.configured() ? St.RETURN_ZONE : St.IDLE;
            tripStartTick = tick;
            tripItems = 0;
            status = "starting";
        }
    }

    /** Record current position as the bank tile. */
    public String recordBank() {
        int x = api.realX(), y = api.realY();
        if (x <= 0) return "ERROR|no position";
        cfg.bankX = x;
        cfg.bankY = y;
        cfg.save();
        status = "bank recorded (" + x + "," + y + ")";
        return status;
    }

    /** Record current position as the loot-zone centre. */
    public String recordZone() {
        int x = api.realX(), y = api.realY();
        if (x <= 0) return "ERROR|no position";
        cfg.zoneX = x;
        cfg.zoneY = y;
        cfg.save();
        status = "zone recorded (" + x + "," + y + ") r=" + cfg.zoneR;
        return status;
    }

    public String saveCfg() { cfg.save(); return "saved"; }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(enabled)
          .append("|state=").append(state)
          .append("|trip=").append(tripItems)
          .append("|free=").append(api.freeSlots())
          .append("|pos=").append(api.realX()).append(",").append(api.realY())
          .append("|prices=").append(PkPriceTable.size())
          .append("|msg=").append(status);
        return sb.toString();
    }

    public String config() {
        return cfg.describe() + "|always=" + cfg.alwaysNames + "|min=" + cfg.minPkValue
                + "|freeSlots=" + cfg.freeSlotsBank + "|drought=" + cfg.droughtMs
                + "|bankNpc=" + cfg.bankNpcName;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Tick
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public void onTick(int gameTick) {
        tick = gameTick;
        if (!enabled) return;
        if (!cfg.configured()) {
            if (!cfgWarned) { status = "record zone & bank first"; cfgWarned = true; }
            return;
        }
        PkPriceTable.ensureLoaded();

        int px = api.realX(), py = api.realY();
        if (px != lastPosX || py != lastPosY) {
            noMoveTicks = 0;
            lastPosX = px;
            lastPosY = py;
        } else {
            noMoveTicks++;
        }

        switch (state) {
            case RETURN_ZONE: stepReturnZone(px, py); break;
            case SWEEP:       stepSweep(px, py); break;
            case GRAB:        stepGrab(px, py); break;
            case TO_BANK:     stepToBank(px, py); break;
            case OPEN_BANK:   stepOpenBank(px, py); break;
            case DEPOSIT:     stepDeposit(px, py); break;
            case POST_BANK:   stepPostBank(px, py); break;
            default: break;
        }

        // Hard trip cap → force a bank run even while busy.
        if (state == St.SWEEP || state == St.GRAB) {
            if (tripStartTick > 0 && tick - tripStartTick > cfg.maxTripTicks) {
                if (tripItems > 0) goBank("max trip time");
            }
        }
    }

    // ── RETURN_ZONE ─────────────────────────────────────────────────────────
    private void stepReturnZone(int px, int py) {
        if (insideZone(px, py)) {
            state = St.SWEEP;
            tripStartTick = tick;
            tripItems = 0;
            status = "sweeping";
            return;
        }
        int tx = cfg.zoneX, ty = cfg.zoneY;
        if (dist2(px, py, tx, ty) <= 4) {
            state = St.SWEEP;
            status = "sweeping";
            return;
        }
        if (!issueWalk(tx, ty)) {
            // Outside the walkable region and stepping made no progress.
            if (noMoveTicks > 40) {
                status = "cannot path to zone from (" + px + "," + py + ")";
            }
        }
    }

    // ── SWEEP ───────────────────────────────────────────────────────────────
    private void stepSweep(int px, int py) {
        List<LooterApi.Loot> loot = api.scanGround(cfg.pickupRadius, cfg.minPkValue, cfg.alwaysNames);
        if (!loot.isEmpty()) {
            target = choose(loot, px, py);
            grabTicks = 0;
            state = St.GRAB;
            return;
        }

        // Bank trigger checks.
        int free = api.freeSlots();
        if (free <= cfg.freeSlotsBank && tripItems > 0) {
            goBank("inventory almost full");
            return;
        }
        if (tripItems >= cfg.minTripItems && lastGrabMs > 0
                && System.currentTimeMillis() - lastGrabMs > cfg.droughtMs) {
            goBank("drought");
            return;
        }

        wander(px, py);
    }

    private LooterApi.Loot choose(List<LooterApi.Loot> loot, int px, int py) {
        // Nearest-first vacuuming with a value tie-break; always-grab items
        // beat purely-value items when distance is similar.
        LooterApi.Loot best = null;
        int bestD = Integer.MAX_VALUE;
        long bestV = Long.MIN_VALUE;
        for (LooterApi.Loot l : loot) {
            int d = dist2(px, py, l.worldX, l.worldY);
            if (d < bestD - 2) {
                best = l; bestD = d; bestV = value(l);
            } else if (Math.abs(d - bestD) <= 2 && best != null && value(l) > bestV) {
                best = l; bestD = d; bestV = value(l);
            }
        }
        if (best == null && !loot.isEmpty()) best = loot.get(0);
        return best;
    }

    private static long value(LooterApi.Loot l) {
        if (l.always) return Long.MAX_VALUE / 2 + l.totalValue();
        return l.totalValue();
    }

    private void wander(int px, int py) {
        if (++wanderTick < cfg.wanderMinTicks && lastIssueWalkMs != 0) return;
        if (noMoveTicks > cfg.stuckTicks || wanderTick >= 6 || insideZone(wanderTargetX, wanderTargetY)) {
            // Pick a fresh random point inside the zone.
            wanderTick = 0;
            java.util.Random r = LootRandom.rnd;
            wanderTargetX = cfg.zoneX + r.nextInt(cfg.zoneR * 2 + 1) - cfg.zoneR;
            wanderTargetY = cfg.zoneY + r.nextInt(cfg.zoneR * 2 + 1) - cfg.zoneR;
            if (dist2(px, py, wanderTargetX, wanderTargetY) < 4) {
                wanderTargetX = px + r.nextInt(7) - 3;
                wanderTargetY = py + r.nextInt(7) - 3;
            }
            if (api.inRegion(wanderTargetX, wanderTargetY)) {
                issueWalk(wanderTargetX, wanderTargetY);
            } else {
                api.walkTowardWorld(wanderTargetX, wanderTargetY);
                lastIssueWalkMs = System.currentTimeMillis();
            }
            status = "wandering";
        } else {
            if (!arrived(wanderTargetX, wanderTargetY, 1)) {
                if (!api.inRegion(wanderTargetX, wanderTargetY)) {
                    api.walkTowardWorld(wanderTargetX, wanderTargetY);
                }
            }
        }
    }

    // ── GRAB ────────────────────────────────────────────────────────────────
    private void stepGrab(int px, int py) {
        if (target == null) { state = St.SWEEP; return; }

        boolean gone = !stillOnGround(target);
        if (gone) {
            tripItems++;
            lastGrabMs = System.currentTimeMillis();
            status = "grabbed " + target.name + " (" + target.totalValue() + ")";
            target = null;
            if (api.freeSlots() <= cfg.freeSlotsBank) {
                goBank("inventory full after grab");
            } else {
                state = St.SWEEP;
            }
            return;
        }

        if (grabTicks == 0) {
            // First tick on this target → click Take (client walks + picks).
            api.clickTake(target.localX, target.localY, target.itemId);
            lastActionMs = System.currentTimeMillis();
        }
        grabTicks++;
        if (grabTicks > 18) {
            // Give up after ~11s; maybe un-reachable / stolen.
            status = "gave up on " + target.name;
            target = null;
            state = St.SWEEP;
            return;
        }
        // Re-click if we've stopped moving and it's still there after a while.
        if (grabTicks == 10 && noMoveTicks >= 4) {
            api.clickTake(target.localX, target.localY, target.itemId);
        }
    }

    private boolean stillOnGround(LooterApi.Loot target) {
        List<LooterApi.Loot> now = api.scanGround(cfg.pickupRadius, 0, "::::");
        for (LooterApi.Loot l : now) {
            if (l.itemId == target.itemId && l.localX == target.localX
                    && l.localY == target.localY && l.amount == target.amount) return true;
        }
        return false;
    }

    // ── TO_BANK ─────────────────────────────────────────────────────────────
    private void goBank(String why) {
        state = St.TO_BANK;
        bankWaitTicks = 0;
        bankOpenedThisTrip = false;
        depositRetriesLeft = cfg.depositRetries;
        status = "to bank (" + why + ")";
    }

    private void stepToBank(int px, int py) {
        if (arrived(cfg.bankX, cfg.bankY, cfg.bankTol)) {
            invCountOnArrive = invCount();
            state = St.OPEN_BANK;
            bankWaitTicks = 0;
            status = "at bank";
            return;
        }
        if (!api.inRegion(cfg.bankX, cfg.bankY)) {
            api.walkTowardWorld(cfg.bankX, cfg.bankY);
            lastIssueWalkMs = System.currentTimeMillis();
        } else if (noMoveTicks > cfg.stuckTicks || interval(1500)) {
            issueWalk(cfg.bankX, cfg.bankY);
        }
    }

    // ── OPEN_BANK ───────────────────────────────────────────────────────────
    private void stepOpenBank(int px, int py) {
        if (api.bankOpen()) {
            state = St.DEPOSIT;
            depositTicks = 0;
            status = "bank open";
            return;
        }
        if (bankWaitTicks == 0) {
            LooterApi.BankTarget t = api.findBankNpc(cfg.pickupRadius, cfg.bankClickOpt);
            if (t == null) {
                // Optional calibrated direct ground action (e.g. Load kit tile).
                if (cfg.bankClickOpcode > 0 && !cfg.bankClickOpt.isEmpty()) {
                    api.doAction(0, 0, cfg.bankClickOpcode, 0, cfg.bankClickOpt, "");
                    status = "clicked " + cfg.bankClickOpt;
                } else {
                    status = "no banker at bank tile (radius " + cfg.pickupRadius + ")";
                }
            } else {
                api.clickBank(t);
                status = "clicking " + t.option + " " + t.label;
            }
        }
        bankWaitTicks++;
        if (bankWaitTicks > cfg.bankOpenTicks) {
            status = "BANK FAILED — no banker/interface at (" + cfg.bankX + "," + cfg.bankY
                    + "). Record a better tile (LOOTER|RECORD|BANK) or set bank.click constants.";
            state = St.IDLE;
        }
    }

    // ── DEPOSIT ─────────────────────────────────────────────────────────────
    private void stepDeposit(int px, int py) {
        int before = invCount();
        if (before <= 0) {
            finishBankRun();
            return;
        }
        if (depositTicks == 0) {
            api.depositInventoryButton();
            depositTicks = 1;
            status = "depositing";
            return;
        }
        depositTicks++;
        if (invCount() < before) {
            finishBankRun();
            return;
        }
        if (depositTicks > 8) {
            if (--depositRetriesLeft > 0) {
                depositTicks = 0;
                status = "deposit retry";
            } else {
                status = "deposit failed (" + invCount() + " items left)";
                finishBankRun(); // close and carry on; don't hard-stop the bot
            }
        }
    }

    private void finishBankRun() {
        api.closeTopInterface();
        tripItems = 0;
        target = null;
        state = St.POST_BANK;
        status = "banked";
    }

    // ── POST_BANK ───────────────────────────────────────────────────────────
    private void stepPostBank(int px, int py) {
        // Make sure the interface is actually gone before we walk.
        if (api.bankOpen()) {
            api.closeTopInterface();
            return;
        }
        state = St.RETURN_ZONE;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Small helpers
    // ════════════════════════════════════════════════════════════════════════

    private boolean insideZone(int px, int py) {
        return cfg.zoneX != 0 && Math.abs(px - cfg.zoneX) <= cfg.zoneR
                && Math.abs(py - cfg.zoneY) <= cfg.zoneR;
    }

    private boolean arrived(int wx, int wy, int tol) {
        return dist2(api.realX(), api.realY(), wx, wy) <= tol * tol;
    }

    private boolean issueWalk(int wx, int wy) {
        if (!api.inRegion(wx, wy)) return false;
        api.walkLocal(api.toLocalX(wx), api.toLocalY(wy));
        lastIssueWalkMs = System.currentTimeMillis();
        return true;
    }

    private boolean interval(long ms) {
        return System.currentTimeMillis() - lastIssueWalkMs > ms;
    }

    private int invCount() {
        return 28 - api.freeSlots();
    }

    private static int dist2(int ax, int ay, int bx, int by) {
        int dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static final class LootRandom {
        static final java.util.Random rnd = new java.util.Random();
    }
}
