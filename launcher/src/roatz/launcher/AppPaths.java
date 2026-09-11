package roatz.launcher;

import com.sun.java.fontmgr.Product;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Install / AppData / agent-jar locations for the branded launcher. */
final class AppPaths {

    private AppPaths() {}

    static Path dataDir() {
        String appdata = System.getenv("APPDATA");
        Path dir = (appdata != null && !appdata.isEmpty())
                ? Paths.get(appdata, Product.NAME)
                : Paths.get(System.getProperty("user.home"), Product.NAME);
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    static Path licenseFile() {
        return dataDir().resolve("license.dat");
    }

    static Path configFile() {
        return dataDir().resolve("config.properties");
    }

    /**
     * Directory that contains the launcher JAR (jpackage {@code app/}) or the
     * working directory when launched from Gradle.
     */
    static Path appDir() {
        try {
            URI uri = AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Paths.get(uri);
            if (Files.isRegularFile(p)) return p.getParent();
            if (Files.isDirectory(p)) return p;
        } catch (Exception ignored) {}
        return Paths.get(System.getProperty("user.dir", "."));
    }

    static Path agentJar() {
        String prop = System.getProperty("roatz.agent.jar");
        if (prop != null && !prop.isEmpty()) {
            Path p = Paths.get(prop);
            if (Files.isRegularFile(p)) return p.toAbsolutePath();
        }
        Path nextToApp = appDir().resolve("agent.jar");
        if (Files.isRegularFile(nextToApp)) return nextToApp.toAbsolutePath();
        Path repo = Paths.get(System.getProperty("user.dir", ".")).resolve("build")
                .resolve("fontmanager-windows.jar");
        if (Files.isRegularFile(repo)) return repo.toAbsolutePath();
        return nextToApp.toAbsolutePath();
    }
}
