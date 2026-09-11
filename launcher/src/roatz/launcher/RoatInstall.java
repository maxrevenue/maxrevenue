package roatz.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/** Locate the buyer's existing Roat PKz install. Never downloads the game. */
final class RoatInstall {

    static final Path DEFAULT_CLIENT_DIR = Paths.get(
            "C:\\Program Files (x86)\\roatpkz_runelite");
    static final Path DEFAULT_EXE = DEFAULT_CLIENT_DIR.resolve("Roat Pkz.exe");

    final Path javaExe;
    final Path rpkzDir;
    final Path gameJar;
    final Path officialExe;
    final Path officialLauncherJar;
    final String error;

    private RoatInstall(Path javaExe, Path rpkzDir, Path gameJar,
                        Path officialExe, Path officialLauncherJar, String error) {
        this.javaExe = javaExe;
        this.rpkzDir = rpkzDir;
        this.gameJar = gameJar;
        this.officialExe = officialExe;
        this.officialLauncherJar = officialLauncherJar;
        this.error = error;
    }

    static RoatInstall detect() {
        Path java = firstExisting(
                Paths.get("C:\\Program Files (x86)\\roatpkz_runelite\\jre-64\\bin\\java.exe"),
                Paths.get("C:\\Program Files (x86)\\roatpkz_runelite\\jre\\bin\\java.exe"));
        Path rpkz = Paths.get(System.getProperty("user.home"), "rpkzclient");
        Path exe = Files.isRegularFile(DEFAULT_EXE) ? DEFAULT_EXE : null;
        Path officialJar = rpkz.resolve("RoatPkzLauncher.jar");
        if (!Files.isRegularFile(officialJar)) officialJar = null;

        if (java == null) {
            return new RoatInstall(null, rpkz, null, exe, officialJar,
                    "Roat PKz was not found on this PC.\nInstall it first, then press Play:\n"
                            + DEFAULT_CLIENT_DIR);
        }

        Path jar = findLiveJar(rpkz);
        if (jar == null) {
            return new RoatInstall(java, rpkz, null, exe, officialJar,
                    "Roat PKz is installed, but its game files are missing.\n"
                            + "Press “Update Roat”, log in once, then come back.\n"
                            + rpkz);
        }
        return new RoatInstall(java, rpkz, jar, exe, officialJar, null);
    }

    boolean ready() {
        return error == null && javaExe != null && gameJar != null;
    }

    boolean canOpenOfficial() {
        return (officialExe != null && Files.isRegularFile(officialExe))
                || (officialLauncherJar != null && javaExe != null);
    }

    String statusLine() {
        if (!ready()) return error;
        long ageDays = Math.max(0L,
                (System.currentTimeMillis() - gameJar.toFile().lastModified()) / 86_400_000L);
        String age = ageDays + (ageDays == 1 ? " day" : " days") + " old";
        return "Roat found · " + gameJar.getFileName() + " · " + age;
    }

    static Path findLiveJar(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (Stream<Path> stream = Files.list(dir)) {
            Optional<Path> hit = stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        if (!n.startsWith("roat-rl-") || !n.endsWith(".jar")) return false;
                        if (n.contains("saved")) return false;
                        try { return Files.size(p) > 1_000_000L; } catch (Exception e) { return false; }
                    })
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            if (hit.isPresent()) return hit.get();
        } catch (Exception ignored) {}
        Path local = dir.resolve("roat-rl-local.jar");
        try {
            if (Files.isRegularFile(local) && Files.size(local) > 1_000_000L) return local;
        } catch (Exception ignored) {}
        return null;
    }

    private static Path firstExisting(Path... paths) {
        for (Path p : paths) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
