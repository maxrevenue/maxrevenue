package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import com.sun.java.fontmgr.swap.CommandParser.Command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure merge-window and AHK-gap policy for {@link SwapDispatcher}.
 *
 * <p>Kept out of the dispatcher so the 160ms different-hotkey merge, the
 * per-command gap table, and the scheduled click list can be unit-tested
 * without a live {@code CombatScript}. Behaviour must stay identical to the
 * inlined loop that used to live in {@code SwapDispatcher.run}.
 */
final class SwapTimeline {

    /** Two <em>different</em> swaps pressed inside this window share a timeline. */
    static final long MERGE_WINDOW_MS = 160L;

    static final long DELAY_DEFAULT_MS = 120L;
    static final long DELAY_MAX_MS = 3000L;

    private SwapTimeline() {}

    /**
     * Merge only when the incoming name is different. Pressing the same swap
     * again starts a new generation (cancels in-flight clicks).
     */
    static boolean shouldMerge(long nowMs, long mergeUntilMs, int activeGen, int currentGen,
                               String lastSwapName, String incomingName) {
        return nowMs <= mergeUntilMs
                && activeGen == currentGen
                && lastSwapName != null && !lastSwapName.isEmpty()
                && incomingName != null && !lastSwapName.equalsIgnoreCase(incomingName);
    }

    /**
     * Client {@code AhkDetection.handleClickInventoryItem} only runs on
     * doAction opcodes 74 (eat/use) and 454 (wield) against iface 3214.
     * {@code r:} clicks worn-equipment iface 1688, so it is not an inventory click.
     */
    static boolean isInventoryClick(String type) {
        return "e".equals(type) || "drop".equals(type) || "u".equals(type);
    }

    static long parseDelayMs(String value) {
        long extra = DELAY_DEFAULT_MS;
        if (value == null) return extra;
        try {
            extra = Math.max(0L, Math.min(DELAY_MAX_MS, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            extra = DELAY_DEFAULT_MS;
        }
        return extra;
    }

    /**
     * Walk {@code steps} with the same cursor {@link SwapDispatcher} uses and
     * return every click with its {@code invokeAfter} offset. {@code delay:}
     * lines advance the cursor but are not clicks.
     */
    static Plan plan(List<Command> steps, long startDelayMs, boolean firstInv) {
        Cursor cur = new Cursor(startDelayMs, firstInv);
        List<Click> clicks = new ArrayList<>();
        if (steps == null) return new Plan(clicks, cur);
        for (Command cmd : steps) {
            if (cmd == null) continue;
            if ("delay".equals(cmd.type)) {
                cur.addExplicitDelay(cmd.value);
                continue;
            }
            long at = cur.scheduleClick(cmd.type);
            clicks.add(new Click(at, cmd));
        }
        return new Plan(clicks, cur);
    }

    static final class Click {
        final long offsetMs;
        final Command command;

        Click(long offsetMs, Command command) {
            this.offsetMs = offsetMs;
            this.command = command;
        }
    }

    static final class Plan {
        final List<Click> clicks;
        final Cursor cursor;

        Plan(List<Click> clicks, Cursor cursor) {
            this.clicks = Collections.unmodifiableList(clicks);
            this.cursor = cursor;
        }

        long doneAtMs() {
            return cursor.doneAtMs();
        }
    }

    /** Mutable delay cursor carried across a merged hotkey pair. */
    static final class Cursor {
        long delayMs;
        boolean firstInv;

        Cursor(long delayMs, boolean firstInv) {
            this.delayMs = delayMs;
            this.firstInv = firstInv;
        }

        void addExplicitDelay(String value) {
            delayMs += parseDelayMs(value);
        }

        /**
         * Advance for one click command and return the fire-at offset from the
         * start of this swap chain (the value passed to {@code invokeAfter}).
         */
        long scheduleClick(String type) {
            if (isInventoryClick(type)) {
                delayMs += firstInv
                        ? ClientThreadGuard.firstInvClickDelayMs()
                        : ClientThreadGuard.ahkSafeInvGapMs();
                firstInv = false;
            } else {
                delayMs += ClientThreadGuard.sameTickGapMs();
            }
            return delayMs;
        }

        long doneAtMs() {
            return delayMs + ClientThreadGuard.sameTickGapMs();
        }
    }
}
