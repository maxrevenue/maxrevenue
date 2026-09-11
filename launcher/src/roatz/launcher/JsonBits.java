package roatz.launcher;

/** Tiny JSON field reader for the license API's compact objects. */
final class JsonBits {

    private JsonBits() {}

    static boolean bool(String json, String key, boolean fallback) {
        String raw = rawValue(json, key);
        if (raw == null) return fallback;
        if ("true".equals(raw)) return true;
        if ("false".equals(raw)) return false;
        return fallback;
    }

    static String str(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        int p = skipWs(json, i + needle.length());
        if (p >= json.length() || json.charAt(p) != '"') return "";
        StringBuilder sb = new StringBuilder();
        for (int n = p + 1; n < json.length(); n++) {
            char c = json.charAt(n);
            if (c == '\\' && n + 1 < json.length()) {
                sb.append(json.charAt(n + 1));
                n++;
                continue;
            }
            if (c == '"') return sb.toString();
            sb.append(c);
        }
        return "";
    }

    static long lng(String json, String key, long fallback) {
        String raw = rawValue(json, key);
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String rawValue(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int p = skipWs(json, i + needle.length());
        if (p >= json.length()) return null;
        if (json.charAt(p) == '"') return null;
        int n = p;
        while (n < json.length()) {
            char c = json.charAt(n);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
            n++;
        }
        return json.substring(p, n);
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
