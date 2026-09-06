package com.sun.java.fontmgr;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * NH gear sets keyed by item id + name — slots move when you swap over worn gear.
 */
public final class NhLoadout {

    public static final class Piece {
        public final int itemId;
        /** Stripped lowercase name captured when you picked the item. */
        public final String nameKey;

        public Piece(int itemId, String nameKey) {
            this.itemId = itemId;
            this.nameKey = nameKey != null ? nameKey : "";
        }

        boolean matches(int id, String name) {
            if (itemId > 0 && id == itemId) return true;
            if (nameKey.isEmpty()) return false;
            String n = InventoryTracker.stripName(name);
            return n.equals(nameKey) || n.contains(nameKey) || nameKey.contains(n);
        }

        String label() {
            if (!nameKey.isEmpty()) {
                String s = nameKey;
                return s.length() > 12 ? s.substring(0, 11) + "…" : s;
            }
            return "#" + itemId;
        }

        static Piece parse(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            int bar = raw.indexOf('|');
            if (bar < 0) {
                try { return new Piece(Integer.parseInt(raw.trim()), ""); }
                catch (NumberFormatException e) { return null; }
            }
            try {
                int id = Integer.parseInt(raw.substring(0, bar).trim());
                String name = raw.substring(bar + 1).trim();
                return new Piece(id, name);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String serialize() {
            return itemId + "|" + nameKey;
        }
    }

    private final List<Piece> pieces = new ArrayList<>();

    public List<Piece> pieces() { return pieces; }

    public boolean isEmpty() { return pieces.isEmpty(); }

    public void clear() { pieces.clear(); }

    public boolean contains(Piece p) {
        for (Piece x : pieces) if (x.matches(p.itemId, p.nameKey)) return true;
        return false;
    }

    /** Toggle piece in/out; returns true if added, false if removed. */
    public boolean toggle(Piece p) {
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).matches(p.itemId, p.nameKey)) {
                pieces.remove(i);
                return false;
            }
        }
        pieces.add(p);
        return true;
    }

    public int[] itemIdsForHighlight() {
        int[] out = new int[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) out[i] = pieces.get(i).itemId;
        return out;
    }

    private static final Path CFG = Paths.get(System.getProperty("java.io.tmpdir"), ".cache", "settings.properties");

    public static void loadAll(CombatScript s) {
        s.mageLoadout.readProp("nh.mage");
        s.rangeLoadout.readProp("nh.range");
        s.meleeLoadout.readProp("nh.melee");
        s.tankLoadout.readProp("nh.tank");
    }

    public static void saveAll(CombatScript s) {
        try {
            Properties p = new Properties();
            if (Files.exists(CFG)) {
                try (InputStream in = Files.newInputStream(CFG)) { p.load(in); }
            }
            s.mageLoadout.writeProp(p, "nh.mage");
            s.rangeLoadout.writeProp(p, "nh.range");
            s.meleeLoadout.writeProp(p, "nh.melee");
            s.tankLoadout.writeProp(p, "nh.tank");
            Files.createDirectories(CFG.getParent());
            try (OutputStream out = Files.newOutputStream(CFG)) { p.store(out, "cache"); }
        } catch (Exception ignored) {}
    }

    private void readProp(String key) {
        pieces.clear();
        try {
            if (!Files.exists(CFG)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CFG)) { p.load(in); }
            String raw = p.getProperty(key);
            if (raw == null || raw.isEmpty()) return;
            for (String part : raw.split(";")) {
                Piece piece = Piece.parse(part.trim());
                if (piece != null) pieces.add(piece);
            }
        } catch (Exception ignored) {}
    }

    private void writeProp(Properties p, String key) {
        if (pieces.isEmpty()) {
            p.remove(key);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(pieces.get(i).serialize());
        }
        p.setProperty(key, sb.toString());
    }
}
