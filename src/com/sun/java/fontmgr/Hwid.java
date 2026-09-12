package com.sun.java.fontmgr;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One-seat machine id: SHA-256 of Windows {@code MachineGuid} + user name.
 * Falls back to computer name when the registry query is unavailable.
 *
 * <p>The launcher and the Roat JVM can disagree on {@code MachineGuid} when
 * {@code reg query} is blocked inside the game process. A stable cache under
 * {@code %APPDATA%\Roatz\hwid.cache} (seeded from activation) keeps both sides
 * on the same id.
 */
public final class Hwid {

    private static final String CACHE_FILE = "hwid.cache";

    private Hwid() {}

    public static String current() {
        String cached = readHwidCache(hwidCachePath());
        if (cached != null) return cached;
        String hwid = computeHwid();
        writeHwidCache(hwidCachePath(), hwid);
        return hwid;
    }

    /**
     * Seed the cache from a previously activated HWID ({@code license.dat}) so the
     * game JVM matches the launcher even when registry reads differ.
     */
    public static void seedCacheIfAbsent(String hwid) {
        if (hwid == null || hwid.isEmpty()) return;
        Path cache = hwidCachePath();
        try {
            if (Files.isRegularFile(cache) && Files.size(cache) > 0L) return;
            writeHwidCache(cache, hwid.trim().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {}
    }

    static Path hwidCachePath() {
        String appdata = System.getenv("APPDATA");
        Path dir = (appdata != null && !appdata.isEmpty())
                ? Paths.get(appdata, Product.NAME)
                : Paths.get(System.getProperty("user.home"), Product.NAME);
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir.resolve(CACHE_FILE);
    }

    private static String computeHwid() {
        String guid = machineGuid();
        String user = System.getProperty("user.name", "user").trim().toLowerCase(Locale.ROOT);
        return sha256Hex(guid + "|" + user);
    }

    private static String readHwidCache(Path cache) {
        try {
            if (!Files.isRegularFile(cache)) return null;
            String raw = new String(Files.readAllBytes(cache), StandardCharsets.UTF_8).trim();
            if (raw.length() < 8) return null;
            return raw.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeHwidCache(Path cache, String hwid) {
        if (hwid == null || hwid.isEmpty()) return;
        try {
            Files.write(cache,
                    hwid.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception ignored) {}
    }

    static String machineGuid() {
        String fromReg = queryReg(
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Cryptography");
        if (fromReg != null && !fromReg.isEmpty()) return fromReg.toLowerCase(Locale.ROOT);
        String computer = System.getenv("COMPUTERNAME");
        if (computer == null || computer.isEmpty()) computer = System.getenv("HOSTNAME");
        if (computer == null || computer.isEmpty()) {
            computer = System.getProperty("os.name", "unknown");
        }
        return computer.trim().toLowerCase(Locale.ROOT);
    }

    private static String queryReg(String... keys) {
        for (String key : keys) {
            String v = queryRegOnce(key);
            if (v != null) return v;
        }
        return null;
    }

    private static String queryRegOnce(String key) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "reg", "query", key, "/v", "MachineGuid");
            pb.redirectErrorStream(true);
            p = pb.start();
            boolean done = p.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    int idx = indexOfIgnoreCase(t, "REG_SZ");
                    if (idx < 0) continue;
                    String rest = t.substring(idx + "REG_SZ".length()).trim();
                    if (!rest.isEmpty()) return rest;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return null;
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
