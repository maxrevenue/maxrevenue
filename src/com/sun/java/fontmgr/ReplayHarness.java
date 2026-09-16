package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Feeds recorded or synthetic {@link CombatState} ticks through
 * {@link TickDecision} and returns the action sequence plus a metric report.
 *
 * <p>No live client. Seed {@link Humanizer} so gaps are deterministic.
 * {@link CombatScript#onTick} calls the same {@link TickDecision#decide}
 * function, so these numbers describe the shipped bot.
 */
public final class ReplayHarness {

    private ReplayHarness() {}

    /** Test/replay flags. Same fields {@link CombatScript} feeds TickDecision. */
    public static final class Config extends TickDecision.Config {}

    public static final class Metrics {
        public int ticks;
        public int killWindowsEntered;
        public int killWindowsConverted;
        public int missedWindows;
        /** Kill windows closed after a survive-eat and no spec. Not a miss. */
        public int windowsSuppressedBySurvival;
        public int specsFired;
        public int specsOutsideWindow;
        public int wrongOverheadTicks;
        /** Rising-edge count of lethal DH brackets we did not eat. */
        public int oneShotDeaths;
        public int surviveEats;

        private boolean inWindow;
        private boolean specInWindow;
        private boolean ateInWindow;
        private boolean inOneShot;

        void observe(CombatState s, TickDecision d) {
            ticks++;
            if (d.overheadWrong) wrongOverheadTicks++;
            boolean exposed = d.oneShotBracket && s.ourHp > 0 && s.ourHp <= s.estimatedOppDhHit
                    && d.intent != TickDecision.Intent.EAT;
            if (exposed && !inOneShot) {
                oneShotDeaths++;
            }
            inOneShot = exposed;
            if (d.intent == TickDecision.Intent.EAT) surviveEats++;
            if (d.intent == TickDecision.Intent.SPEC) {
                specsFired++;
                if (!d.killWindowOpen) specsOutsideWindow++;
            }

            boolean open = d.killWindowOpen;
            if (open && !inWindow) {
                killWindowsEntered++;
                specInWindow = false;
                ateInWindow = false;
            }
            if (open && d.intent == TickDecision.Intent.SPEC) specInWindow = true;
            if (open && d.intent == TickDecision.Intent.EAT) ateInWindow = true;
            if (!open && inWindow) closeWindow();
            inWindow = open;
        }

        void finish() {
            if (inWindow) closeWindow();
        }

        private void closeWindow() {
            if (specInWindow) killWindowsConverted++;
            else if (ateInWindow) windowsSuppressedBySurvival++;
            else missedWindows++;
            specInWindow = false;
            ateInWindow = false;
        }

        public String report() {
            return "ticks=" + ticks
                    + " windows=" + killWindowsEntered
                    + " converted=" + killWindowsConverted
                    + " missed=" + missedWindows
                    + " suppressed=" + windowsSuppressedBySurvival
                    + " specs=" + specsFired
                    + " specWaste=" + specsOutsideWindow
                    + " wrongOh=" + wrongOverheadTicks
                    + " oneShot=" + oneShotDeaths
                    + " eats=" + surviveEats;
        }
    }

    public static final class Report {
        public final List<TickDecision> decisions;
        public final Metrics metrics;

        Report(List<TickDecision> decisions, Metrics metrics) {
            this.decisions = Collections.unmodifiableList(decisions);
            this.metrics = metrics;
        }

        public List<String> actionSequence() {
            List<String> out = new ArrayList<>(decisions.size());
            for (int i = 0; i < decisions.size(); i++) {
                TickDecision d = decisions.get(i);
                out.add(d.intent + ":" + d.reason);
            }
            return out;
        }
    }

    public static Report run(List<CombatState> ticks, TickDecision.Config cfg) {
        if (cfg == null) cfg = new Config();
        Humanizer.seed(cfg.rngSeed);
        try {
            TickDecision.Session session = new TickDecision.Session();
            List<TickDecision> decisions = new ArrayList<>();
            Metrics metrics = new Metrics();
            if (ticks != null) {
                for (CombatState s : ticks) {
                    TickDecision d = TickDecision.decide(s, cfg, session);
                    decisions.add(d);
                    metrics.observe(s, d);
                }
            }
            metrics.finish();
            return new Report(decisions, metrics);
        } finally {
            Humanizer.unseed();
        }
    }
}
