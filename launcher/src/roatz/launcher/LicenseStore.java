package roatz.launcher;

import com.sun.java.fontmgr.Hwid;
import com.sun.java.fontmgr.LicenseToken;
import com.sun.java.fontmgr.Product;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class LicenseStore {

    String key = "";
    String token = "";
    String hwid = "";
    long expUnix;
    long checkedAtMs;

    static LicenseStore load() {
        LicenseStore s = new LicenseStore();
        Path f = AppPaths.licenseFile();
        if (!Files.isRegularFile(f)) return s;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException ignored) {
            return s;
        }
        s.key = p.getProperty("key", "");
        s.token = p.getProperty("token", "");
        s.hwid = p.getProperty("hwid", "");
        s.expUnix = parseLong(p.getProperty("exp", "0"));
        s.checkedAtMs = parseLong(p.getProperty("checkedAt", "0"));
        return s;
    }

    void save() {
        Properties p = new Properties();
        p.setProperty("key", key == null ? "" : key);
        p.setProperty("token", token == null ? "" : token);
        p.setProperty("hwid", hwid == null ? "" : hwid);
        p.setProperty("exp", Long.toString(expUnix));
        p.setProperty("checkedAt", Long.toString(checkedAtMs));
        try (OutputStream out = Files.newOutputStream(AppPaths.licenseFile())) {
            p.store(out, Product.NAME + " license");
        } catch (IOException ignored) {}
    }

    void apply(LicenseClient.Result r, String usedHwid) {
        if (!r.ok()) return;
        this.key = r.key != null && !r.key.isEmpty() ? r.key : this.key;
        this.token = r.token;
        this.hwid = usedHwid;
        this.expUnix = r.expUnix;
        this.checkedAtMs = System.currentTimeMillis();
        save();
    }

    void clear() {
        key = "";
        token = "";
        hwid = "";
        expUnix = 0;
        checkedAtMs = 0;
        try { Files.deleteIfExists(AppPaths.licenseFile()); } catch (IOException ignored) {}
    }

    boolean hasKey() {
        return key != null && !key.trim().isEmpty();
    }

    /** Cached token still verifies (72h TTL). Used when the license API is unreachable. */
    boolean canAttachOffline(String currentHwid) {
        return token != null && !token.isEmpty()
                && LicenseToken.verify(token, currentHwid) != null;
    }

    static Properties loadConfig() {
        Properties p = new Properties();
        Path f = AppPaths.configFile();
        if (!Files.isRegularFile(f)) {
            p.setProperty("license.api", Product.LICENSE_API_DEFAULT);
            saveConfig(p);
            return p;
        }
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException ignored) {}
        if (!p.containsKey("license.api")) {
            p.setProperty("license.api", Product.LICENSE_API_DEFAULT);
        }
        return p;
    }

    static void saveConfig(Properties p) {
        try (OutputStream out = Files.newOutputStream(AppPaths.configFile())) {
            p.store(out, Product.NAME + " launcher");
        } catch (IOException ignored) {}
    }

    static String currentHwid() {
        return Hwid.current();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }
}
