package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Walk onto the opponent's tile (swapper {@code walkunder}, NH auto, Test button).
 *
 * <p>Roat's follow/attack click uses {@code doWalkTo(2, …, width=1, length=1, moveNear=false)},
 * which pathfinds as a 1×1 object and stops <em>adjacent</em>. Ground clicks use
 * {@code doWalkTo(0, 0, 0, 0, myY, 0, 0, endY, myX, true, endX)} (packet 164) and
 * actually step onto the tile — that is walk-under.
 *
 * <p>{@code doWalkTo} is private; {@link Reflect#method} only sees public methods.
 */
public final class WalkUnder {

    private static final int REGION = 104;
    private static final long PROTECT_DEST_MS = 800L;

    private final CombatScript script;
    private Method doWalkTo;
    private Field baseXField;
    private Field baseYField;
    private volatile long lastSuccessMs;

    public WalkUnder(CombatScript script) {
        this.script = script;
    }

    /**
     * True for a short window after a successful walk packet so Ice arm /
     * left-click hooks do not wipe {@code destX}/{@code destY} (minimap flag).
     */
    public boolean recentSuccess() {
        return System.currentTimeMillis() - lastSuccessMs < PROTECT_DEST_MS;
    }

    /** Same local tile as {@code target} (not merely adjacent). */
    public boolean standingOn(Object target) {
        Object me = localPlayer();
        int[] a = localTile(me);
        int[] b = localTile(target);
        return a != null && b != null && a[0] == b[0] && a[1] == b[1];
    }

    /**
     * Walk onto the current opponent's tile. Uses the live interacting entity
     * first, then sticky / cached combat target, then last attack index.
     */
    public boolean walkUnderTarget() {
        try {
            Object me = localPlayer();
            if (me == null) {
                FontManager.log("[WalkUnder] FAILED: local player is null");
                return false;
            }

            Object target = resolveTarget(me);
            if (target == null) {
                FontManager.log("[WalkUnder] FAILED: no target (attack someone first)");
                return false;
            }

            int[] from = localTile(me);
            int[] to = localTile(target);
            if (from == null) {
                FontManager.log("[WalkUnder] FAILED: could not read our tile");
                return false;
            }
            if (to == null) {
                FontManager.log("[WalkUnder] FAILED: could not read target tile");
                return false;
            }

            int sx = from[0], sy = from[1], tx = to[0], ty = to[1];
            if (sx == tx && sy == ty) {
                lastSuccessMs = System.currentTimeMillis();
                script.lastAction("WALKUNDER_ON@" + script.currentTick());
                FontManager.log("[WalkUnder] already on tile (" + tx + "," + ty + ")");
                return true;
            }

            Method walk = resolveDoWalkTo();
            if (walk == null) {
                FontManager.log("[WalkUnder] FAILED: doWalkTo not found");
                return false;
            }

            // Ground-click path: onto the tile, walk as close as possible if blocked.
            Boolean ok = invokeWalk(walk, 0, 0, 0, sy, 0, ty, sx, true, tx);
            if (!Boolean.TRUE.equals(ok)) {
                // Menu "Walk here" (opcode 520): same dest, minimap-style packet 98.
                ok = invokeWalk(walk, 2, 0, 0, sy, 0, ty, sx, false, tx);
            }
            if (!Boolean.TRUE.equals(ok)) {
                ok = invokeWalk(walk, 2, 0, 0, sy, 0, ty, sx, true, tx);
            }

            if (Boolean.TRUE.equals(ok)) {
                lastSuccessMs = System.currentTimeMillis();
                script.lastAction("WALKUNDER@" + script.currentTick());
                FontManager.log("[WalkUnder] walk (" + sx + "," + sy + ") -> (" + tx + "," + ty + ")");
                return true;
            }
            FontManager.log("[WalkUnder] FAILED: no path (" + sx + "," + sy + ") -> (" + tx + "," + ty + ")");
            return false;
        } catch (Exception e) {
            FontManager.log("[WalkUnder] FAILED: " + e.getMessage());
            return false;
        }
    }

    private Object resolveTarget(Object me) {
        if (script.getInteractingMethod() != null && me != null) {
            try {
                Object t = script.getInteractingMethod().invoke(me);
                if (t != null && t != me) return t;
            } catch (Exception ignored) {}
        }
        Object sticky = script.stickyTarget();
        if (sticky != null && sticky != me) return sticky;
        Object cached = script.cachedTarget();
        if (cached != null && cached != me) return cached;
        return script.actorFromCachedAttack();
    }

    private Object localPlayer() {
        try {
            Field f = script.myPlayerField();
            if (f != null) {
                Object p = Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(script.client());
                if (p != null) return p;
            }
        } catch (Exception ignored) {}
        try {
            Method glp = Reflect.method(script.client().getClass(), "getLocalPlayer", 0);
            if (glp != null) return glp.invoke(script.client());
        } catch (Exception ignored) {}
        return null;
    }

    private int[] localTile(Object actor) {
        if (actor == null) return null;
        try {
            Field sx = Reflect.declaredField(actor.getClass(), "smallX");
            Field sy = Reflect.declaredField(actor.getClass(), "smallY");
            if (sx != null && sy != null) {
                int[] xa = (int[]) sx.get(actor);
                int[] ya = (int[]) sy.get(actor);
                if (xa != null && ya != null && xa.length > 0 && ya.length > 0) {
                    int[] t = normalizeTile(xa[0], ya[0]);
                    if (t != null) return t;
                }
            }
        } catch (Exception ignored) {}
        try {
            Field xf = Reflect.declaredField(actor.getClass(), "x");
            Field yf = Reflect.declaredField(actor.getClass(), "y");
            if (xf != null && yf != null) {
                int[] t = normalizeTile(xf.getInt(actor) >> 7, yf.getInt(actor) >> 7);
                if (t != null) return t;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private int[] normalizeTile(int x, int y) {
        if (inRegion(x, y)) return new int[] { x, y };
        int[] converted = worldToLocal(x, y);
        return converted;
    }

    private static boolean inRegion(int x, int y) {
        return x >= 0 && x < REGION && y >= 0 && y < REGION;
    }

    private int[] worldToLocal(int wx, int wy) {
        try {
            Object client = script.client();
            if (client == null) return null;
            if (baseXField == null) baseXField = Reflect.declaredField(client.getClass(), "baseX");
            if (baseYField == null) baseYField = Reflect.declaredField(client.getClass(), "baseY");
            if (baseXField == null || baseYField == null) return null;
            int bx = intField(baseXField, client);
            int by = intField(baseYField, client);
            int lx = wx - bx;
            int ly = wy - by;
            if (inRegion(lx, ly)) return new int[] { lx, ly };
        } catch (Exception ignored) {}
        return null;
    }

    private static int intField(Field f, Object client) throws IllegalAccessException {
        if (Modifier.isStatic(f.getModifiers())) return f.getInt(null);
        return f.getInt(client);
    }

    private Method resolveDoWalkTo() {
        if (doWalkTo != null) return doWalkTo;
        Object client = script.client();
        if (client == null) return null;
        Class<?> cls = client.getClass();
        Method named = RtLookup.method(cls, "doWalkTo", 11);
        if (named != null && isWalkShape(named)) {
            doWalkTo = named;
            return doWalkTo;
        }
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (isWalkShape(m) && ("doWalkTo".equals(m.getName()) || m.getReturnType() == boolean.class)) {
                    m.setAccessible(true);
                    doWalkTo = m;
                    FontManager.debug("[WalkUnder] doWalkTo=" + m.getName() + " on " + c.getName());
                    return doWalkTo;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean isWalkShape(Method m) {
        if (m == null) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 11
                && p[0] == int.class && p[1] == int.class && p[2] == int.class
                && p[3] == int.class && p[4] == int.class && p[5] == int.class
                && p[6] == int.class && p[7] == int.class && p[8] == int.class
                && p[9] == boolean.class && p[10] == int.class;
    }

    private Boolean invokeWalk(Method walk, int walkType, int width, int objectType,
                               int startY, int length, int endY, int startX,
                               boolean moveNear, int endX) {
        try {
            walk.setAccessible(true);
            Object r = walk.invoke(script.client(),
                    walkType, 0, width, objectType, startY, length, 0, endY, startX, moveNear, endX);
            if (r instanceof Boolean) return (Boolean) r;
            return r == null ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            FontManager.debug("[WalkUnder] invoke walkType=" + walkType + " " + c.getMessage());
            return Boolean.FALSE;
        }
    }
}
