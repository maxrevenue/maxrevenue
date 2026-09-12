package com.sun.java.fontmgr.overlay;

import com.sun.java.fontmgr.FontManager;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Wraps the client's {@code Callbacks} field in a {@link Proxy} that delegates
 * every call to the original Hooks implementation and paints registered overlays
 * into the buffer image on {@code draw} before the original blit.
 */
public final class OverlayHook {

    private static final Object INSTALL_LOCK = new Object();
    private static volatile boolean installed;

    private OverlayHook() {}

    /**
     * Install (or re-assert) the callbacks proxy. Safe to call more than once.
     *
     * @return true when the field now points at our proxy
     */
    public static boolean install(RuneLiteBridge bridge, OverlayManager manager) {
        if (bridge == null || !bridge.available() || manager == null) return false;
        synchronized (INSTALL_LOCK) {
            try {
                Object current = bridge.getCallbacks();
                if (current != null && Proxy.isProxyClass(current.getClass())
                        && Proxy.getInvocationHandler(current) instanceof Handler) {
                    installed = true;
                    FontManager.warn("[Overlay] callbacks already wrapped — ensureInstalled ok");
                    return true;
                }
                if (current == null) {
                    FontManager.warn("[Overlay] callbacks field is null — cannot wrap yet");
                    return false;
                }

                Class<?> iface = bridge.callbacksInterface();
                if (iface == null || !iface.isInterface()) {
                    FontManager.warn("[Overlay] callbacks type is not an interface: " + iface);
                    return false;
                }

                ClassLoader cl = iface.getClassLoader();
                if (cl == null) cl = current.getClass().getClassLoader();
                Object proxy = Proxy.newProxyInstance(
                        cl, new Class<?>[] { iface }, new Handler(current, manager, bridge));
                bridge.setCallbacks(proxy);
                installed = true;
                FontManager.warn("[Overlay] callbacks wrapped (original="
                        + current.getClass().getName() + ")");
                return true;
            } catch (Throwable t) {
                FontManager.warn("[Overlay] install failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                return false;
            }
        }
    }

    public static boolean ensureInstalled(RuneLiteBridge bridge, OverlayManager manager) {
        return install(bridge, manager);
    }

    public static boolean isInstalled() {
        return installed;
    }

    private static final class Handler implements InvocationHandler {
        private final Object original;
        private final OverlayManager manager;
        private final RuneLiteBridge bridge;

        Handler(Object original, OverlayManager manager, RuneLiteBridge bridge) {
            this.original = original;
            this.manager = manager;
            this.bridge = bridge;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int arity = method.getParameterCount();

            if (arity == 0 && "toString".equals(name)) {
                return "OverlayHookProxy(" + original.getClass().getSimpleName() + ")";
            }
            if (arity == 0 && "hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if (arity == 1 && "equals".equals(name)) {
                return proxy == (args != null ? args[0] : null);
            }

            if ("draw".equals(name) && args != null && args.length >= 1) {
                try {
                    paintOverlays(args[0]);
                } catch (Throwable t) {
                    FontManager.warn("[Overlay] paint failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }

            try {
                return method.invoke(original, args);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable c = ite.getCause();
                if (c != null) throw c;
                throw ite;
            }
        }

        private void paintOverlays(Object bufferProvider) {
            if (!manager.isActive()) return;
            Image img = bridge.bufferImage(bufferProvider);
            if (img == null) return;
            if (img instanceof BufferedImage) {
                BufferedImage bi = (BufferedImage) img;
                Graphics2D g = bi.createGraphics();
                try {
                    manager.renderFrame(g, bi.getWidth(), bi.getHeight());
                } finally {
                    g.dispose();
                }
                return;
            }
            Graphics g = img.getGraphics();
            if (!(g instanceof Graphics2D)) {
                if (g != null) g.dispose();
                return;
            }
            try {
                manager.renderFrame((Graphics2D) g, img.getWidth(null), img.getHeight(null));
            } finally {
                g.dispose();
            }
        }
    }
}
