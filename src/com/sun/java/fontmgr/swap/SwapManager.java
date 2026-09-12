package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.FontManager;
import com.sun.java.fontmgr.Stealth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Persists named PK loadouts (profiles). Each loadout holds its own list of gear
 * swaps. The active loadout is mirrored to {@code %TEMP%/.cache/fontdata-local.bin}
 * for backward compatibility; every loadout also has its own file under
 * {@code fontdata-loadout-&lt;name&gt;.bin}.
 */
public final class SwapManager {

    private static final Path ACTIVE_CFG = Stealth.cacheFile("swap");
    private static final Path INDEX_CFG = Stealth.cacheFile("swap-profiles");
    private static final String DEFAULT_PROFILE = "Default";

    private final List<Swap> swaps = new ArrayList<>();
    private final LinkedHashSet<String> profiles = new LinkedHashSet<>();
    private String activeProfile = DEFAULT_PROFILE;

    public SwapManager() {
        loadIndex();
        migrateLegacyIfNeeded();
        if (profiles.isEmpty()) {
            profiles.add(DEFAULT_PROFILE);
            activeProfile = DEFAULT_PROFILE;
            saveIndex();
        }
        if (!profilesContains(activeProfile)) {
            activeProfile = profiles.iterator().next();
            saveIndex();
        }
        loadActive();
    }

    public List<Swap> getSwaps() { return swaps; }

    public String getActiveProfile() { return activeProfile; }

    public List<String> listProfiles() {
        return Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    public Swap byName(String name) {
        if (name == null) return null;
        for (Swap s : swaps) {
            if (s.getName() != null && s.getName().equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    public Swap byHotkey(int keyCode, int modifiers) {
        for (Swap s : swaps) {
            if (s.hasHotkey() && s.getKeyCode() == keyCode && s.getModifiers() == modifiers)
                return s;
        }
        return null;
    }

    public void addOrUpdate(Swap swap) {
        if (swap == null || swap.getName() == null) return;
        Swap existing = byName(swap.getName());
        if (existing != null) swaps.remove(existing);
        swaps.add(swap);
        save();
    }

    /** Next unused slot name: Gear 1, Gear 2, … */
    public String nextGearSlotName() {
        int n = 1;
        while (byName("Gear " + n) != null) n++;
        return "Gear " + n;
    }

    public void delete(String name) {
        swaps.removeIf(s -> s.getName() != null && s.getName().equalsIgnoreCase(name));
        save();
    }

    public boolean renameSwap(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        String trimmed = newName.trim();
        if (trimmed.isEmpty()) return false;
        Swap s = byName(oldName);
        if (s == null) return false;
        if (!s.getName().equalsIgnoreCase(trimmed) && byName(trimmed) != null) return false;
        s.setName(trimmed);
        save();
        return true;
    }

    /**
     * Switch the active PK loadout. Saves the current list first so nothing is lost.
     * @return false if the named loadout does not exist
     */
    public boolean switchProfile(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String target = findProfile(name.trim());
        if (target == null) return false;
        if (target.equals(activeProfile)) return true;
        save();
        activeProfile = target;
        saveIndex();
        loadActive();
        FontManager.log("[Swapper] switched loadout: " + activeProfile);
        return true;
    }

    /**
     * Create a new empty loadout and switch to it.
     * @return false if the name is empty or already exists
     */
    public boolean createProfile(String name) {
        String n = normalizeProfileName(name);
        if (n == null || profilesContains(n) || fileTokenTaken(n, null)) return false;
        save();
        profiles.add(n);
        activeProfile = n;
        swaps.clear();
        saveIndex();
        save();
        FontManager.log("[Swapper] created loadout: " + n);
        return true;
    }

    /**
     * Duplicate the current loadout under a new name and switch to the copy.
     * Use this to keep an old setup while editing a new one.
     */
    public boolean saveAsProfile(String name) {
        String n = normalizeProfileName(name);
        if (n == null || profilesContains(n) || fileTokenTaken(n, null)) return false;
        save();
        profiles.add(n);
        activeProfile = n;
        saveIndex();
        save();
        FontManager.log("[Swapper] saved loadout as: " + n);
        return true;
    }

    public boolean renameProfile(String oldName, String newName) {
        String from = findProfile(oldName);
        String to = normalizeProfileName(newName);
        if (from == null || to == null || profilesContains(to) || fileTokenTaken(to, from)) return false;
        if (from.equals(to)) return true;
        Path src = profilePath(from);
        Path dst = profilePath(to);
        try {
            if (Files.exists(src)) {
                Files.createDirectories(dst.getParent());
                if (src.equals(dst)) {
                    // Same on-disk token (e.g. only casing/spacing change) — index only.
                } else {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            FontManager.log("[Swapper] rename loadout failed: " + e.getMessage());
            return false;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>();
        for (String p : profiles) {
            next.add(p.equals(from) ? to : p);
        }
        profiles.clear();
        profiles.addAll(next);
        if (activeProfile.equals(from)) activeProfile = to;
        saveIndex();
        save();
        FontManager.log("[Swapper] renamed loadout: " + from + " -> " + to);
        return true;
    }

    /**
     * Delete a loadout. Refuses to delete the last remaining one.
     * If the active loadout is deleted, switches to another.
     */
    public boolean deleteProfile(String name) {
        String target = findProfile(name);
        if (target == null || profiles.size() <= 1) return false;
        profiles.remove(target);
        try {
            Files.deleteIfExists(profilePath(target));
        } catch (Exception ignored) {}
        if (activeProfile.equals(target)) {
            activeProfile = profiles.iterator().next();
            loadActive();
        }
        saveIndex();
        save();
        FontManager.log("[Swapper] deleted loadout: " + target);
        return true;
    }

    /** Reload the active loadout from disk (same as legacy {@code load()}). */
    public void load() {
        loadActive();
    }

    public void save() {
        writeSwaps(profilePath(activeProfile));
        // Mirror active loadout to the legacy path so older tooling still sees it.
        writeSwaps(ACTIVE_CFG);
    }

    private void loadActive() {
        Path path = profilePath(activeProfile);
        if (!Files.exists(path) && Files.exists(ACTIVE_CFG)
                && DEFAULT_PROFILE.equals(activeProfile)) {
            path = ACTIVE_CFG;
        }
        readSwaps(path);
        FontManager.log("[Swapper] loaded " + swaps.size() + " swap(s) [" + activeProfile + "]");
    }

    private void migrateLegacyIfNeeded() {
        if (!profiles.isEmpty()) return;
        if (!Files.exists(ACTIVE_CFG)) return;
        profiles.add(DEFAULT_PROFILE);
        activeProfile = DEFAULT_PROFILE;
        try {
            Path dest = profilePath(DEFAULT_PROFILE);
            if (!Files.exists(dest)) {
                Files.createDirectories(dest.getParent());
                Files.copy(ACTIVE_CFG, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            saveIndex();
            FontManager.log("[Swapper] migrated existing swaps into loadout: " + DEFAULT_PROFILE);
        } catch (Exception e) {
            FontManager.log("[Swapper] migrate failed: " + e.getMessage());
        }
    }

    private void loadIndex() {
        profiles.clear();
        activeProfile = DEFAULT_PROFILE;
        if (!Files.exists(INDEX_CFG)) return;
        try (BufferedReader br = Files.newBufferedReader(INDEX_CFG, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("active=")) {
                    String a = line.substring(7).trim();
                    if (!a.isEmpty()) activeProfile = a;
                } else {
                    profiles.add(line);
                }
            }
        } catch (Exception e) {
            FontManager.log("[Swapper] loadout index failed: " + e.getMessage());
        }
    }

    private void saveIndex() {
        try {
            Files.createDirectories(INDEX_CFG.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(INDEX_CFG, StandardCharsets.UTF_8)) {
                bw.write("active=" + activeProfile);
                bw.newLine();
                for (String p : profiles) {
                    bw.write(p);
                    bw.newLine();
                }
            }
        } catch (Exception e) {
            FontManager.log("[Swapper] save loadout index failed: " + e.getMessage());
        }
    }

    private void readSwaps(Path path) {
        swaps.clear();
        if (path == null || !Files.exists(path)) return;
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Swap cur = null;
            StringBuilder cmds = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("===") && line.endsWith("===") && line.length() > 6) {
                    flush(cur, cmds);
                    String name = line.substring(3, line.length() - 3).trim();
                    cur = new Swap(name, "");
                    cmds = new StringBuilder();
                    continue;
                }
                if (cur == null) continue;
                if (line.startsWith("hotkey=")) {
                    try { cur.setKeyCode(Integer.parseInt(line.substring(7).trim())); }
                    catch (NumberFormatException ignored) {}
                } else if (line.startsWith("modifiers=")) {
                    try { cur.setModifiers(Integer.parseInt(line.substring(10).trim())); }
                    catch (NumberFormatException ignored) {}
                } else if (!line.isEmpty()) {
                    if (cmds.length() > 0) cmds.append('\n');
                    cmds.append(line);
                }
            }
            flush(cur, cmds);
        } catch (Exception e) {
            FontManager.log("[Swapper] load failed: " + e.getMessage());
        }
    }

    private void writeSwaps(Path path) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                for (Swap s : swaps) {
                    bw.write("===" + s.getName() + "===");
                    bw.newLine();
                    bw.write("hotkey=" + s.getKeyCode());
                    bw.newLine();
                    bw.write("modifiers=" + s.getModifiers());
                    bw.newLine();
                    if (s.getCommands() != null && !s.getCommands().isEmpty()) {
                        bw.write(s.getCommands());
                        if (!s.getCommands().endsWith("\n")) bw.newLine();
                    }
                }
            }
        } catch (Exception e) {
            FontManager.log("[Swapper] save failed: " + e.getMessage());
        }
    }

    private void flush(Swap cur, StringBuilder cmds) {
        if (cur == null) return;
        cur.setCommands(cmds != null ? cmds.toString() : "");
        swaps.add(cur);
    }

    private boolean profilesContains(String name) {
        return findProfile(name) != null;
    }

    private String findProfile(String name) {
        if (name == null) return null;
        for (String p : profiles) {
            if (p.equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /** True when another loadout already uses the same on-disk file token. */
    private boolean fileTokenTaken(String candidate, String ignoreProfile) {
        String token = sanitizeFileToken(candidate);
        for (String p : profiles) {
            if (ignoreProfile != null && p.equalsIgnoreCase(ignoreProfile)) continue;
            if (sanitizeFileToken(p).equals(token)) return true;
        }
        return false;
    }

    private static String normalizeProfileName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        if (n.indexOf('=') >= 0 || n.indexOf('\n') >= 0 || n.indexOf('\r') >= 0) return null;
        if (n.length() > 48) n = n.substring(0, 48).trim();
        return n.isEmpty() ? null : n;
    }

    static Path profilePath(String profileName) {
        return Stealth.cacheDir().resolve("fontdata-loadout-" + sanitizeFileToken(profileName) + ".bin");
    }

    static String sanitizeFileToken(String name) {
        if (name == null || name.isEmpty()) return "default";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        String s = sb.toString();
        if (s.isEmpty()) s = "loadout";
        if (s.length() > 40) s = s.substring(0, 40);
        return s.toLowerCase(Locale.ROOT);
    }
}
