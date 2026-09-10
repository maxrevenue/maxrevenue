package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.List;

/** Collect-all assertion sink: every check runs, nothing short-circuits. */
public final class Checks {

    private final String label;
    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int passed;

    public Checks(String label) {
        this.label = label;
    }

    public void that(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failures.add(what);
            System.out.println("  FAIL " + what);
        }
    }

    public void equal(String what, Object expected, Object actual) {
        that(what + " [expected=" + expected + " actual=" + actual + "]",
                expected == null ? actual == null : expected.equals(actual));
    }

    /** Recorded even on success, for the log; always counts as a pass. */
    public void note(String what) {
        notes.add(what);
        System.out.println("  note " + what);
        passed++;
    }

    public int passed() {
        return passed;
    }

    public boolean ok() {
        return failures.isEmpty();
    }

    public List<String> failures() {
        return failures;
    }

    public void report() {
        if (ok()) {
            System.out.println("  OK   " + label + ": " + passed + " checks passed");
        } else {
            System.out.println("  FAIL " + label + ": " + failures.size() + " failed, "
                    + passed + " passed");
            for (String f : failures) System.out.println("       - " + f);
        }
    }
}
