package com.roatpkz.client.game.net;

/**
 * Test stand-in for the telemetry class the agent rewrites. The two reporting
 * methods are replaced wholesale with a constant return (their exception
 * tables may legitimately go with them); touch(int) must be left alone.
 */
@SuppressWarnings("unused")
public class HttpHelper {

    private String endpoint = "http://example.invalid";

    public String getClientPotionSettings() {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < 3; i++) {
                if (i % 2 == 0) {
                    sb.append("field").append(i).append('=');
                } else {
                    sb.append("x").append(i);
                }
                sb.append('\n');
            }
        } catch (RuntimeException ex) {
            return "error";
        }
        return sb.toString() + endpoint;
    }

    public String getLoadedPluginDetails() {
        try {
            if (endpoint == null) {
                return "none";
            }
            return "Total Plugins: 0\nSHA-256: 0\n\nNo plugins loaded";
        } catch (RuntimeException ex) {
            return "error";
        }
    }

    public int touch(int x) {
        try {
            switch (x & 3) {
                case 0: return x + 1;
                case 1: return x + 2;
                case 2: return x + 3;
                default: return x;
            }
        } catch (RuntimeException ex) {
            return -1;
        }
    }
}
