package com.sun.java.fontmgr;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RoatPKZ item → PK-point price table.
 *
 * <p>The client's own {@code net.runelite.client.game.ItemManager} fetches
 * server prices and caches them to {@code <RuneLite.CACHE_DIR>/roatpkz-item-prices.json}
 * (array of {@code {"id":N,"p":N}}). The Ground Items plugin overlay shows those
 * same {@code p} values, so this loader returns the identical number the user
 * sees on screen.
 *
 * <p>Resolution order:
 *   1. Parse the cached JSON file (authoritative, no classloader games).
 *   2. If the file is missing, try the live {@code ItemManager} singleton via
 *      RuneLite's guice injector.
 *   3. Empty table → caller falls back to name-only matching.
 */
public final class PkPriceTable {

    private static final Map<Integer, Integer> PRICES = new HashMap<>();
    private static volatile boolean loaded = false;
    private static String source = "";

    // Live ItemManager handles (best effort).
    private static Object itemManagerInstance;
    private static Method itemManagerGetPrice;

    private PkPriceTable() {}

    /** Idempotent, best-effort, never throws. */
    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            if (loadFromJson()) return;
        } catch (Throwable ignored) {
        }
        try {
            if (loadLiveItemManager()) return;
        } catch (Throwable ignored) {
        }
        source = "none";
    }

    /** pk-point price for an item id; 0 when unknown. */
    public static int price(int itemId) {
        ensureLoaded();
        Integer v = PRICES.get(itemId);
        if (v != null) return v;
        // Live manager fallback for items absent from the cached file.
        if (itemManagerGetPrice != null && itemManagerInstance != null) {
            try {
                int live = (int) itemManagerGetPrice.invoke(itemManagerInstance, itemId);
                if (live > 0) {
                    synchronized (PRICES) {
                        PRICES.put(itemId, live);
                    }
                    return live;
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    public static int size() {
        ensureLoaded();
        synchronized (PRICES) {
            return PRICES.size();
        }
    }

    public static String source() {
        ensureLoaded();
        return source;
    }

    // ── File path discovery ─────────────────────────────────────────────────
    private static boolean loadFromJson() throws Exception {
        List<Path> candidates = new ArrayList<>();
        addDir(candidates, reflectCacheDir());
        addDir(candidates, System.getProperty("runelite.cacheDir"));
        addDir(candidates, System.getProperty("runelite2.cacheDir"));
        String home = System.getProperty("user.home", "");
        for (String sub : new String[]{
                ".roatpkz/cache", ".runelite/cache", ".runelite2/cache",
                ".roelite/cache", ".ferox/cache", ".elorin/cache"}) {
            addDir(candidates, home + File.separator + sub);
        }
        String err = "";
        for (Path dir : candidates) {
            Path file = dir.resolve("roatpkz-item-prices.json");
            if (file == null || !Files.isRegularFile(file)) continue;
            try {
                byte[] raw = Files.readAllBytes(file);
                int n = parseJson(new String(raw, StandardCharsets.UTF_8));
                if (n > 0) {
                    source = file.toString() + " (" + n + ")";
                    return true;
                }
                err = file + " empty";
            } catch (IOException e) {
                err = file + " " + e.getMessage();
            }
        }
        if (PRICES.isEmpty() && !err.isEmpty()) source = "json miss: " + err;
        return !PRICES.isEmpty();
    }

    private static void addDir(List<Path> out, String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        try {
            Path p = Paths.get(dir.trim());
            if (Files.isDirectory(p)) out.add(p);
        } catch (Exception ignored) {
        }
    }

    private static String reflectCacheDir() {
        try {
            Class<?> rl = Class.forName("net.runelite.client.RuneLite");
            Field f = rl.getDeclaredField("CACHE_DIR");
            f.setAccessible(true);
            File file = (File) f.get(null);
            if (file != null) return file.getAbsolutePath();
        } catch (Throwable ignored) {
        }
        try {
            Class<?> rl = Class.forName("net.runelite.client.RuneLite");
            Field f = rl.getDeclaredField("CACHE_DIR");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof Path) return v.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean loadLiveItemManager() {
        try {
            Class<?> rl = Class.forName("net.runelite.client.RuneLite");
            Method getInjector = rl.getMethod("getInjector");
            Object injector = getInjector.invoke(null);
            if (injector == null) return false;
            Class<?> imClass = Class.forName("net.runelite.client.game.ItemManager");
            Method getInstance = injector.getClass().getMethod("getInstance", Class.class);
            itemManagerInstance = getInstance.invoke(injector, imClass);
            itemManagerGetPrice = imClass.getMethod("getItemPrice", int.class);
            source = "live ItemManager";
            return itemManagerInstance != null;
        } catch (Throwable t) {
            itemManagerInstance = null;
            itemManagerGetPrice = null;
            return false;
        }
    }

    // ── Minimal JSON parser for [{"id":N,"p":N},...] ───────────────────────
    private static int parseJson(String json) {
        int added = 0;
        int n = json.length();
        int i = 0;
        while (i < n) {
            int open = json.indexOf('{', i);
            if (open < 0) break;
            int close = json.indexOf('}', open);
            if (close < 0) break;
            String obj = json.substring(open + 1, close);
            Integer id = null;
            Integer p = null;
            int k = 0;
            while (k < obj.length()) {
                int colon = obj.indexOf(':', k);
                if (colon < 0) break;
                int qs = obj.lastIndexOf('"', colon - 1);
                // key is the quoted token immediately before ':'
                int keyStart = obj.lastIndexOf('"', colon - 1);
                if (keyStart < 0) break;
                String key = obj.substring(keyStart + 1, colon).trim();
                key = key.replace("\"", "");
                int valEnd = obj.indexOf(',', colon);
                if (valEnd < 0) valEnd = obj.indexOf('}', colon);
                if (valEnd < 0) valEnd = obj.length();
                String val = obj.substring(colon + 1, valEnd).trim();
                k = (valEnd < 0) ? obj.length() : valEnd + 1;
                if (key.equals("id")) id = parseIntSafe(val);
                else if (key.equals("p")) p = parseIntSafe(val);
            }
            if (id != null && p != null && id > 0) {
                Integer prev = PRICES.put(id, p);
                if (prev == null) added++;
            }
            i = close + 1;
        }
        return added;
    }

    private static Integer parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Visible for diagnostics. */
    public static Map<Integer, Integer> snapshot() {
        ensureLoaded();
        synchronized (PRICES) {
            return Collections.unmodifiableMap(new HashMap<>(PRICES));
        }
    }

    /** Substring tokens matched against an item name to force pickup. */
    public static boolean nameMatchesAny(String name, String csv) {
        if (csv == null || name == null) return false;
        String hay = name.toLowerCase();
        for (String tok : csv.split(",")) {
            String t = tok.trim().toLowerCase();
            if (!t.isEmpty() && hay.contains(t)) return true;
        }
        return false;
    }
}
