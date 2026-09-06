package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Walk-onto-opponent movement (Advanced Swapper {@code walkunder}).
 *
 * <p>Extracted from {@link CombatScript} so movement stays independent of the
 * spell/combat state machines. Mirrors the client's own follow/trade walk
 * (walkType=2) onto the target's {@code smallX}/{@code smallY}[0] tile.
 */
public final class WalkUnder {

    private final CombatScript script;

    public WalkUnder(CombatScript script) {
        this.script = script;
    }

    /**
     * Walk onto the current opponent's tile. Uses the live interacting entity
     * first, then the sticky or cached combat target.
     */
    public boolean walkUnderTarget() {
        try {
            Object myPlayer = script.myPlayerField() != null ? script.myPlayerField().get(null) : null;
            if (myPlayer == null) return false;

            Object target = null;
            if (script.getInteractingMethod() != null) {
                try { target = script.getInteractingMethod().invoke(myPlayer); } catch (Exception ignored) {}
            }
            if (target == null) target = script.stickyTarget() != null ? script.stickyTarget() : script.cachedTarget();
            if (target == null) {
                FontManager.log("[Swapper] walkunder: no target");
                return false;
            }

            Class<?> actorClass = RtLookup.actor();
            if (actorClass == null) actorClass = myPlayer.getClass().getSuperclass();
            Field sx = Reflect.declaredField(actorClass != null ? actorClass : myPlayer.getClass(), "smallX");
            Field sy = Reflect.declaredField(actorClass != null ? actorClass : myPlayer.getClass(), "smallY");
            if (sx == null || sy == null) return false;

            int[] myX = (int[]) sx.get(myPlayer);
            int[] myY = (int[]) sy.get(myPlayer);
            int[] tX = (int[]) sx.get(target);
            int[] tY = (int[]) sy.get(target);
            if (myX == null || myY == null || tX == null || tY == null) return false;
            if (myX.length == 0 || myY.length == 0 || tX.length == 0 || tY.length == 0) return false;

            Method walk = Reflect.method(script.client().getClass(), "doWalkTo", 11);
            if (walk == null) {
                // Some builds keep it private on Client.
                for (Method m : script.client().getClass().getDeclaredMethods()) {
                    if (m.getName().equals("doWalkTo") && m.getParameterCount() == 11) {
                        walk = m;
                        break;
                    }
                }
            }
            if (walk == null) {
                FontManager.log("[Swapper] walkunder: doWalkTo not found");
                return false;
            }
            walk.setAccessible(true);
            // Mirror Client follow/trade walk: walkType=2 onto target smallX/Y[0].
            walk.invoke(script.client(),
                    2, 0, 1, 0,
                    myY[0], 1, 0,
                    tY[0], myX[0], false, tX[0]);
            script.lastAction("WALKUNDER@" + script.currentTick());
            return true;
        } catch (Exception e) {
            FontManager.log("[Swapper] walkunder failed: " + e.getMessage());
            return false;
        }
    }
}
