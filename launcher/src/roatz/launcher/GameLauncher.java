package roatz.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class GameLauncher {

    static final class Session {
        final Process process;
        final long pid;
        Session(Process process, long pid) {
            this.process = process;
            this.pid = pid;
        }
        boolean alive() {
            return process != null && process.isAlive();
        }
    }

    static Session startVanilla(RoatInstall roat) throws Exception {
        if (roat == null || !roat.ready()) {
            throw new IllegalStateException(roat != null && roat.error != null
                    ? roat.error
                    : "Roat PKz was not found.");
        }
        clearDownloadLock(roat.rpkzDir);
        stopOldClients();

        String md5 = md5Hex(roat.gameJar);
        long ts = System.currentTimeMillis() / 1000L;
        List<String> cmd = new ArrayList<>();
        cmd.add(roat.javaExe.toString());
        cmd.add("-Dsun.java2d.dpiaware=true");
        cmd.add("-Dsun.java2d.uiScale=1.0");
        cmd.add("-Drunelite.launcher.nojvm=true");
        cmd.add("-Xmx4g");
        cmd.add("-Xms1g");
        cmd.add("-Xss2m");
        cmd.add("-XX:CompileThreshold=1500");
        cmd.add("-Djna.nosys=true");
        cmd.add("-XX:+UseStringDeduplication");
        cmd.add("-XX:AutoBoxCacheMax=65535");
        cmd.add("-Droatpkz.ac.client_md5=" + md5);
        cmd.add("-Droatpkz.ac.launch_ts=" + ts);
        cmd.add("-jar");
        cmd.add(roat.gameJar.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(roat.rpkzDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        return new Session(p, p.pid());
    }

    static void openOfficial(RoatInstall roat) throws Exception {
        if (roat == null) throw new IllegalStateException("Roat PKz was not found.");
        if (roat.officialExe != null && Files.isRegularFile(roat.officialExe)) {
            new ProcessBuilder(roat.officialExe.toString()).start();
            return;
        }
        if (roat.officialLauncherJar != null && roat.javaExe != null) {
            new ProcessBuilder(roat.javaExe.toString(), "-jar",
                    roat.officialLauncherJar.toAbsolutePath().toString()).start();
            return;
        }
        throw new IllegalStateException(
                "The Roat updater wasn't found. Install Roat PKz first.");
    }

    static void stopOldClients() {
        for (long pid : findRoatPids()) {
            try {
                new ProcessBuilder("taskkill", "/PID", Long.toString(pid), "/F")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        try { Thread.sleep(400L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static List<Long> findRoatPids() {
        List<Long> out = new ArrayList<>();
        String ps = "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
                + "Where-Object { $_.CommandLine -match 'roat-rl|roat-rl-saved|fontconfig-ext' } | "
                + "ForEach-Object { $_.ProcessId }";
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", ps);
            pb.redirectErrorStream(true);
            p = pb.start();
            boolean done = p.waitFor(8, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return out;
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try { out.add(Long.parseLong(line)); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroyForcibly();
        }
        return out;
    }

    static Long latestRoatPid() {
        List<Long> pids = findRoatPids();
        if (pids.isEmpty()) return null;
        long max = pids.get(0);
        for (long n : pids) if (n > max) max = n;
        return max;
    }

    private static void clearDownloadLock(Path rpkzDir) {
        if (rpkzDir == null) return;
        Path lock = rpkzDir.resolve("roat-rl-download.lock");
        try { Files.deleteIfExists(lock); } catch (Exception ignored) {}
    }

    static String md5Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[65536];
        try (java.io.InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(32);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
