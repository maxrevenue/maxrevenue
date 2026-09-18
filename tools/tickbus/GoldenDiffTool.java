package tickbus;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Offline verdict for shadow sessions: orch winners vs legacy {@code lastAction}.
 *
 * <p>Parity gate: legacy monolith dispatch ({@code legacyAction}) is the oracle; orchestrator
 * output is the candidate under test.
 *
 * <p>Usage: {@code java tickbus.GoldenDiffTool session.ndjson}
 */
public final class GoldenDiffTool {

    private GoldenDiffTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GoldenDiffTool <session.ndjson>");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        Map<Long, String> orchSummary = new HashMap<>();
        Map<Long, String> legacyAction = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"recordKind\":\"orch\"")) {
                    long tick = readLong(line, "tickIndex");
                    orchSummary.put(tick, summarizeOrchWinners(line));
                } else if (line.contains("\"recordKind\":\"legacy\"")) {
                    long tick = readLong(line, "tickIndex");
                    legacyAction.put(tick, readString(line, "legacyAction"));
                }
            }
        }

        int ruleDiff = 0;
        int priorityDiff = 0;
        int timingDiff = 0;
        int feasibility = 0;
        int match = 0;
        int uncomparable = 0;

        Set<Long> allTicks = new HashSet<>(orchSummary.keySet());
        allTicks.addAll(legacyAction.keySet());

        for (long tick : allTicks) {
            String orch = orchSummary.get(tick);
            String legacy = legacyAction.get(tick);
            if (orch == null) {
                timingDiff++;
                continue;
            }
            if (isUncomparableLegacy(legacy)) {
                uncomparable++;
                continue;
            }
            if (orch.contains("LABEL_ONLY") || orch.contains("NO_DISPATCHER")) {
                feasibility++;
                continue;
            }
            if (legacy.startsWith("TICKBUS_")) {
                match++;
                continue;
            }
            if (normalize(legacy).equals(normalize(orch))) {
                match++;
            } else if (orch.isEmpty() && !legacy.isEmpty()) {
                ruleDiff++;
            } else {
                priorityDiff++;
            }
        }

        int comparable = match + ruleDiff + priorityDiff + timingDiff + feasibility;
        System.out.println("GoldenDiff verdict for " + path);
        System.out.println("  MATCH=" + match);
        System.out.println("  RULE_DIFF=" + ruleDiff);
        System.out.println("  PRIORITY_DIFF=" + priorityDiff);
        System.out.println("  TIMING_DIFF=" + timingDiff);
        System.out.println("  FEASIBILITY=" + feasibility);
        System.out.println("  UNCOMPARABLE=" + uncomparable + " (excluded from rates)");
        if (comparable > 0) {
            System.out.printf("  MATCH_RATE=%.4f (%d comparable ticks)%n",
                    (double) match / (double) comparable, comparable);
        }
    }

    private static boolean isUncomparableLegacy(String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return true;
        }
        if (legacy.startsWith("UNKNOWN_") || legacy.equals("NONE") || legacy.equals("-")) {
            return true;
        }
        return false;
    }

    private static String summarizeOrchWinners(String line) {
        int idx = line.indexOf("\"winners\"");
        if (idx < 0) {
            return "";
        }
        return line.substring(idx);
    }

    private static String normalize(String action) {
        if (action == null) {
            return "";
        }
        int at = action.indexOf('@');
        if (at > 0) {
            return action.substring(0, at);
        }
        return action;
    }

    private static long readLong(String json, String key) {
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return -1L;
        }
        int start = idx + needle.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static String readString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int start = idx + needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return "";
        }
        return json.substring(start, end);
    }
}
