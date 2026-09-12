package roatz.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Forwards a small, explicitly allow-listed set of diagnostic {@code -D} flags
 * into the game JVM — which is where the agent actually runs, so this is the only
 * way to turn things like the tick recorder on from the normal launcher.
 *
 * <p><b>Why an allow-list and not a passthrough.</b> The agent honours
 * {@code -Dfontmgr.license.bypass=true} to skip the licence gate entirely (see
 * {@code LicenseGate}), and {@code launch.ps1} uses it for the dev loop. This
 * launcher ships to buyers. A general passthrough would therefore let any buyer
 * set one environment variable and run without a licence, which would defeat the
 * whole point of the licensing. So: an exact allow-list of property names, plus a
 * refusal of anything mentioning {@code license} or {@code bypass} as a second,
 * independent layer.
 *
 * <p>Usage — set before launching:
 * <pre>
 *   $env:ROATZ_AGENT_FLAGS = "-Droatz.rec=true"
 * </pre>
 * A value containing spaces is not supported (no quoting), so use
 * {@code -Droatz.rec=true} for the default output path rather than a path with a
 * space in it.
 */
final class AgentFlags {

    static final String ENV = "ROATZ_AGENT_FLAGS";

    /**
     * Exact property names the operator may forward. Nothing else is accepted, so
     * adding a diagnostic means adding it here deliberately.
     */
    private static final List<String> ALLOWED = Arrays.asList(
            "roatz.rec",            // tick recorder — the reason this exists
            "roatz.defpray.gear",   // gear-corroborated defensive prayer
            "agent.filelog",        // agent file logging
            "agent.debug",
            "agent.cmd",            // command socket (off by default)
            "fontmgr.debug",
            "fontmgr.praylog",      // high-volume prayer diagnostics
            "fontmgr.overlay.detail",
            "fontmgr.attach.verbose"
    );

    /** Refused even if a name above were ever widened to a prefix. */
    private static final List<String> FORBIDDEN = Arrays.asList("license", "bypass");

    static final class Parsed {
        final List<String> accepted = new ArrayList<>();
        final List<String> rejected = new ArrayList<>();

        boolean isEmpty() {
            return accepted.isEmpty();
        }
    }

    /** Parses {@link #ENV}. Never throws, never returns null. */
    static Parsed fromEnv() {
        return parse(System.getenv(ENV));
    }

    /**
     * Pure and total: splits on whitespace and keeps only well-formed, allow-listed
     * flags. Everything else is collected in {@code rejected} so the launcher can
     * say why, rather than silently dropping it.
     */
    static Parsed parse(String raw) {
        Parsed out = new Parsed();
        if (raw == null) return out;
        for (String token : raw.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (isAllowed(token)) {
                out.accepted.add(token);
            } else {
                out.rejected.add(token);
            }
        }
        return out;
    }

    private static boolean isAllowed(String token) {
        if (!token.startsWith("-D")) return false;
        int eq = token.indexOf('=');
        if (eq < 3) return false;                       // need -Dname=value
        String name = token.substring(2, eq);
        String lower = token.toLowerCase(Locale.ROOT);
        for (String bad : FORBIDDEN) {
            if (lower.contains(bad)) return false;
        }
        return ALLOWED.contains(name);
    }

    private AgentFlags() {}
}
