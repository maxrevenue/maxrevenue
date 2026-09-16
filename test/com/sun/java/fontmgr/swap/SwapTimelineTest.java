package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        assertTrue(first.delayMs >= 18L && first.delayMs <= 36L,
                "r:+c: two same-tick gaps=" + first.delayMs);
        SwapTimeline.Cursor merged = new SwapTimeline.Cursor(first.delayMs, first.firstInv);
        long specAt = merged.scheduleClick("spec");
        long specGap = specAt - first.delayMs;
        assertTrue(specGap >= 9L && specGap <= 18L, "merged spec offset=" + specGap);
        assertTrue(merged.firstInv, "spec must not consume firstInv");
    }

    @Test
    public void planSchedulesInventoryThenCastWithAhkThenSameTick() {
        List<CommandParser.Command> steps = Arrays.asList(
                cmd("e", "whip"),
                cmd("c", "Ice Barrage"));
        SwapTimeline.Plan plan = SwapTimeline.plan(steps, 0L, true);
        assertEquals(2, plan.clicks.size());
        assertEquals("e", plan.clicks.get(0).command.type);
        assertEquals("c", plan.clicks.get(1).command.type);
        long equip = plan.clicks.get(0).offsetMs;
        long cast = plan.clicks.get(1).offsetMs;
        assertTrue(equip >= 72L && equip <= 99L, "first inv=" + equip);
        assertTrue(cast - equip >= 9L && cast - equip <= 18L, "cast gap=" + (cast - equip));
    }

    @Test
    public void planSkipsDelayCommandsButAdvancesTheCursor() {
        List<CommandParser.Command> steps = Arrays.asList(
                cmd("e", "whip"),
                cmd("delay", "200"),
                cmd("e", "torso"));
        SwapTimeline.Plan plan = SwapTimeline.plan(steps, 0L, true);
        assertEquals(2, plan.clicks.size());
        long gap = plan.clicks.get(1).offsetMs - plan.clicks.get(0).offsetMs;
        assertTrue(gap >= 200L + 72L && gap <= 200L + 99L, "delay+ahk gap=" + gap);
        assertFalse(plan.cursor.firstInv);
    }

    @Test
    public void planMergedContinuesFromStartDelay() {
        List<CommandParser.Command> firstSteps = Arrays.asList(
                cmd("r", "helm"),
                cmd("c", "veng"));
        SwapTimeline.Plan first = SwapTimeline.plan(firstSteps, 0L, true);
        assertEquals(2, first.clicks.size());
        List<CommandParser.Command> spec = Collections.singletonList(cmd("spec", ""));
        SwapTimeline.Plan merged = SwapTimeline.plan(spec, first.cursor.delayMs, first.cursor.firstInv);
        assertEquals(1, merged.clicks.size());
        long specGap = merged.clicks.get(0).offsetMs - first.cursor.delayMs;
        assertTrue(specGap >= 9L && specGap <= 18L, "merged spec offset=" + specGap);
        assertTrue(merged.clicks.get(0).offsetMs > first.clicks.get(1).offsetMs);
    }

    @Test
    public void mergeWindowConstantIs160ms() {
        assertEquals(160L, SwapTimeline.MERGE_WINDOW_MS);
        assertTrue(ClientThreadGuard.ahkSafeInvGapMs() >= 72L);
    }

    private static CommandParser.Command cmd(String type, String value) {
        CommandParser.Command c = new CommandParser.Command();
        c.type = type;
        c.value = value;
        return c;
    }
}
