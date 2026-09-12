package com.sun.java.fontmgr.overlay;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.sun.java.fontmgr.FontManager;

/**
 * Installs the in-game overlay renderer by wrapping the RuneLite
 * {@code Callbacks} object the client already holds.
 *
 * <h2>Why this seam</h2>
 * The client is a full RuneLite client. Its {@code ProducingGraphicsBuffer}
 * blits the frame through {@code Client.getCallbacks().draw(bufferProvider,
 * graphics, x, y)} — the same callback RuneLite's own overlays use. The
 * {@code callbacks} field is Guice-injected ({@code @Inject Callbacks callbacks})
 * and, on this build, has no setter, so we read it reflectively, wrap it in a
 * dynamic proxy that delegates everything, and write it back.
 *
 * <p>Our drawing happens inside the wrapper <em>before</em> the original
 * callback runs: we render into the buffer image
 * ({@code bufferProvider.getImage().getGraphics()}), then the original
 * RuneLite {@code Hooks.draw} draws its overlay layers onto the same image and
 * blits it to the canvas. Drawing into the buffer (rather than the canvas)
 * means stretched/resized modes scale our tiles together with the scene.
 *
 * <p>Delegation is deliberate: RuneLite's plugin overlays, notifications and
 * screenshots keep working. The wrapper is re-asserted once per game tick in
 * case something overwrites the field.
 */
public final class OverlayHook {

    private static final Object LOCK = new Object();

    private static volatile boolean installed;
    private static volatile Object clientRef;
    private static volatile Field callbacksField;
    private static volatile Object originalCallbacks;
    private static volatile Object proxyRef;
    private static volatile Method getImageMethod;
    private static volatile long lastRenderErrorMs;

    private OverlayHook() {}

    /**
     * Wraps the client's {@code Callbacks}. Safe to call repeatedly.
     *
     * @return true when the wrapper is (or already was) installed
     */
    public static boolean install(Object client) {
        synchronized (LOCK) {
            if (installed) return true;
            if (client == null) return false;
            try {
                Class<?> callbacksApi = Class.forName("net.runelite.api.hooks.Callbacks");
                Field field = findField(client.getClass(), "callbacks");
                if (field == null) {
                    FontManager.warn("[Overlay] client has no 'callbacks' field — overlays disabled");
                    return false;
                }
                field.setAccessible(true);

                Object current = field.get(client);
                if (isOurProxy(current)) {
                    // Already wrapped (e.g. re-attach after a failed install path).
                    installed = true;
                    clientRef = client;
                    callbacksField = field;
                    proxyRef = current;
                    return true;
                }

                clientRef = client;
                callbacksField = field;
                originalCallbacks = current;

                Object proxy = Proxy.newProxyInstance(
                        callbacksApi.getClassLoader(),
                        new Class<?>[]{callbacksApi},
                        new Handler());
                field.set(client, proxy);
                proxyRef = proxy;
                installed = true;

                FontManager.log("[Overlay] callbacks wrapped (original="
                        + (current == null ? "none" : current.getClass().getSimpleName()) + ")");
                return true;
            } catch (Throwable t) {
                FontManager.warn("[Overlay] hook install failed: " + describe(t));
                return false;
            }
        }
    }

    /**
     * Re-asserts the wrapper if the {@code callbacks} field no longer points at
     * it. Cheap (one field read); intended to be called once per game tick.
     */
    public static void ensureInstalled() {
        if (!installed) return;
        Object client = clientRef;
        Field field = callbacksField;
        if (client == null || field == null) return;
        try {
            if (field.get(client) != proxyRef) {
                synchronized (LOCK) {
                    installed = false;
                }
                install(client);
            }
        } catch (Throwable ignored) {
            // A transient read failure is not worth a log line every tick.
        }
    }

    // ── Frame rendering ───────────────────────────────────────────────────────

    private static void renderIntoBuffer(Object bufferProvider) {
        OverlayManager manager = OverlayManager.get();
        if (!manager.hasEnabled()) return;

        RuneLiteBridge bridge = manager.bridge();
        if (bridge == null || !bridge.isLoggedIn()) return;

        try {
            Object image = bufferImage(bufferProvider);
            if (!(image instanceof Image)) return;

            Graphics2D g = (Graphics2D) ((Image) image).getGraphics();
            if (g == null) return;
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                        RenderingHints.VALUE_STROKE_PURE);
                manager.renderAll(g);
            } finally {
                g.dispose();
            }
        } catch (Throwable t) {
            // Never spam: one line per 5s at most, and only in debug mode.
            long now = System.currentTimeMillis();
            if (now - lastRenderErrorMs > 5000) {
                lastRenderErrorMs = now;
                FontManager.debug("[Overlay] render error: " + describe(t));
            }
        }
    }

    private static Object bufferImage(Object bufferProvider) throws Exception {
        Method m = getImageMethod;
        if (m == null || !m.getDeclaringClass().isInstance(bufferProvider)) {
            m = findGetImage(bufferProvider.getClass());
            if (m == null) return null;
            getImageMethod = m;
        }
        return m.invoke(bufferProvider);
    }

    private static Method findGetImage(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getMethod("getImage");
            } catch (NoSuchMethodException ignored) {
                // keep walking
            }
        }
        return null;
    }

    // ── Proxy plumbing ────────────────────────────────────────────────────────

    private static boolean isOurProxy(Object o) {
        return o != null && Proxy.isProxyClass(o.getClass())
                && Proxy.getInvocationHandler(o) instanceof Handler;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    /** Unwraps {@link InvocationTargetException} into a one-line description. */
    static String describe(Throwable t) {
        Throwable c = (t instanceof InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        return c.getClass().getSimpleName() + (c.getMessage() != null ? ": " + c.getMessage() : "");
    }

    /** Delegates every callback to the original Hooks, adding overlay drawing to {@code draw}. */
    private static final class Handler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "equals":
                    return proxy == (args != null && args.length == 1 ? args[0] : null);
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "RoatzOverlayCallbacks";
                default:
                    break;
            }

            boolean isDraw = "draw".equals(name) && args != null && args.length == 4;
            if (isDraw && args[0] != null) {
                try {
                    renderIntoBuffer(args[0]);
                } catch (Throwable ignored) {
                    // renderIntoBuffer already logs; never break the frame.
                }
            }

            Object original = originalCallbacks;
            if (original != null) {
                try {
                    return method.invoke(original, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }

            // No original Hooks: we are the only renderer, so blit the frame.
            if (isDraw) {
                try {
                    Object bufferProvider = args[0];
                    Graphics canvas = (Graphics) args[1];
                    if (bufferProvider != null && canvas != null) {
                        Object image = bufferImage(bufferProvider);
                        if (image instanceof Image) canvas.drawImage((Image) image, 0, 0, null);
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }
}
