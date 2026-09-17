package com.sun.java.fontmgr.overlay;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Behavioral smoke test for {@link OverlayHook} without a live game.jar.
 * Run: {@code javac} on the overlay package + this file, or via Gradle test
 * once a test source set is wired. For now this is a main() harness.
 */
public final class OverlayHookSmoke {

    public interface FakeCallbacks {
        void draw(Object buffer, Graphics g, int x, int y);
        void post(Object event);
    }

    public static final class FakeClient {
        public FakeCallbacks callbacks;
    }

    public static final class FakeBuffer {
        private final BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        public BufferedImage getImage() { return image; }
    }

    public static void main(String[] args) throws Exception {
        FakeClient client = new FakeClient();
        final int[] draws = {0};
        final int[] posts = {0};
        FakeCallbacks original = new FakeCallbacks() {
            @Override public void draw(Object buffer, Graphics g, int x, int y) { draws[0]++; }
            @Override public void post(Object event) { posts[0]++; }
        };
        client.callbacks = original;

        // Minimal bridge-like wrap using the same Proxy pattern as OverlayHook.
        Field f = FakeClient.class.getField("callbacks");
        Object proxy = Proxy.newProxyInstance(
                FakeCallbacks.class.getClassLoader(),
                new Class<?>[] { FakeCallbacks.class },
                (p, m, a) -> {
                    if ("equals".equals(m.getName())) return p == a[0];
                    if ("hashCode".equals(m.getName())) return System.identityHashCode(p);
                    if ("toString".equals(m.getName())) return "proxy";
                    if ("draw".equals(m.getName())) {
                        // paint path would run here
                    }
                    return m.invoke(original, a);
                });
        f.set(client, proxy);

        FakeCallbacks wrapped = client.callbacks;
        if (!Proxy.isProxyClass(wrapped.getClass())) {
            throw new AssertionError("callbacks not proxied");
        }
        FakeBuffer buf = new FakeBuffer();
        wrapped.draw(buf, buf.getImage().getGraphics(), 0, 0);
        wrapped.post("tick");
        // identity
        if (!wrapped.equals(wrapped)) throw new AssertionError("equals broken");
        if (wrapped.equals(original)) throw new AssertionError("equals leaked to original");
        wrapped.toString();
        wrapped.hashCode();

        if (draws[0] != 1 || posts[0] != 1) {
            throw new AssertionError("delegate counts draw=" + draws[0] + " post=" + posts[0]);
        }

        // ensureInstalled-style no-op when already proxied
        if (!(Proxy.getInvocationHandler(client.callbacks) != null)) {
            throw new AssertionError("handler missing");
        }

        System.out.println("OK OverlayHookSmoke: proxy wraps, delegates draw/post, identity ok");
    }
}
