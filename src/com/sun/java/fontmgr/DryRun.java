package com.sun.java.fontmgr;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code -Droatz.dryrun=true}: combat decisions still run, client packets do
 * not. Actions are recorded for the replay harness and the LOG tail.
 *
 * <p>Off by default. Attach-after-login is unchanged when the flag is unset.
 */
public final class DryRun {

    private static final List<String> ACTIONS = Collections.synchronizedList(new ArrayList<>());

    private DryRun() {}

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("roatz.dryrun", "false"));
    }

    public static void record(String kind, String detail) {
        String line = kind + (detail == null || detail.isEmpty() ? "" : " " + detail);
        ACTIONS.add(line);
        FontManager.log("[DryRun] " + line);
    }

    public static List<String> actions() {
        synchronized (ACTIONS) {
            return new ArrayList<>(ACTIONS);
        }
    }

    public static void resetForTest() {
        ACTIONS.clear();
    }

    /**
     * Drop-in for {@code Client.doAction}. Static so
     * {@code Method.invoke(client, args...)} still binds the ten client
     * arguments; the receiver is ignored.
     */
    public static void doActionSink(int p0, int p1, int opcode, int id, int extra,
                                    int unused, String option, String target, int a, int b) {
        record("doAction", option + " " + target + " op=" + opcode + " id=" + id);
    }

    static Method doActionSinkMethod() {
        try {
            return DryRun.class.getMethod("doActionSink",
                    int.class, int.class, int.class, int.class, int.class,
                    int.class, String.class, String.class, int.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
