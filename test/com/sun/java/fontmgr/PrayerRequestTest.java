package com.sun.java.fontmgr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An explicit Swapper prayer request must not be silently downgraded.
 *
 * <p>The auto-prayer deliberately takes the first available candidate for a
 * style — {@code Mystic Might} before {@code Augury}, {@code Eagle Eye} before
 * {@code Rigour} — so that it still puts "a magic attack prayer" up on
 * tournament worlds where the 99s are locked. That candidate ordering leaked
 * into the named-request path, so {@code p:augury} turned on Mystic Might and
 * {@code p:rigour} turned on Eagle Eye.
 */
public class PrayerRequestTest {

    @Test
    public void namedPrayerWinsWhenItIsAvailable() {
        assertEquals("AUGURY", PrayerController.resolveRequestedOffensivePrayer(
                "AUGURY", AnimationDb.AttackStyle.MAGIC, false));
        assertEquals("RIGOUR", PrayerController.resolveRequestedOffensivePrayer(
                "RIGOUR", AnimationDb.AttackStyle.RANGED, false));
        assertEquals("PIETY", PrayerController.resolveRequestedOffensivePrayer(
                "PIETY", AnimationDb.AttackStyle.MELEE, false));
        assertEquals("CHIVALRY", PrayerController.resolveRequestedOffensivePrayer(
                "CHIVALRY", AnimationDb.AttackStyle.MELEE, false));
    }

    @Test
    public void fallsBackToTheStyleCandidateOnlyWhenTheNamedOneIsUnavailable() {
        assertEquals("MYSTIC_MIGHT", PrayerController.resolveRequestedOffensivePrayer(
                "AUGURY", AnimationDb.AttackStyle.MAGIC, true));
        assertEquals("EAGLE_EYE", PrayerController.resolveRequestedOffensivePrayer(
                "RIGOUR", AnimationDb.AttackStyle.RANGED, true));
    }

    @Test
    public void anUnnamedRequestStillUsesTheStyleCandidate() {
        assertEquals("MYSTIC_MIGHT", PrayerController.resolveRequestedOffensivePrayer(
                null, AnimationDb.AttackStyle.MAGIC, false));
        assertEquals("EAGLE_EYE", PrayerController.resolveRequestedOffensivePrayer(
                null, AnimationDb.AttackStyle.RANGED, false));
    }
}
