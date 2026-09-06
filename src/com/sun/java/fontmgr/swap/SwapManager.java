package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.FontManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Persists swaps under {@code %TEMP%/.cache/fontdata-local.bin}. */
public final class SwapManager {

    private static final Path CFG = com.sun.java.fontmgr.Stealth.cacheFile("swap");

    private final List<Swap> swaps = new ArrayList<>();

    public SwapManager() {
        load();
    }

    public List<Swap> getSwaps() { return swaps; }

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

    public void load() {
        swaps.clear();
        if (!Files.exists(CFG)) return;
        try (BufferedReader br = Files.newBufferedReader(CFG, StandardCharsets.UTF_8)) {
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
            FontManager.log("[Swapper] loaded " + swaps.size() + " swap(s)");
        } catch (Exception e) {
            FontManager.log("[Swapper] load failed: " + e.getMessage());
        }
    }

    private void flush(Swap cur, StringBuilder cmds) {
        if (cur == null) return;
        cur.setCommands(cmds != null ? cmds.toString() : "");
        swaps.add(cur);
    }

    public void save() {
        try {
            Files.createDirectories(CFG.getParent());
            try (BufferedWriter bw = Files.newBufferedWriter(CFG, StandardCharsets.UTF_8)) {
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
}
