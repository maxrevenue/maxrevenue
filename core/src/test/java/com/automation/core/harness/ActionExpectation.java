package com.automation.core.harness;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.AttackAction;
import com.automation.core.model.EatAction;
import com.automation.core.model.SpecAction;

import java.util.Locale;
import java.util.Objects;

/**
 * Parsed {@code executedAction} label from an EchoForge NDJSON tick line.
 *
 * <p>Accepts the compact legacy encodings used by {@code TickRecorder}:
 * {@code EAT:3}, {@code SPEC:AGS}, {@code ATTACK}, plus a few common aliases.
 */
public record ActionExpectation(String raw, Kind kind, String detail) {

    public enum Kind {
        EAT,
        SPEC,
        ATTACK,
        OTHER
    }

    public ActionExpectation {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail;
    }

    public static ActionExpectation parse(String executedAction) {
        String raw = executedAction == null ? "" : executedAction.trim();
        if (raw.isEmpty()) {
            return new ActionExpectation(raw, Kind.OTHER, "");
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.startsWith("EAT:") || upper.startsWith("EAT ")) {
            return new ActionExpectation(raw, Kind.EAT, raw.substring(4).trim());
        }
        if (upper.startsWith("SPEC:") || upper.startsWith("SPEC ")) {
            return new ActionExpectation(raw, Kind.SPEC, raw.substring(5).trim());
        }
        if (upper.equals("ATTACK") || upper.startsWith("ATTACK:") || upper.startsWith("ATTACK@")
                || upper.startsWith("REATTACK")) {
            return new ActionExpectation(raw, Kind.ATTACK, "");
        }
        return new ActionExpectation(raw, Kind.OTHER, raw);
    }

    /** True when {@code intent} is the ActionIntent class this expectation encodes. */
    public boolean matches(ActionIntent intent) {
        if (intent == null) {
            return false;
        }
        return switch (kind) {
            case EAT -> intent instanceof EatAction eat && detailMatchesSlot(eat.inventorySlot());
            case SPEC -> intent instanceof SpecAction;
            case ATTACK -> intent instanceof AttackAction;
            case OTHER -> false;
        };
    }

    private boolean detailMatchesSlot(int slot) {
        if (detail.isEmpty()) {
            return true;
        }
        try {
            return Integer.parseInt(detail) == slot;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    public Class<? extends ActionIntent> expectedType() {
        return switch (kind) {
            case EAT -> EatAction.class;
            case SPEC -> SpecAction.class;
            case ATTACK -> AttackAction.class;
            case OTHER -> ActionIntent.class;
        };
    }
}
