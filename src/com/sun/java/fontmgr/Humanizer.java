package com.sun.java.fontmgr;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps auto-play from looking like a perfect machine.
 *
 * Counter-spec and kill-window dumps stay immediate. Passive spec / eat
 * decisions get jitter so every incoming 1s does not produce the same packet
 * sequence on the same tick.
 */
final class Humanizer {

    private Humanizer() {}

    static int tickDelayMs() {
        return tickAlignMs();
    }

    /**
     * How far into the 600ms game tick we start work. Stay early so a same-tick
     * orb+axe+eat chain is still accepted before the server closes the window.
     */
    static int tickAlignMs() {
        return 8 + ThreadLocalRandom.current().nextInt(15);
    }

    /**
     * Gap between two inventory clicks on the same tick. Instant back-to-back
     * doAction calls get dropped (anti-drag / one-click-wins). Must stay far
     * under 600ms even for 10 orb clicks: 10 × 18ms = 180ms.
     */
    static int sameTickClickGapMs() {
        return 9 + ThreadLocalRandom.current().nextInt(10);
    }

    static void sameTickPause() {
        try {
            Thread.sleep(sameTickClickGapMs());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Eat HP line with a small wobble so it is not always exactly N. */
    static int eatThreshold(int configured) {
        int base = Math.max(1, configured);
        int wobble = ThreadLocalRandom.current().nextInt(3) - 1; // -1..+1
        return Math.max(1, base + wobble);
    }

    /**
     * True when a non-critical spec dump should wait one extra tick.
     * Never used for opponent-spec counters or KO windows.
     */
    static boolean delayPassiveSpec() {
        return ThreadLocalRandom.current().nextInt(100) < 7;
    }

    /** Skip a passive auto-eat this tick (player might be about to food). */
    static boolean skipPassiveEat() {
        return ThreadLocalRandom.current().nextInt(100) < 4;
    }

    /** Gap between inventory equips — above Roat ~70ms AhkDetection threshold. */
    static int invGapMs() {
        return 95 + ThreadLocalRandom.current().nextInt(56);
    }

    /** First equip in a switch — never below AhkDetection click threshold. */
    static int firstEquipDelayMs() {
        return 72 + ThreadLocalRandom.current().nextInt(28);
    }

    /** Extra tick before an auto re-freeze so the recast is not a metronome. */
    static boolean delayNhRecast() {
        return ThreadLocalRandom.current().nextInt(100) < 18;
    }

    /** Ticks before unfreeze to pre-wield melee (1–2, not robotic). */
    static int nhMeleePrepTicks() {
        return 1 + ThreadLocalRandom.current().nextInt(2);
    }

    /** Occasional missed def-pray switch — looks less bot-like. */
    static boolean defPrayMiss() {
        return ThreadLocalRandom.current().nextInt(100) < 8;
    }

    /**
     * Eat-punish reaction center — clipped Gaussian via
     * {@link ClientThreadGuard#gaussianDelayMs(long, long)}.
     * Fast enough to land before they can react; not instant.
     */
    static long punishReactionMs() {
        long mean = 88 + ThreadLocalRandom.current().nextInt(42);
        long sigma = Math.max(12L, mean / 5L);
        return ClientThreadGuard.gaussianDelayMs(mean, sigma);
    }

    /** Gear-swap chain mean delay between non-first equips. */
    static long gearSwapMeanMs() {
        return 95 + ThreadLocalRandom.current().nextInt(56);
    }
}
