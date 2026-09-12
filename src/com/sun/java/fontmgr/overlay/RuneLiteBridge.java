package com.sun.java.fontmgr.overlay;

import com.sun.java.fontmgr.FontManager;

import java.awt.Image;
import java.awt.Polygon;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection-only view of {@code net.runelite.api}. No compile dependency on the
 * gitignored game JAR — every handle is resolved against the live Client.
 */
public final class RuneLiteBridge {

    private static volatile boolean warnedMissing;

    private final Object client;
    private final Class<?> clientClass;

    private final Method mGetLocalPlayer;
    private final Method mGetSelectedSceneTile;
    private final Method mGetPlane;
    private final Method mGetGameState;
    private final Method mGetCanvasWidth;
    private final Method mGetCanvasHeight;
    private final Method mGameStateOrdinal;

    private final Method mActorWorldLocation;
    private final Method mActorInteracting;
    private final Method mTileWorldLocation;

    private final Method mLocalPointFromWorld;
    private final Method mGetCanvasTilePoly;
    private final Constructor<?> worldPointCtor;
    private final Method mWorldX;
    private final Method mWorldY;
    private final Method mWorldPlane;

    private final Field callbacksField;
    private final Class<?> callbacksInterface;
    private final boolean available;

    public RuneLiteBridge(Object client) {
        this.client = client;
        this.clientClass = client != null ? client.getClass() : null;

        mGetLocalPlayer = findClientMethod("getLocalPlayer", 0);
        mGetSelectedSceneTile = findClientMethod("getSelectedSceneTile", 0);
        mGetPlane = findClientMethod("getPlane", 0);
        mGetGameState = findClientMethod("getGameState", 0);
        mGetCanvasWidth = findClientMethod("getCanvasWidth", 0);
        mGetCanvasHeight = findClientMethod("getCanvasHeight", 0);

        Class<?> actorCls = classForName("net.runelite.api.Actor");
        mActorWorldLocation = findMethod(actorCls, "getWorldLocation", 0);
        mActorInteracting = findMethod(actorCls, "getInteracting", 0);

        Class<?> tileCls = classForName("net.runelite.api.Tile");
        mTileWorldLocation = findMethod(tileCls, "getWorldLocation", 0);

        Class<?> gameStateCls = classForName("net.runelite.api.GameState");
        mGameStateOrdinal = findMethod(gameStateCls, "ordinal", 0);

        Class<?> localPointCls = classForName("net.runelite.api.coords.LocalPoint");
        mLocalPointFromWorld = findStatic(localPointCls, "fromWorld", 2);

        Class<?> perspectiveCls = classForName("net.runelite.api.Perspective");
        mGetCanvasTilePoly = findStatic(perspectiveCls, "getCanvasTilePoly", 2);

        Class<?> worldPointCls = classForName("net.runelite.api.coords.WorldPoint");
        Constructor<?> ctor = null;
        if (worldPointCls != null) {
            try {
                ctor = worldPointCls.getConstructor(int.class, int.class, int.class);
            } catch (Exception ignored) {}
        }
        worldPointCtor = ctor;
        mWorldX = findMethod(worldPointCls, "getX", 0);
        mWorldY = findMethod(worldPointCls, "getY", 0);
        mWorldPlane = findMethod(worldPointCls, "getPlane", 0);

        callbacksField = findCallbacksField(clientClass);
        callbacksInterface = resolveCallbacksInterface(callbacksField);

        boolean ok = client != null
                && mGetLocalPlayer != null
                && mGetCanvasTilePoly != null
                && mLocalPointFromWorld != null
                && callbacksField != null
                && callbacksInterface != null
                && worldPointCtor != null;

        if (!ok && !warnedMissing) {
            warnedMissing = true;
            FontManager.warn("[Overlay] RuneLite API incomplete — in-game overlays disabled"
                    + " (client=" + (clientClass != null ? clientClass.getName() : "null")
                    + " localPlayer=" + (mGetLocalPlayer != null)
                    + " tilePoly=" + (mGetCanvasTilePoly != null)
                    + " localPoint=" + (mLocalPointFromWorld != null)
                    + " worldPoint=" + (worldPointCtor != null)
                    + " callbacksField=" + (callbacksField != null)
                    + " callbacksIface=" + (callbacksInterface != null) + ")");
        } else if (ok) {
            FontManager.warn("[Overlay] RuneLite bridge ready"
                    + " callbacks=" + callbacksInterface.getName()
                    + " field=" + callbacksField.getDeclaringClass().getSimpleName()
                    + "." + callbacksField.getName());
        }
        this.available = ok;
    }

    public boolean available() { return available; }

    public Object client() { return client; }

    public Field callbacksField() { return callbacksField; }

    public Class<?> callbacksInterface() { return callbacksInterface; }

    public Object getCallbacks() {
        try {
            return callbacksField != null ? callbacksField.get(client) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void setCallbacks(Object callbacks) throws IllegalAccessException {
        if (callbacksField == null) throw new IllegalStateException("no callbacks field");
        callbacksField.set(client, callbacks);
    }

    public boolean isLoggedIn() {
        if (mGetGameState == null) return true;
        try {
            Object gs = mGetGameState.invoke(client);
            if (gs == null) return false;
            if (mGameStateOrdinal != null) {
                return ((Integer) mGameStateOrdinal.invoke(gs)) >= 3;
            }
            return "LOGGED_IN".equals(String.valueOf(gs));
        } catch (Exception e) {
            return true;
        }
    }

    public int canvasWidth() { return intInvoke(mGetCanvasWidth, client, 0); }

    public int canvasHeight() { return intInvoke(mGetCanvasHeight, client, 0); }

    public int plane() { return intInvoke(mGetPlane, client, 0); }

    public Object localPlayer() { return invoke(mGetLocalPlayer, client); }

    public Object selectedTile() { return invoke(mGetSelectedSceneTile, client); }

    public Object interacting(Object actor) { return invoke(mActorInteracting, actor); }

    public Object worldLocationOfActor(Object actor) {
        return invoke(mActorWorldLocation, actor);
    }

    public Object worldLocationOfTile(Object tile) {
        return invoke(mTileWorldLocation, tile);
    }

    public Object worldPoint(int x, int y, int plane) {
        if (worldPointCtor == null) return null;
        try {
            return worldPointCtor.newInstance(x, y, plane);
        } catch (Exception e) {
            return null;
        }
    }

    public int worldX(Object wp) { return intInvoke(mWorldX, wp, -1); }

    public int worldY(Object wp) { return intInvoke(mWorldY, wp, -1); }

    public int worldPlane(Object wp) { return intInvoke(mWorldPlane, wp, 0); }

    public Object localPointFromWorld(Object worldPoint) {
        return invokeStatic(mLocalPointFromWorld, client, worldPoint);
    }

    public Polygon tilePoly(Object localPoint) {
        Object poly = invokeStatic(mGetCanvasTilePoly, client, localPoint);
        return poly instanceof Polygon ? (Polygon) poly : null;
    }

    public Polygon worldTilePoly(Object worldPoint) {
        if (worldPoint == null) return null;
        Object lp = localPointFromWorld(worldPoint);
        return lp != null ? tilePoly(lp) : null;
    }

    public Image bufferImage(Object bufferProvider) {
        if (bufferProvider == null) return null;
        Method getImage = findMethod(bufferProvider.getClass(), "getImage", 0);
        Object img = invoke(getImage, bufferProvider);
        return img instanceof Image ? (Image) img : null;
    }

    private Method findClientMethod(String name, int arity) {
        Method m = findMethod(clientClass, name, arity);
        if (m != null) return m;
        if (clientClass == null) return null;
        for (Class<?> iface : clientClass.getInterfaces()) {
            m = findMethod(iface, name, arity);
            if (m != null) return m;
        }
        for (Class<?> c = clientClass.getSuperclass(); c != null; c = c.getSuperclass()) {
            m = findMethod(c, name, arity);
            if (m != null) return m;
        }
        return null;
    }

    private static Field findCallbacksField(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!"callbacks".equals(f.getName())) continue;
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Exception ignored) {}
            }
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t != null && "Callbacks".equals(t.getSimpleName())) {
                    try {
                        f.setAccessible(true);
                        return f;
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private static Class<?> resolveCallbacksInterface(Field callbacksField) {
        if (callbacksField == null) return null;
        Class<?> t = callbacksField.getType();
        if (t != null && t.isInterface()) return t;
        Class<?> named = classForName("net.runelite.api.hooks.Callbacks");
        if (named != null) return named;
        named = classForName("net.runelite.client.callback.Callbacks");
        return named != null ? named : t;
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl != null) return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name, int arity) {
        if (cls == null) return null;
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
        }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == arity) {
                    try { m.setAccessible(true); } catch (Exception ignored) {}
                    return m;
                }
            }
        }
        return null;
    }

    private static Method findStatic(Class<?> cls, String name, int arity) {
        if (cls == null) return null;
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity
                    && Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity
                    && Modifier.isStatic(m.getModifiers())) {
                try { m.setAccessible(true); } catch (Exception ignored) {}
                return m;
            }
        }
        return null;
    }

    private static Object invoke(Method m, Object target, Object... args) {
        if (m == null || target == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object invokeStatic(Method m, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(null, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static int intInvoke(Method m, Object target, int fallback) {
        Object v = invoke(m, target);
        return v instanceof Integer ? (Integer) v : fallback;
    }
}
