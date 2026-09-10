package com.sun.java.fontmgr;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection API for the wild looter. Mirrors how the real client clicks so the
 * bot behaves exactly like a human: doAction with the same opcodes/params the
 * menu pipeline produces, doWalkTo for movement, and the packet helper for
 * button presses.
 */
public final class LooterApi {

    private final Object client;
    private final Class<?> clientClass;

    // Resolved lazily, cached forever.
    private Method getPlayerRealX, getPlayerRealY, getLocalPlayer, doActionM, doWalkToM;
    private Method getItemContainerM;
    private Field baseXField, baseYField, planeField, groundItemsField, openInterfaceField;
    private Field interfaceCacheField;
    private Method nodeGetFirst, nodeGetNext;
    private Map<String, Field> actorFields = new HashMap<>();
    private Map<String, Field> itemFields = new HashMap<>();
    private Field npcsField, npcIndicesField, npcCountField;
    private Method defTransformM;
    private Object packetHelper;
    private Method sendClickingButtonM;

    // Item definition name cache (id -> name) to avoid hammering ItemDef.
    private final Map<Integer, String> itemNameCache = new HashMap<>();

    public LooterApi(Object client) {
        this.client = client;
        this.clientClass = client != null ? client.getClass() : null;
    }

    public Object client() { return client; }

    // ════════════════════════════════════════════════════════════════════════
    //  Player / region
    // ════════════════════════════════════════════════════════════════════════

    public int realX() {
        try {
            if (getPlayerRealX == null) getPlayerRealX = publicMethod("getPlayerRealX", 0);
            return getPlayerRealX != null ? (int) getPlayerRealX.invoke(client) : -1;
        } catch (Exception e) { return -1; }
    }

    public int realY() {
        try {
            if (getPlayerRealY == null) getPlayerRealY = publicMethod("getPlayerRealY", 0);
            return getPlayerRealY != null ? (int) getPlayerRealY.invoke(client) : -1;
        } catch (Exception e) { return -1; }
    }

    public int baseX() {
        try {
            if (baseXField == null) baseXField = field(clientClass, "baseX");
            return baseXField != null ? baseXField.getInt(client) : -1;
        } catch (Exception e) { return -1; }
    }

    public int baseY() {
        try {
            if (baseYField == null) baseYField = field(clientClass, "baseY");
            return baseYField != null ? baseYField.getInt(client) : -1;
        } catch (Exception e) { return -1; }
    }

    public int plane() {
        try {
            if (planeField == null) planeField = field(clientClass, "plane");
            if (planeField == null) return 0;
            if (Modifier.isStatic(planeField.getModifiers())) return planeField.getInt(null);
            return planeField.getInt(client);
        } catch (Exception e) { return 0; }
    }

    /** True when the destination world tile lies inside the loaded 104x104 region. */
    public boolean inRegion(int wx, int wy) {
        int lx = wx - baseX();
        int ly = wy - baseY();
        return lx >= 0 && lx < 104 && ly >= 0 && ly < 104;
    }

    public int toLocalX(int wx) { return wx - baseX(); }
    public int toLocalY(int wy) { return wy - baseY(); }

    public int[] localPlayerTile() {
        Object p = localPlayer();
        if (p == null) return new int[]{-1, -1};
        try {
            Field sx = actorField(p, "smallX");
            Field sy = actorField(p, "smallY");
            if (sx == null || sy == null) return new int[]{-1, -1};
            int[] xa = (int[]) sx.get(p);
            int[] ya = (int[]) sy.get(p);
            if (xa == null || ya == null || xa.length == 0) return new int[]{-1, -1};
            return new int[]{xa[0], ya[0]};
        } catch (Exception e) { return new int[]{-1, -1}; }
    }

    private Object localPlayer() {
        try {
            if (getLocalPlayer == null) getLocalPlayer = publicMethod("getLocalPlayer", 0);
            if (getLocalPlayer != null) return getLocalPlayer.invoke(client);
        } catch (Exception ignored) {}
        return null;
    }

    public int hp() {
        try {
            Field f = field(clientClass, "currentSkillLevel");
            if (f == null) return 99;
            Object holder = Modifier.isStatic(f.getModifiers()) ? null : client;
            int[] levels = (int[]) f.get(holder);
            return levels != null && levels.length > 3 && levels[3] > 0 ? levels[3] : 99;
        } catch (Exception e) { return 99; }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inventory
    // ════════════════════════════════════════════════════════════════════════

    /** Raw inventory ids (stored as id+1, 0 = empty), length 28 or -1 array. */
    public int[] inventoryRaw() {
        try {
            Object iface = interfaceCache()[3214];
            if (iface == null) return new int[28];
            Field f = field(iface.getClass(), "inventoryItemId");
            if (f == null) return new int[28];
            int[] ids = (int[]) f.get(iface);
            return ids != null ? ids.clone() : new int[28];
        } catch (Exception e) { return new int[28]; }
    }

    private Object[] interfaceCache() {
        try {
            if (interfaceCacheField == null) {
                Class<?> rsi = RtLookup.rsInterface();
                if (rsi == null) return new Object[0];
                interfaceCacheField = rsi.getDeclaredField("interfaceCache");
                interfaceCacheField.setAccessible(true);
            }
            return (Object[]) interfaceCacheField.get(null);
        } catch (Exception e) { return new Object[0]; }
    }

    public int freeSlots() {
        int[] inv = inventoryRaw();
        int free = 0;
        for (int id : inv) if (id <= 0) free++;
        return free;
    }

    public boolean inventoryEmpty() {
        return freeSlots() >= 28;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Bank / interface state
    // ════════════════════════════════════════════════════════════════════════

    public boolean bankOpen() {
        try {
            if (openInterfaceField == null) {
                openInterfaceField = clientClass.getDeclaredField("openInterfaceID");
                openInterfaceField.setAccessible(true);
            }
            int v = Modifier.isStatic(openInterfaceField.getModifiers())
                    ? openInterfaceField.getInt(null) : openInterfaceField.getInt(client);
            return v == 29000;
        } catch (Exception e) { return false; }
    }

    /** Close any top-level interface (opcode 200 path — the Close menu click). */
    public void closeTopInterface() {
        doAction(-1, -1, 200, -1, "Close", "");
    }

    /**
     * Deposit inventory via the bank's "Deposit inventory" button (child 29012,
     * ButtonWithHover). Real clicks route through opcode 315 → default case →
     * frame 185 with the button id; calling the packet helper directly is the
     * same wire action.
     */
    public boolean depositInventoryButton() {
        Object ph = packetHelper();
        if (ph != null && sendClickingButtonM != null) {
            try {
                sendClickingButtonM.invoke(ph, 29012);
                return true;
            } catch (Exception e) {
                FontManager.debug("[Looter] sendClickingButton(29012) failed: " + e.getMessage());
            }
        }
        // Menu-path equivalent (frame 185 with the button id as well).
        doAction(-1, 29012, 315, -1, "Deposit inventory", "");
        return true;
    }

    private Object packetHelper() {
        try {
            if (packetHelper == null) {
                Method m = publicMethod("getPacketHelper", 0);
                if (m != null) {
                    packetHelper = m.invoke(client);
                    if (packetHelper != null) {
                        sendClickingButtonM = Reflect.method(packetHelper.getClass(), "sendClickingButton", 1);
                    }
                }
            }
            return packetHelper;
        } catch (Exception e) { return null; }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  doAction / doWalkTo
    // ════════════════════════════════════════════════════════════════════════

    public void doAction(int p0, int p1, int opcode, int id, String option, String target) {
        try {
            if (doActionM == null) doActionM = RtLookup.doAction(clientClass);
            if (doActionM != null) doActionM.invoke(client, 0, p0, p1, opcode, id, -1, option, target, -1, -1);
        } catch (Exception e) {
            FontManager.debug("[Looter] doAction fail: " + e.getMessage());
        }
    }

    /**
     * Take a ground item: same opcode/params a real Take click produces
     * (opcode 234 → client walks into range, then sends frame 236).
     */
    public void clickTake(int localX, int localY, int itemId) {
        doAction(localX, localY, 234, itemId, "Take", "");
    }

    /** Walk to a local (region 0..103) tile, mirroring the terrain-click path. */
    public void walkLocal(int localX, int localY) {
        try {
            if (doWalkToM == null) doWalkToM = Reflect.declaredMethod(clientClass, "doWalkTo", 11);
            if (doWalkToM == null) return;
            int[] me = localPlayerTile();
            int startX = me[0] < 0 ? localX : me[0];
            int startY = me[1] < 0 ? localY : me[1];
            doWalkToM.invoke(client, 0, 0, 0, 0, startY, 0, 0, localY, startX, true, localX);
        } catch (Exception e) {
            FontManager.debug("[Looter] walkLocal fail: " + e.getMessage());
        }
    }

    /**
     * Walk toward a world tile. If the destination is outside the loaded region
     * we step to the farthest reachable point along the straight line and let
     * the caller re-invoke after the region follows.
     */
    public void walkTowardWorld(int wx, int wy) {
        int lx = wx - baseX();
        int ly = wy - baseY();
        if (lx >= 0 && lx < 104 && ly >= 0 && ly < 104) {
            walkLocal(lx, ly);
            return;
        }
        // Clamp to a reachable margin inside the loaded region.
        int clx = Math.max(6, Math.min(97, lx));
        int cly = Math.max(6, Math.min(97, ly));
        walkLocal(clx, cly);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Ground item scan
    // ════════════════════════════════════════════════════════════════════════

    public static final class Loot {
        public final int itemId;
        public final int amount;
        public final int localX;
        public final int localY;
        public final int worldX;
        public final int worldY;
        public final int price;
        public final String name;
        public final boolean always;

        public Loot(int itemId, int amount, int localX, int localY,
                    int worldX, int worldY, int price, String name, boolean always) {
            this.itemId = itemId; this.amount = amount; this.localX = localX;
            this.localY = localY; this.worldX = worldX; this.worldY = worldY;
            this.price = price; this.name = name; this.always = always;
        }

        public long totalValue() { return (long) price * amount; }
    }

    /**
     * Scan ground items near the player (square window of {@code radius} tiles).
     * Distance is measured from the player's world position.
     */
    public List<Loot> scanGround(int radius, int minPkValue, String alwaysCsv) {
        List<Loot> out = new ArrayList<>();
        int baseX = baseX(), baseY = baseY();
        int pX = realX(), pY = realY();
        if (baseX < 0 || pX < 0) return out;
        try {
            Object[][][] arr = groundItemsArray();
            if (arr == null) return out;
            int pl = Math.max(0, plane());
            if (pl >= arr.length) return out;
            int[] me = localPlayerTile();
            int cx = me[0], cy = me[1];
            if (cx < 0) return out;
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || x >= 104 || y < 0 || y >= 104) continue;
                    Object[][] planeArr = arr[pl];
                    if (planeArr == null) continue;
                    Object[] col = planeArr[x];
                    if (col == null) continue;
                    Object list = col[y];
                    if (list == null) continue;
                    Object node = nodeFirst(list);
                    while (node != null) {
                        Loot l = lootFrom(node, x, y, baseX, baseY, pX, pY, radius, minPkValue, alwaysCsv);
                        if (l != null) out.add(l);
                        node = nodeNext(list);
                    }
                }
            }
        } catch (Exception e) {
            FontManager.debug("[Looter] scanGround fail: " + e.getMessage());
        }
        return out;
    }

    private Loot lootFrom(Object node, int x, int y, int baseX, int baseY,
                          int pX, int pY, int radius, int minPkValue, String alwaysCsv) {
        try {
            Field fId = itemField(node, "itemId");
            Field fAmt = itemField(node, "itemAmount");
            Field fX = itemField(node, "x");
            Field fY = itemField(node, "y");
            if (fId == null || fAmt == null) return null;
            int id = fId.getInt(node);
            if (id <= 0) return null;
            int amount = fAmt != null ? fAmt.getInt(node) : 1;
            int lx = fX != null ? fX.getInt(node) : x;
            int ly = fY != null ? fY.getInt(node) : y;
            int wx = baseX + lx, wy = baseY + ly;
            int dx = wx - pX, dy = wy - pY;
            if (dx * dx + dy * dy > radius * radius) return null;
            int price = PkPriceTable.price(id);
            String name = itemName(id);
            boolean always = PkPriceTable.nameMatchesAny(name, alwaysCsv);
            if (!always && (long) price * amount < minPkValue) return null;
            return new Loot(id, amount, lx, ly, wx, wy, price, name, always);
        } catch (Exception e) {
            return null;
        }
    }

    private Object[][][] groundItemsArray() {
        try {
            if (groundItemsField == null) groundItemsField = field(clientClass, "groundItemsArray");
            return groundItemsField != null ? (Object[][][]) groundItemsField.get(client) : null;
        } catch (Exception e) { return null; }
    }

    public String itemName(int itemId) {
        String cached = itemNameCache.get(itemId);
        if (cached != null) return cached;
        try {
            Class<?> itemDef = RtLookup.itemDef();
            if (itemDef == null) return "";
            Method forId = null;
            for (Method m : itemDef.getMethods()) {
                if (m.getName().equals("forID") && m.getParameterCount() == 1) { forId = m; break; }
            }
            if (forId == null) return "";
            Object def = forId.invoke(null, itemId);
            if (def == null) return "";
            Field name = field(def.getClass(), "name");
            String n = name != null ? (String) name.get(def) : "";
            if (n == null) n = "";
            itemNameCache.put(itemId, n);
            return n;
        } catch (Exception e) { return ""; }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Bank interactable discovery
    // ════════════════════════════════════════════════════════════════════════

    public static final class BankTarget {
        public final int kind;          // 0 = NPC, 1 = object
        public final int uid;           // npc index or object id
        public final int localX, localY;
        public final int opcode;
        public final String option;
        public final String label;

        public BankTarget(int kind, int uid, int localX, int localY,
                          int opcode, String option, String label) {
            this.kind = kind; this.uid = uid; this.localX = localX;
            this.localY = localY; this.opcode = opcode;
            this.option = option; this.label = label;
        }
    }

    private static final int[] NPC_OPCODES = {20, 412, 225, 965, 478};
    private static final int[] OBJ_OPCODES = {502, 900, 113, 872, 1062};

    /** Click a bank target with the semantics of a real menu selection. */
    public void clickBank(BankTarget t) {
        doAction(t.localX, t.localY, t.opcode, t.uid, t.option, t.label);
    }

    /** Nearest NPC whose name/actions look like a banker within {@code radius} tiles. */
    public BankTarget findBankNpc(int radius, String optCsv) {
        try {
            if (npcsField == null) npcsField = field(clientClass, "npcs");
            if (npcIndicesField == null) npcIndicesField = field(clientClass, "npcIndices");
            if (npcCountField == null) npcCountField = field(clientClass, "npcCount");
            if (npcsField == null || npcIndicesField == null || npcCountField == null) return null;
            Object[] npcs = (Object[]) npcsField.get(client);
            int[] indices = (int[]) npcIndicesField.get(client);
            int count = npcCountField.getInt(client);
            if (npcs == null || indices == null) return null;
            int baseX = baseX(), baseY = baseY();
            int pX = realX(), pY = realY();
            BankTarget best = null;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                int idx = indices[i];
                if (idx < 0 || idx >= npcs.length) continue;
                Object npc = npcs[idx];
                if (npc == null) continue;
                int[] tile = npcLocalTile(npc);
                if (tile[0] < 0 || tile[0] >= 104 || tile[1] < 0 || tile[1] >= 104) continue;
                int wx = baseX + tile[0], wy = baseY + tile[1];
                int d = (wx - pX) * (wx - pX) + (wy - pY) * (wy - pY);
                if (d > radius * radius) continue;
                Object def = npcDef(npc);
                if (def == null) continue;
                String name = defName(def);
                String[] actions = defActions(def);
                if (name == null) continue;
                int slot = actionSlot(actions, optCsv);
                if (slot < 0) continue;
                if (d < bestDist) {
                    bestDist = d;
                    int op = slot < NPC_OPCODES.length ? NPC_OPCODES[slot] : 20;
                    best = new BankTarget(0, idx, tile[0], tile[1], op,
                            actions[slot], clean(name));
                }
            }
            return best;
        } catch (Exception e) { return null; }
    }

    private int[] npcLocalTile(Object npc) {
        try {
            Field sx = actorField(npc, "smallX");
            Field sy = actorField(npc, "smallY");
            if (sx != null && sy != null) {
                int[] xa = (int[]) sx.get(npc);
                int[] ya = (int[]) sy.get(npc);
                if (xa != null && ya != null && xa.length > 0
                        && xa[0] >= 0 && xa[0] < 104 && ya[0] >= 0 && ya[0] < 104) {
                    return new int[]{xa[0], ya[0]};
                }
            }
            Field xf = field(npc.getClass(), "x");
            Field yf = field(npc.getClass(), "y");
            if (xf != null && yf != null) {
                int x = xf.getInt(npc) >> 7;
                int y = yf.getInt(npc) >> 7;
                if (x >= 0 && x < 104 && y >= 0 && y < 104) return new int[]{x, y};
            }
            return new int[]{-1, -1};
        } catch (Exception e) { return new int[]{-1, -1}; }
    }

    private Object npcDef(Object npc) {
        try {
            Field d = field(npc.getClass(), "desc");
            if (d == null) return null;
            Object def = d.get(npc);
            return resolveDef(def);
        } catch (Exception e) { return null; }
    }

    /** Apply transform() when the def has transforms (mirrors buildAtNPCMenu). */
    private Object resolveDef(Object def) {
        if (def == null) return null;
        try {
            Field tr = field(def.getClass(), "transforms");
            if (tr != null) {
                int[] transforms = (int[]) tr.get(def);
                if (transforms != null) {
                    if (defTransformM == null) {
                        for (Method m : def.getClass().getMethods()) {
                            if (m.getName().equals("transform") && m.getParameterCount() == 0) {
                                defTransformM = m;
                                break;
                            }
                        }
                    }
                    if (defTransformM != null) {
                        Object t = defTransformM.invoke(def);
                        if (t != null) def = t;
                    }
                }
            }
        } catch (Exception ignored) {}
        return def;
    }

    private String defName(Object def) {
        try {
            Field f = field(def.getClass(), "name");
            return f != null ? (String) f.get(def) : null;
        } catch (Exception e) { return null; }
    }

    private String[] defActions(Object def) {
        try {
            Field f = field(def.getClass(), "actions");
            return f != null ? (String[]) f.get(def) : null;
        } catch (Exception e) { return null; }
    }

    private int actionSlot(String[] actions, String optCsv) {
        if (actions == null) return -1;
        for (int i = 0; i < actions.length; i++) {
            if (actions[i] == null) continue;
            String a = actions[i].toLowerCase();
            if (a.contains("bank") || a.contains("use-quickly")) return i;
        }
        if (optCsv != null && !optCsv.isEmpty()) {
            for (int i = 0; i < actions.length; i++) {
                if (actions[i] == null) continue;
                for (String tok : optCsv.split(",")) {
                    if (tok.trim().isEmpty()) continue;
                    if (actions[i].toLowerCase().contains(tok.trim().toLowerCase())) return i;
                }
            }
        }
        return -1;
    }

    private static String clean(String name) {
        if (name == null) return "";
        return name.replaceAll("<[^>]*>", "").replaceAll("@[^@]*@", "").trim();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Reflection helpers
    // ════════════════════════════════════════════════════════════════════════

    private Method publicMethod(String name, int arity) {
        if (clientClass == null) return null;
        for (Method m : clientClass.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
        }
        return null;
    }

    private static Field field(Class<?> cls, String name) {
        if (cls == null) return null;
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Field actorField(Object actor, String name) {
        if (actor == null) return null;
        String key = actor.getClass().getName() + "#" + name;
        Field cached = actorFields.get(key);
        if (cached != null) return cached;
        Field f = field(actor.getClass(), name);
        if (f != null) actorFields.put(key, f);
        return f;
    }

    private Field itemField(Object node, String name) {
        if (node == null) return null;
        String key = node.getClass().getName() + "#" + name;
        Field cached = itemFields.get(key);
        if (cached != null) return cached;
        Field f = field(node.getClass(), name);
        if (f != null) itemFields.put(key, f);
        return f;
    }

    private Object nodeFirst(Object nodeList) {
        try {
            if (nodeGetFirst == null) nodeGetFirst = Reflect.method(nodeList.getClass(), "reverseGetFirst", 0);
            if (nodeGetFirst != null) return nodeGetFirst.invoke(nodeList);
        } catch (Exception ignored) {}
        return null;
    }

    private Object nodeNext(Object nodeList) {
        try {
            if (nodeGetNext == null) nodeGetNext = Reflect.method(nodeList.getClass(), "reverseGetNext", 0);
            if (nodeGetNext != null) return nodeGetNext.invoke(nodeList);
        } catch (Exception ignored) {}
        return null;
    }
}
