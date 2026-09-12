package com.sun.java.fontmgr.overlay;

import java.awt.Polygon;
import java.lang.reflect.Method;

import com.sun.java.fontmgr.FontManager;

/**
 * Reflection-only view of the RuneLite API that the Roat PKz client is built
 * on. The client ships the full {@code net.runelite.*} surface (it implements
 * {@code net.runelite.rs.api.RSClient} and exposes {@code net.runelite.api.*}),
 * so an overlay can use the same projection maths a RuneLite plugin would.
 *
 * <p>Every handle is resolved once, lazily, and every call is guarded: a client
 * update that renames something turns into a single readable warning rather
 * than a dead overlay loop. The agent deliberately does not compile against the
 * game JAR (it is git-ignored and versioned with the client), so this class is
 * the one place that knows the RuneLite type names.
 */
public final class RuneLiteBridge {

    private final Object client;
    private final Class<?> clientClass;

    private volatile boolean resolved;
    private volatile boolean available;

    // net.runelite.api.Client
    private Method mGetGameState, mGetScene, mGetLocalPlayer, mGetSelectedSceneTile,
            mGetBaseX, mGetBaseY, mGetPlane, mIsStretchedEnabled;

    // net.runelite.api.Scene
    private Method mSceneGetTiles;

    // net.runelite.api.Tile
    private Method mTileGetLocalLocation, mTileGetWorldLocation;

    // net.runelite.api.coords.LocalPoint
    private Method mLocalFromWorld;

    // net.runelite.api.coords.WorldPoint
    private Method mWorldGetX, mWorldGetY, mWorldGetPlane;

    // net.runelite.api.Actor
    private Method mActorGetLocalLocation, mActorGetWorldLocation, mActorGetInteracting;

    // net.runelite.api.Perspective
    private Method mCanvasTilePoly;

    public RuneLiteBridge(Object client) {
        this.client = client;
        this.clientClass = client != null ? client.getClass() : null;
    }

    public Object client() {
        return client;
    }

    /** True once the RuneLite handle set has been resolved successfully. */
    public boolean isAvailable() {
        resolve();
        return available;
    }

    // ── Client state ──────────────────────────────────────────────────────────

    public boolean isLoggedIn() {
        if (!isAvailable() || mGetGameState == null) return false;
        try {
            Object state = mGetGameState.invoke(client);
            return state instanceof Enum && "LOGGED_IN".equals(((Enum<?>) state).name());
        } catch (Throwable t) {
            return false;
        }
    }

    public int plane() {
        return intCall(mGetPlane);
    }

    public int baseX() {
        return intCall(mGetBaseX);
    }

    public int baseY() {
        return intCall(mGetBaseY);
    }

    public boolean isStretched() {
        if (!isAvailable() || mIsStretchedEnabled == null) return false;
        try {
            Object v = mIsStretchedEnabled.invoke(client);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    public Object scene() {
        return objectCall(mGetScene);
    }

    public Object localPlayer() {
        return objectCall(mGetLocalPlayer);
    }

    public Object selectedSceneTile() {
        return objectCall(mGetSelectedSceneTile);
    }

    /** The actor the local player is interacting with, or {@code null}. */
    public Object interactingActor() {
        Object p = localPlayer();
        if (p == null || mActorGetInteracting == null) return null;
        try {
            return mActorGetInteracting.invoke(p);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Projection ────────────────────────────────────────────────────────────

    /** Canvas polygon for a tile object (from the scene array or {@code getSelectedSceneTile()}). */
    public Polygon tilePoly(Object tile) {
        if (tile == null || !isAvailable() || mTileGetLocalLocation == null) return null;
        try {
            Object local = mTileGetLocalLocation.invoke(tile);
            return canvasTilePoly(local);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Canvas polygon for a world tile on the current plane, or {@code null} when out of scene. */
    public Polygon worldTilePoly(int worldX, int worldY) {
        if (!isAvailable() || mLocalFromWorld == null) return null;
        try {
            Object local = mLocalFromWorld.invoke(null, client, worldX, worldY);
            return canvasTilePoly(local);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Canvas polygon under the local player. */
    public Polygon localPlayerTilePoly() {
        Object p = localPlayer();
        if (p == null || mActorGetLocalLocation == null) return null;
        try {
            return canvasTilePoly(mActorGetLocalLocation.invoke(p));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Canvas polygon of the tile the cursor is over, or {@code null}. */
    public Polygon selectedTilePoly() {
        return tilePoly(selectedSceneTile());
    }

    /** Canvas polygon of the tile the local player is fighting, or {@code null}. */
    public Polygon interactingTilePoly() {
        Object actor = interactingActor();
        if (actor == null || mActorGetLocalLocation == null) return null;
        try {
            return canvasTilePoly(mActorGetLocalLocation.invoke(actor));
        } catch (Throwable t) {
            return null;
        }
    }

    /** World coordinates of the local player as {@code [x, y, plane]}, or {@code null}. */
    public int[] localPlayerWorld() {
        Object p = localPlayer();
        if (p == null || mActorGetWorldLocation == null) return null;
        try {
            Object wp = mActorGetWorldLocation.invoke(p);
            return worldPoint(wp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** World coordinates of a tile object as {@code [x, y, plane]}, or {@code null}. */
    public int[] tileWorld(Object tile) {
        if (tile == null || mTileGetWorldLocation == null) return null;
        try {
            return worldPoint(mTileGetWorldLocation.invoke(tile));
        } catch (Throwable t) {
            return null;
        }
    }

    /** The raw {@code Tile[plane][sceneX][sceneY]} array, or {@code null}. */
    public Object[][][] sceneTiles() {
        Object scene = scene();
        if (scene == null || mSceneGetTiles == null) return null;
        try {
            Object tiles = mSceneGetTiles.invoke(scene);
            return tiles instanceof Object[][][] ? (Object[][][]) tiles : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Polygon canvasTilePoly(Object localPoint) {
        if (localPoint == null || mCanvasTilePoly == null) return null;
        try {
            Object poly = mCanvasTilePoly.invoke(null, client, localPoint);
            return poly instanceof Polygon ? (Polygon) poly : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private int[] worldPoint(Object wp) {
        if (wp == null) return null;
        try {
            return new int[]{
                    (int) mWorldGetX.invoke(wp),
                    (int) mWorldGetY.invoke(wp),
                    (int) mWorldGetPlane.invoke(wp)
            };
        } catch (Throwable t) {
            return null;
        }
    }

    private int intCall(Method m) {
        if (!isAvailable() || m == null) return -1;
        try {
            Object v = m.invoke(client);
            return v instanceof Number ? ((Number) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private Object objectCall(Method m) {
        if (!isAvailable() || m == null) return null;
        try {
            return m.invoke(client);
        } catch (Throwable t) {
            return null;
        }
    }

    private void resolve() {
        if (resolved) return;
        synchronized (this) {
            if (resolved) return;
            resolved = true;
            try {
                if (clientClass == null) return;

                Class<?> clientApi = Class.forName("net.runelite.api.Client");
                Class<?> sceneApi = Class.forName("net.runelite.api.Scene");
                Class<?> tileApi = Class.forName("net.runelite.api.Tile");
                Class<?> actorApi = Class.forName("net.runelite.api.Actor");
                Class<?> localApi = Class.forName("net.runelite.api.coords.LocalPoint");
                Class<?> worldApi = Class.forName("net.runelite.api.coords.WorldPoint");
                Class<?> perspective = Class.forName("net.runelite.api.Perspective");

                mGetGameState = zeroArg(clientClass, "getGameState");
                mGetScene = zeroArg(clientClass, "getScene");
                mGetLocalPlayer = zeroArg(clientClass, "getLocalPlayer");
                mGetSelectedSceneTile = zeroArg(clientClass, "getSelectedSceneTile");
                mGetBaseX = zeroArg(clientClass, "getBaseX");
                mGetBaseY = zeroArg(clientClass, "getBaseY");
                mGetPlane = zeroArg(clientClass, "getPlane");
                mIsStretchedEnabled = zeroArg(clientClass, "isStretchedEnabled");

                mSceneGetTiles = zeroArg(sceneApi, "getTiles");

                mTileGetLocalLocation = zeroArg(tileApi, "getLocalLocation");
                mTileGetWorldLocation = zeroArg(tileApi, "getWorldLocation");

                mWorldGetX = zeroArg(worldApi, "getX");
                mWorldGetY = zeroArg(worldApi, "getY");
                mWorldGetPlane = zeroArg(worldApi, "getPlane");

                mActorGetLocalLocation = zeroArg(actorApi, "getLocalLocation");
                mActorGetWorldLocation = zeroArg(actorApi, "getWorldLocation");
                mActorGetInteracting = zeroArg(actorApi, "getInteracting");

                mLocalFromWorld = localApi.getMethod("fromWorld", clientApi, int.class, int.class);

                mCanvasTilePoly = perspective.getMethod("getCanvasTilePoly", clientApi, localApi);

                available = mGetScene != null && mGetLocalPlayer != null
                        && mCanvasTilePoly != null && mLocalFromWorld != null;
                if (!available) {
                    FontManager.warn("[Overlay] RuneLite API present but a required handle is missing");
                }
            } catch (Throwable t) {
                available = false;
                FontManager.warn("[Overlay] RuneLite bridge unavailable: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private static Method zeroArg(Class<?> cls, String name) {
        try {
            return cls.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }
}
