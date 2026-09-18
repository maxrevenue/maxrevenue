package tickbus;

import com.bot.core.golden.GoldenParityClassifier;
import com.bot.core.golden.GoldenParityClassifier.Category;
import com.bot.core.golden.ParityInput;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Offline verdict for shadow sessions — classification via {@link ParityInput} only.
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
        Map<Long, LegacyRow> legacyRows = new HashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"recordKind\":\"orch\"")) {
                    long tick = readLong(line, "tickIndex");
                    orchSummary.put(tick, summarizeOrchWinners(line));
                } else if (line.contains("\"recordKind\":\"legacy\"")) {
                    long tick = readLong(line, "tickIndex");
                    legacyRows.put(tick, new LegacyRow(
                            readString(line, "legacyAction"),
                            readString(line, "uncomparableSubtype")));
                }
            }
        }

        int match = 0;
        int ruleDiff = 0;
        int priorityDiff = 0;
        int timingDiff = 0;
        int feasibility = 0;
        int uncomparableNoOpinion = 0;
        int uncomparableOutOfVocab = 0;

        Set<Long> allTicks = new HashSet<>(orchSummary.keySet());
        allTicks.addAll(legacyRows.keySet());

        for (long tick : allTicks) {
            LegacyRow legacyRow = legacyRows.get(tick);
            String legacy = legacyRow == null ? "" : legacyRow.action;
            String subtype = legacyRow == null ? "" : legacyRow.uncomparableSubtype;
            String orch = orchSummary.get(tick);
            ParityInput input = new ParityInput(tick, orch, legacy, subtype);
            switch (GoldenParityClassifier.classify(input)) {
                case MATCH:
                    match++;
                    break;
                case RULE_DIFF:
                    ruleDiff++;
                    break;
                case PRIORITY_DIFF:
                    priorityDiff++;
                    break;
                case TIMING_DIFF:
                    timingDiff++;
                    break;
                case FEASIBILITY:
                    feasibility++;
                    break;
                case UNCOMPARABLE_NO_OPINION:
                    uncomparableNoOpinion++;
                    break;
                case UNCOMPARABLE_OUT_OF_VOCAB:
                    uncomparableOutOfVocab++;
                    break;
                default:
                    break;
            }
        }

        int uncomparable = uncomparableNoOpinion + uncomparableOutOfVocab;
        int comparable = match + ruleDiff + priorityDiff + timingDiff + feasibility;
        System.out.println("GoldenDiff verdict for " + path);
        System.out.println("  MATCH=" + match);
        System.out.println("  RULE_DIFF=" + ruleDiff);
        System.out.println("  PRIORITY_DIFF=" + priorityDiff);
        System.out.println("  TIMING_DIFF=" + timingDiff);
        System.out.println("  FEASIBILITY=" + feasibility);
        System.out.println("  UNCOMPARABLE=" + uncomparable + " (excluded from rates)");
        System.out.println("    NO_OPINION=" + uncomparableNoOpinion);
        System.out.println("    OUT_OF_VOCAB=" + uncomparableOutOfVocab);
        if (comparable > 0) {
            System.out.printf("  MATCH_RATE=%.4f (%d comparable ticks)%n",
                    (double) match / (double) comparable, comparable);
        }
    }

    private static String summarizeOrchWinners(String line) {
        int idx = line.indexOf("\"winners\"");
        if (idx < 0) {
            return "";
        }
        return line.substring(idx);
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

    private static final class LegacyRow {
        final String action;
        final String uncomparableSubtype;

        LegacyRow(String action, String uncomparableSubtype) {
            this.action = action;
            this.uncomparableSubtype = uncomparableSubtype == null ? "" : uncomparableSubtype;
        }
    }
}
