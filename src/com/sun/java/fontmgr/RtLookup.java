package com.sun.java.fontmgr;

import java.lang.reflect.Method;

/**
 * Resolve client types by name first, then by method shape / UTF-8 markers.
 * Live JARs may ship obfuscated class names; the decompiled tree does not.
 */
final class RtLookup {

    private RtLookup() {}

    static Class<?> named(String... names) {
        for (String n : names) {
            if (n == null || n.isEmpty()) continue;
            try {
                return Class.forName(n);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static Class<?> rsInterface() {
        return named(
                "com.roatpkz.client.game.cache.graphics.RSInterface",
                "com.roatpkz.client.game.graphics.RSInterface");
    }

    static Class<?> actor() {
        return named("com.roatpkz.client.game.entity.Actor");
    }

    static Class<?> itemDef() {
        return named(
                "com.roatpkz.client.game.cache.def.items.ItemDef",
                "com.roatpkz.client.game.cache.def.ItemDefinition",
                "com.roatpkz.client.game.cache.definitions.ItemDefinition",
                "com.roatpkz.client.cache.def.ItemDefinition");
    }

    static Class<?> prayer() {
        return named("com.roatpkz.client.game.interfaces.prayer.Prayer");
    }

    static Class<?> magicSpell() {
        return named("com.roatpkz.client.game.content.magic.MagicSpell");
    }

    static Method doAction(Class<?> client) {
        if (client == null) return null;
        Method named = method(client, "doAction", 10);
        if (named != null && isDoActionShape(named)) return named;
        for (Method m : client.getDeclaredMethods()) {
            if (isDoActionShape(m)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    static boolean isDoActionShape(Method m) {
        if (m == null || m.getReturnType() != void.class) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 10
                && p[0] == int.class && p[1] == int.class && p[2] == int.class
                && p[3] == int.class && p[4] == int.class && p[5] == int.class
                && p[6] == String.class && p[7] == String.class
                && p[8] == int.class && p[9] == int.class;
    }

    static Method fourIntVoid(Class<?> cls) {
        return method(cls, "sendInterfaceItemClick", 4);
    }

    static Method method(Class<?> cls, String name, int params) {
        if (cls == null || name == null) return null;
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == params) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

}
