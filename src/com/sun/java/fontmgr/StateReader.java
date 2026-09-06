package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection-based game state reader. Publishes a {@link GameState} snapshot
 * to {@link SharedMemory} each tick for external tooling / IPC.
 */
public class StateReader {

    private final Object client;
    private final Method getLocalPlayer, getBoostedSkillLevel, getEnergy, getGameCycle, getVarbit, getItemContainer;
    private Method getAnimation, getX, getY, getPlane, getInteracting, getEquipmentIds, getName;
    private Method getInteractingEntity;
    private Method getBoostedSkillLevelsMethod, getRealSkillLevelsMethod;
    private Field currentSkillLevelField, maxSkillLevelField;
    private Field actorCurrentHealthField, actorMaxHealthField, playerEquipmentField;
    private Object cachedLocalPlayer;
    private long   lastLocalPlayerRefresh = 0;

    private final GameState publishBuffer = new GameState();

    public StateReader(Object client, Method getLocalPlayer, Method getBoostedSkillLevel,
                       Method getEnergy, Method getGameCycle, Method getVarbit, Method getItemContainer) {
        this.client               = client;
        this.getLocalPlayer       = getLocalPlayer;
        this.getBoostedSkillLevel = getBoostedSkillLevel;
        this.getEnergy            = getEnergy;
        this.getGameCycle         = getGameCycle;
        this.getVarbit            = getVarbit;
        this.getItemContainer     = getItemContainer;
    }

    public GameState read() {
        GameState gs = new GameState();
        fill(gs, null, -1, false);
        return gs;
    }

    /**
     * Full tick snapshot including target vitals and animation flags.
     *
     * @param target           current combat target (may be null)
     * @param targetAnim       target sequence id this tick
     * @param targetConsuming  from {@link AnimationMonitor}
     */
    public void publishTick(Object target, int targetAnim, boolean targetConsuming) {
        fill(publishBuffer, target, targetAnim, targetConsuming);
        SharedMemory.publish(publishBuffer);
    }

    private void fill(GameState gs, Object target, int targetAnim, boolean targetConsuming) {
        gs.tick   = readTick();
        gs.hp     = readSkill(3);
        gs.prayer = readSkill(5);
        gs.spec   = readSpec();
        gs.energy = readEnergy();
        gs.anim   = readAnimation();
        readPosition(gs);
        gs.target = readTarget();
        readEquipment(gs);
        gs.prayerFlags = readPrayerFlags();
        gs.vengActive  = isVengeanceActive();
        gs.targetAnim  = targetAnim;
        gs.targetConsuming = targetConsuming;
        readTargetVitals(gs, target);
        readTargetEquipment(gs, target);
    }

    private int readTick()            { try { return (int) getGameCycle.invoke(client); } catch (Exception e) { return -1; } }
    private int readSkill(int i) {
        // Resolve the field/method handles once, then reuse on every tick.
        if (currentSkillLevelField == null) {
            try {
                Field f = client.getClass().getDeclaredField("currentSkillLevel");
                f.setAccessible(true);
                currentSkillLevelField = f;
            } catch (Exception ignored) {}
        }
        if (currentSkillLevelField != null) {
            try {
                Object holder = Modifier.isStatic(currentSkillLevelField.getModifiers()) ? null : client;
                int[] levels = (int[]) currentSkillLevelField.get(holder);
                if (levels != null && i >= 0 && i < levels.length && levels[i] > 0) return levels[i];
            } catch (Exception ignored) {}
        }
        if (getBoostedSkillLevelsMethod == null) {
            try {
                getBoostedSkillLevelsMethod = client.getClass().getMethod("getBoostedSkillLevels");
            } catch (Exception ignored) {}
        }
        if (getBoostedSkillLevelsMethod != null) {
            try {
                int[] levels = (int[]) getBoostedSkillLevelsMethod.invoke(client);
                if (levels != null && i >= 0 && i < levels.length && levels[i] > 0) return levels[i];
            } catch (Exception ignored) {}
        }
        try {
            if (getBoostedSkillLevel != null && getBoostedSkillLevel.getParameterCount() == 1
                    && getBoostedSkillLevel.getParameterTypes()[0] == int.class) {
                int v = (int) getBoostedSkillLevel.invoke(client, i);
                if (v > 0) return v;
            }
        } catch (Exception ignored) {}
        if (i == 5) {
            try {
                Class<?> p = Class.forName("com.roatpkz.client.game.interfaces.prayer.Prayer");
                int v = (int) p.getMethod("getCurrentPrayerLevel").invoke(null);
                if (v > 0) return v;
            } catch (Exception ignored) {}
        }
        return -1;
    }
    public  int getCurrentHp()       { return readSkill(3); }
    public  int getMaxHp()           {
        if (getRealSkillLevelsMethod == null) {
            try {
                getRealSkillLevelsMethod = client.getClass().getMethod("getRealSkillLevels");
            } catch (Exception ignored) {}
        }
        if (getRealSkillLevelsMethod != null) {
            try {
                int[] levels = (int[]) getRealSkillLevelsMethod.invoke(client);
                if (levels != null && levels.length > 3 && levels[3] > 0) return levels[3];
            } catch (Exception ignored) {}
        }
        if (maxSkillLevelField == null) {
            try {
                Field f = client.getClass().getDeclaredField("maxSkillLevel");
                f.setAccessible(true);
                maxSkillLevelField = f;
            } catch (Exception ignored) {}
        }
        if (maxSkillLevelField != null) {
            try {
                Object holder = Modifier.isStatic(maxSkillLevelField.getModifiers()) ? null : client;
                int[] levels = (int[]) maxSkillLevelField.get(holder);
                if (levels != null && levels.length > 3 && levels[3] > 0) return levels[3];
            } catch (Exception ignored) {}
        }
        int cur = getCurrentHp();
        return cur > 0 ? cur : 99;
    }
    public  int getStrength()        { return readSkill(2); }
    public  int getCurrentPrayer()   { return readSkill(5); }
    public  int getMaxPrayer()       { return readSkill(5); }

    private int readSpec() {
        try {
            int s = (int) getVarbit.invoke(client, 300);
            if (s < 0 || s > 1000) s = (int) getVarbit.invoke(client, 301);
            return s / 10;
        } catch (Exception e) { return -1; }
    }

    private int readEnergy() { try { return (int) getEnergy.invoke(client); } catch (Exception e) { return -1; } }

    private Object getLocal() {
        if (getLocalPlayer == null) return null;
        try {
            long now = System.currentTimeMillis();
            if (cachedLocalPlayer == null || (now - lastLocalPlayerRefresh) > 2000) {
                cachedLocalPlayer      = getLocalPlayer.invoke(client);
                lastLocalPlayerRefresh = now;
            }
            return cachedLocalPlayer;
        } catch (Exception e) { return null; }
    }

    /** Force-refresh local player reference (e.g. after login). */
    public void invalidateLocalPlayer() {
        cachedLocalPlayer = null;
    }

    private int readAnimation() {
        Object local = getLocal();
        if (local == null) return -1;
        try {
            if (getAnimation == null) getAnimation = findMethod(local.getClass(), "getAnimation", 0);
            if (getAnimation != null) return (int) getAnimation.invoke(local);
            return readSequenceField(local);
        } catch (Exception e) { return -1; }
    }

    public int readActorAnimation(Object actor) {
        if (actor == null) return -1;
        try {
            Method m = findMethod(actor.getClass(), "getAnimation", 0);
            if (m != null) return (int) m.invoke(actor);
            return readSequenceField(actor);
        } catch (Exception e) { return -1; }
    }

    private int readSequenceField(Object actor) {
        try {
            Field f = findField(actor.getClass(), "sequence");
            if (f != null) return f.getInt(actor);
        } catch (Exception ignored) {}
        return -1;
    }

    public Object readInteractingTarget() {
        Object local = getLocal();
        if (local == null) return null;
        try {
            if (getInteracting == null)
                getInteracting = findMethod(local.getClass(), "getInteracting", 0);
            if (getInteracting != null) {
                Object t = getInteracting.invoke(local);
                if (t != null) return t;
            }
            if (getInteractingEntity == null)
                getInteractingEntity = findMethod(local.getClass(), "getInteractingEntity", 0);
            if (getInteractingEntity != null) return getInteractingEntity.invoke(local);
        } catch (Exception ignored) {}
        return null;
    }

    private void readPosition(GameState gs) {
        Object local = getLocal();
        if (local == null) { gs.posX = -1; gs.posY = -1; gs.posZ = -1; return; }
        try {
            if (getX     == null) getX     = findMethod(local.getClass(), "getX",     0);
            if (getY     == null) getY     = findMethod(local.getClass(), "getY",     0);
            if (getPlane == null) getPlane = findMethod(local.getClass(), "getPlane", 0);
            gs.posX = getX     != null ? (int) getX.invoke(local)     : readCoordField(local, "x");
            gs.posY = getY     != null ? (int) getY.invoke(local)     : readCoordField(local, "y");
            gs.posZ = getPlane != null ? (int) getPlane.invoke(local) : readCoordField(local, "plane");
        } catch (Exception e) { gs.posX = -1; gs.posY = -1; gs.posZ = -1; }
    }

    private int readCoordField(Object actor, String fieldName) {
        try {
            Field f = findField(actor.getClass(), fieldName);
            if (f != null) return f.getInt(actor);
        } catch (Exception ignored) {}
        return -1;
    }

    private String readTarget() {
        Object t = readInteractingTarget();
        if (t == null) return "";
        try {
            if (getName == null) getName = findMethod(t.getClass(), "getName", 0);
            return getName != null ? (String) getName.invoke(t) : "???";
        } catch (Exception e) { return ""; }
    }

    private void readEquipment(GameState gs) {
        Object local = getLocal();
        if (local == null) return;
        try {
            if (getEquipmentIds == null)
                getEquipmentIds = findMethod(local.getClass(), "getEquipmentIds", 0);
            if (getEquipmentIds != null) {
                int[] ids = (int[]) getEquipmentIds.invoke(local);
                if (ids != null) System.arraycopy(ids, 0, gs.equipIds, 0, Math.min(ids.length, 14));
                return;
            }
        } catch (Exception ignored) {}
        try {
            Field f = client.getClass().getDeclaredField("myPlayerEquipmentIds");
            f.setAccessible(true);
            int[] ids = (int[]) f.get(null);
            if (ids != null) System.arraycopy(ids, 0, gs.equipIds, 0, Math.min(ids.length, 14));
        } catch (Exception ignored) {}
    }

    private void readTargetVitals(GameState gs, Object target) {
        gs.targetHp = gs.targetMaxHp = -1;
        if (target == null) return;
        try {
            if (actorCurrentHealthField == null) {
                actorCurrentHealthField = findField(target.getClass(), "currentHealth");
                actorMaxHealthField     = findField(target.getClass(), "maxHealth");
            }
            if (actorCurrentHealthField != null) gs.targetHp = actorCurrentHealthField.getInt(target);
            if (actorMaxHealthField != null) gs.targetMaxHp = actorMaxHealthField.getInt(target);
            if (gs.targetMaxHp <= 0 && gs.targetHp > 0) gs.targetMaxHp = Math.max(gs.targetHp, 99);
        } catch (Exception ignored) {}
    }

    private void readTargetEquipment(GameState gs, Object target) {
        if (target == null) return;
        try {
            if (playerEquipmentField == null)
                playerEquipmentField = findField(target.getClass(), "equipmentItemId");
            int[] eq = null;
            if (playerEquipmentField != null) eq = (int[]) playerEquipmentField.get(target);
            if (eq == null) {
                Method getEq = findMethod(target.getClass(), "getEquipmentIds", 0);
                if (getEq != null) eq = (int[]) getEq.invoke(target);
            }
            if (eq == null) return;
            int n = Math.min(eq.length, gs.targetEquipIds.length);
            for (int i = 0; i < n; i++) {
                int raw = eq[i];
                gs.targetEquipIds[i] = raw > 0 ? raw : 0;
            }
        } catch (Exception ignored) {}
    }

    private int readPrayerFlags() {
        int flags = 0;
        if (isPrayerActive("PROTECT_FROM_MAGIC"))    flags |= 1;
        if (isPrayerActive("PROTECT_FROM_MISSILES")) flags |= 2;
        if (isPrayerActive("PROTECT_FROM_MELEE"))    flags |= 4;
        if (isPrayerActive("PIETY"))                 flags |= 8;
        if (isPrayerActive("RIGOUR"))                flags |= 16;
        if (isPrayerActive("MYSTIC_MIGHT"))          flags |= 32;
        return flags;
    }

    private boolean isPrayerActive(String enumName) {
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            Object prayer = p.getField(enumName).get(null);
            return p.getField("isActive").getBoolean(prayer);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVengeanceActive() {
        try {
            if (getVarbit != null) {
                int v = (int) getVarbit.invoke(client, 2450);
                return v > 0;
            }
        } catch (Exception ignored) {}
        try {
            Class<?> p = RtLookup.prayer();
            if (p == null) throw new ClassNotFoundException("p");
            Object prayer = p.getField("VENGEANCE").get(null);
            return p.getField("isActive").getBoolean(prayer);
        } catch (Exception ignored) {}
        return false;
    }

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        for (Method m : cls.getMethods())
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
              catch (Exception e) { break; }
        }
        return null;
    }
}
