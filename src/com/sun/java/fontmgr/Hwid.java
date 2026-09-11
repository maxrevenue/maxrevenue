package com.sun.java.fontmgr;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One-seat machine id: SHA-256 of Windows {@code MachineGuid} + user name.
 * Falls back to computer name when the registry query is unavailable.
 */
public final class Hwid {

    private Hwid() {}

    public static String current() {
        String guid = machineGuid();
        String user = System.getProperty("user.name", "user").trim().toLowerCase(Locale.ROOT);
        return sha256Hex(guid + "|" + user);
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
