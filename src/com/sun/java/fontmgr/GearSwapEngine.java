package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic multi-item gear switching with Gaussian humanized gaps.
 * Stays above Roat's ~70 ms AHK detection threshold via {@link Humanizer}.
 */
public final class GearSwapEngine {

    public static final class SwapStep {
        public final int invSlot;
        public final int itemId;
        public final String label;

        public SwapStep(int invSlot, int itemId, String label) {
            this.invSlot = invSlot;
            this.itemId = itemId;
            this.label = label != null ? label : "";
        }
    }

    private final CombatScript script;
    private volatile boolean busy;

    public GearSwapEngine(CombatScript script) {
        this.script = Objects.requireNonNull(script, "script");
    }

    public boolean isBusy() { return busy; }

    /**
     * Queue a weapon-first swap chain with humanized inter-equip gaps.
     */
    public void queueSwap(String label, List<SwapStep> steps, Runnable afterWeapon, Runnable finale) {
        if (steps == null || steps.isEmpty() || busy) return;
        busy = true;
        final String tag = label != null ? label : "SWAP";
        long delay = Humanizer.firstEquipDelayMs();
        boolean weaponScheduled = false;
        long weaponAt = delay;

        for (int i = 0; i < steps.size(); i++) {
            final SwapStep step = steps.get(i);
            final boolean isWeapon = i == 0;
            UiExecutor.schedule(() -> {
                script.wieldItemPublic(step.invSlot, step.itemId);
                FontManager.debug("[GearSwap] " + tag + " " + step.label + " slot=" + step.invSlot);
            }, delay);
            if (!weaponScheduled && isWeapon) {
                weaponScheduled = true;
                weaponAt = delay;
            }
            delay += Humanizer.invGapMs();
        }

        if (afterWeapon != null) {
            long prayAt = (weaponScheduled ? weaponAt : Humanizer.firstEquipDelayMs())
                    + Humanizer.tickDelayMs();
            UiExecutor.schedule(afterWeapon, prayAt);
        }

        if (finale != null) {
            UiExecutor.schedule(() -> {
                try { finale.run(); } finally { busy = false; }
            }, delay + Humanizer.tickDelayMs());
        } else {
            UiExecutor.schedule(() -> busy = false, delay);
        }
    }

    /** Granite maul wield → piety → spec (punish / follow-up chain). */
    public void queueGmaulSpec(String label, Runnable afterSpec) {
        SwapStep gmaul = findFirst(InventoryTracker::isGmaul);
        if (gmaul == null) return;
        List<SwapStep> steps = new ArrayList<>(1);
        steps.add(gmaul);
        queueSwap(label, steps, script::activatePietyPublic,
            () -> {
                script.specAndAttackPublic();
                if (afterSpec != null) afterSpec.run();
            });
    }

    /** VLS wield → piety → spec; falls back to gmaul when VLS is missing. */
    public void queueVlsSpec(String label, Runnable afterSpec) {
        SwapStep vls = findFirst(InventoryTracker::isVls);
        if (vls == null) {
            queueGmaulSpec(label, afterSpec);
            return;
        }
        List<SwapStep> steps = new ArrayList<>(1);
        steps.add(vls);
        queueSwap(label, steps, script::activatePietyPublic,
            () -> {
                script.specAndAttackPublic();
                if (afterSpec != null) afterSpec.run();
            });
    }

    private SwapStep findFirst(ItemMatcher matcher) {
        int[] inv = script.getInventorySnapshot();
        for (int slot = 0; slot < inv.length; slot++) {
            int raw = inv[slot];
            if (raw <= 0) continue;
            int id = raw - 1;
            String name = script.resolveItemNamePublic(id);
            if (matcher.matches(id, name)) {
                return new SwapStep(slot, id, InventoryTracker.stripName(name));
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ItemMatcher {
        boolean matches(int itemId, String name);
    }
}
