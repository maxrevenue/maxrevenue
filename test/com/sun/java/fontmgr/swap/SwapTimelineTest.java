package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the rescued 160ms different-hotkey merge and per-command AHK
 * gaps. Would fail if same-name swaps merged, if {@code r:} were treated as an
 * inventory click, or if {@code delay:} did not advance the cursor.
 */
public class SwapTimelineTest {

    @Test
    public void differentNamesInsideWindowMerge() {
        assertTrue(SwapTimeline.shouldMerge(100L, 160L, 1, 1, "unequip", "veng"));
        assertTrue(SwapTimeline.shouldMerge(160L, 160L, 3, 3, "DH", "Ice"));
    }

    @Test
    public void sameNameInsideWindowDoesNotMerge() {
        assertFalse(SwapTimeline.shouldMerge(50L, 160L, 1, 1, "veng", "veng"));
        assertFalse(SwapTimeline.shouldMerge(50L, 160L, 1, 1, "Veng", "veng"));
    }

    @Test
    public void windowExpiryAndCancelDoNotMerge() {
        assertFalse(SwapTimeline.shouldMerge(161L, 160L, 1, 1, "unequip", "veng"));
        assertFalse(SwapTimeline.shouldMerge(10L, 160L, 1, 2, "unequip", "veng"));
        assertFalse(SwapTimeline.shouldMerge(10L, 160L, 1, 1, "", "veng"));
        assertFalse(SwapTimeline.shouldMerge(10L, 160L, 1, 1, "unequip", null));
    }

    @Test
    public void removeAndPrayerAreNotInventoryClicks() {
        assertTrue(SwapTimeline.isInventoryClick("e"));
        assertTrue(SwapTimeline.isInventoryClick("drop"));
        assertTrue(SwapTimeline.isInventoryClick("u"));
        assertFalse(SwapTimeline.isInventoryClick("r"));
        assertFalse(SwapTimeline.isInventoryClick("p"));
        assertFalse(SwapTimeline.isInventoryClick("spec"));
        assertFalse(SwapTimeline.isInventoryClick("c"));
        assertFalse(SwapTimeline.isInventoryClick("delay"));
    }

    @Test
    public void delayCommandParsesDefaultClampAndGarbage() {
        assertEquals(120L, SwapTimeline.parseDelayMs(null));
        assertEquals(120L, SwapTimeline.parseDelayMs(""));
        assertEquals(120L, SwapTimeline.parseDelayMs("nope"));
        assertEquals(200L, SwapTimeline.parseDelayMs("200"));
        assertEquals(0L, SwapTimeline.parseDelayMs("-5"));
        assertEquals(3000L, SwapTimeline.parseDelayMs("99999"));
    }

    @Test
    public void inventoryThenInventoryUsesAhkGap() {
        SwapTimeline.Cursor cur = new SwapTimeline.Cursor(0L, true);
        long first = cur.scheduleClick("e");
        long second = cur.scheduleClick("e");
        assertTrue(first >= 72L && first <= 99L, "first inv=" + first);
        long gap = second - first;
        assertTrue(gap >= 72L && gap <= 99L, "ahk gap=" + gap);
        assertFalse(cur.firstInv);
    }

    @Test
    public void inventoryThenPrayerUsesSameTickGap() {
        SwapTimeline.Cursor cur = new SwapTimeline.Cursor(0L, true);
        long equip = cur.scheduleClick("e");
        long pray = cur.scheduleClick("p");
        long gap = pray - equip;
        assertTrue(gap >= 9L && gap <= 18L, "same-tick gap=" + gap);
    }

    @Test
    public void removeUsesSameTickGapSoVengCanShareTheTick() {
        SwapTimeline.Cursor cur = new SwapTimeline.Cursor(0L, true);
        long unequip = cur.scheduleClick("r");
        assertTrue(unequip >= 9L && unequip <= 18L, "r: gap=" + unequip);
        assertTrue(cur.firstInv, "r: must not consume the first-inventory slot");
        long veng = cur.scheduleClick("c");
        assertTrue(veng - unequip >= 9L && veng - unequip <= 18L);
        long thenEquip = cur.scheduleClick("e");
        assertTrue(thenEquip - veng >= 72L, "first real inv after r:+c: must still be AHK");
    }

    @Test
    public void explicitDelaySitsBetweenInventoryClicks() {
        SwapTimeline.Cursor cur = new SwapTimeline.Cursor(0L, true);
        long a = cur.scheduleClick("e");
        cur.addExplicitDelay("200");
        long b = cur.scheduleClick("e");
        long gap = b - a;
        assertTrue(gap >= 200L + 72L && gap <= 200L + 99L, "delay+ahk gap=" + gap);
    }

    @Test
    public void mergedCursorContinuesTheTimelineInsteadOfResetting() {
        SwapTimeline.Cursor first = new SwapTimeline.Cursor(0L, true);
        first.scheduleClick("r");
        first.scheduleClick("c");
        SwapTimeline.Cursor merged = new SwapTimeline.Cursor(first.delayMs, first.firstInv);
        long specAt = merged.scheduleClick("spec");
        assertTrue(specAt > first.delayMs);
        assertEquals(first.delayMs + (specAt - first.delayMs), specAt);
    }

    @Test
    public void mergeWindowConstantIs160ms() {
        assertEquals(160L, SwapTimeline.MERGE_WINDOW_MS);
        assertTrue(ClientThreadGuard.ahkSafeInvGapMs() >= 72L);
    }
}
