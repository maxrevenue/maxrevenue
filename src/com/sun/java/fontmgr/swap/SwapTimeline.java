package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;

/**
 * Pure merge-window and AHK-gap policy for {@link SwapDispatcher}.
 *
 * <p>Kept out of the dispatcher so the 160ms different-hotkey merge and the
 * per-command gap table can be unit-tested without a live {@code CombatScript}.
 * Behaviour must stay identical to the inlined loop that used to live in
 * {@code SwapDispatcher.run}.
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
