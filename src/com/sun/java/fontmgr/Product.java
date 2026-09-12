package com.sun.java.fontmgr;

/**
 * User-facing product identity. Change {@link #NAME} to rebrand the launcher and HUD.
 */
public final class Product {

    public static final String NAME = "Roatz";
    /** Bump when shipping HUD changes so Play/Attach builds are easy to verify. */
    public static final String VERSION = "1.0.1";

    /**
     * Default license API. Override with {@code -Droatz.license.api=},
     * {@code ROATZ_LICENSE_API}, or {@code %APPDATA%\Roatz\config.properties}.
     * This is the deployed Worker; {@code dist} additionally bakes the URL into
     * the app image, which takes precedence over this value.
     */
    public static final String LICENSE_API_DEFAULT = "https://roatz-license.alec-5c7.workers.dev";

    /** Signed token lifetime; matches the launcher's offline grace. */
    public static final long TOKEN_TTL_SECONDS = 72L * 3600L;

    public static final long OFFLINE_GRACE_MS = TOKEN_TTL_SECONDS * 1000L;

    private Product() {}
}
