package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shared reflection helpers, extracted from {@link CombatScript} so the
 * fighting logic stays readable and these utilities live in one place.
 *
 * <p>All methods are null-tolerant and cache nothing — they are intended for
 * bootstrap-time resolution. Hot-path reads should cache the returned
 * {@link Field}/{@link Method} handles themselves.
 */
public final class Reflect {

    private Reflect() {}

    /** Field declared on {@code cls} or any superclass, made accessible. */
    public static Field declaredField(Class<?> cls, String name) {
        if (cls == null || name == null) return null;
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                break;
            }
        }
        return null;
    }

    /** Public/declared method matching name + arity (walks superclasses). */
    public static Method method(Class<?> cls, String name, int paramCount) {
        if (cls == null || name == null) return null;
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        return null;
    }

    /** {@link Class#forName} that returns {@code null} instead of throwing. */
    public static Class<?> forName(String name) {
        if (name == null) return null;
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
