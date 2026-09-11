package roatz.launcher;

import com.sun.java.fontmgr.Product;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

final class LicenseClient {

    enum Kind { OK, INVALID, REVOKED, EXPIRED, OTHER_PC, NETWORK, SERVER }

    static final class Result {
        final Kind kind;
        final String token;
        final long expUnix;
        final String key;
        final String message;

        Result(Kind kind, String token, long expUnix, String key, String message) {
            this.kind = kind;
            this.token = token;
            this.expUnix = expUnix;
            this.key = key;
            this.message = message;
        }

        boolean ok() { return kind == Kind.OK && token != null && !token.isEmpty(); }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    String apiBase() {
        String prop = System.getProperty("roatz.license.api");
        if (prop != null && !prop.isEmpty()) return trimSlash(prop);
        String env = System.getenv("ROATZ_LICENSE_API");
        if (env != null && !env.isEmpty()) return trimSlash(env);
        Properties cfg = LicenseStore.loadConfig();
        String fromFile = cfg.getProperty("license.api", "").trim();
        if (!fromFile.isEmpty()) return trimSlash(fromFile);
        return trimSlash(Product.LICENSE_API_DEFAULT);
    }

    Result activate(String key, String hwid) {
        return post("/v1/activate", key, hwid);
    }

    Result check(String key, String hwid) {
        return post("/v1/check", key, hwid);
    }

    private Result post(String path, String key, String hwid) {
        String json = "{\"key\":\"" + escape(key) + "\",\"hwid\":\"" + escape(hwid) + "\"}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase() + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parse(resp.statusCode(), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Kind.NETWORK, null, 0, key, "Request interrupted.");
        } catch (Exception e) {
            return new Result(Kind.NETWORK, null, 0, key,
                    "Cannot reach the license server (" + apiBase() + ").");
        }
    }

    static Result parse(int status, String body) {
        if (body == null) body = "";
        String err = JsonBits.str(body, "error");
        // Checked before the 403/revoked branch: an expired license is also a 403,
        // but telling a buyer "this key was revoked" when it simply ran out sends
        // them to support instead of the renewal page.
        if ("expired".equals(err)) {
            return new Result(Kind.EXPIRED, null, 0, "", "Your license has ended.");
        }
        if (status == 403 || "revoked".equals(err)) {
            return new Result(Kind.REVOKED, null, 0, "", "This key was revoked.");
        }
        if (status == 409 || "other_pc".equals(err)) {
            return new Result(Kind.OTHER_PC, null, 0, "",
                    "This key is already bound to another PC.");
        }
        if (status == 401 || "invalid".equals(err) || "unauthorized".equals(err)) {
            return new Result(Kind.INVALID, null, 0, "", "That key is not valid.");
        }
        boolean ok = JsonBits.bool(body, "ok", false);
        String token = JsonBits.str(body, "token");
        long exp = JsonBits.lng(body, "exp", 0);
        String key = JsonBits.str(body, "key");
        if (ok && !token.isEmpty()) {
            return new Result(Kind.OK, token, exp, key, "Licensed");
        }
        if (status >= 500) {
            return new Result(Kind.SERVER, null, 0, "", "License server error.");
        }
        return new Result(Kind.INVALID, null, 0, "",
                err.isEmpty() ? "That key is not valid." : err);
    }

    static String human(Kind kind) {
        switch (kind) {
            case REVOKED: return "This key was revoked.";
            case EXPIRED: return "Your license has ended.";
            case OTHER_PC: return "This key is already bound to another PC.";
            case INVALID: return "That key is not valid.";
            case NETWORK: return "Cannot reach the license server.";
            case SERVER: return "License server error.";
            default: return "Licensed";
        }
    }

    private static String trimSlash(String s) {
        if (s.endsWith("/")) return s.substring(0, s.length() - 1);
        return s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
