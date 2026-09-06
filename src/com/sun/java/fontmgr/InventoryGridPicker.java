package com.sun.java.fontmgr;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 4×7 (28-slot) inventory grid picker for NH loadout assignment. Click or drag
 * across cells to toggle selection. Used by the consolidated OverlayUI.
 */
final class InventoryGridPicker extends JPanel {

    static final int COLS  = 4;
    static final int ROWS  = 7;
    static final int SLOTS = COLS * ROWS;   // 28
    static final int CELL  = 28;
    static final int GAP   = 3;

    private final String setName;
    private final Color  accent;
    private final boolean[] selected = new boolean[SLOTS];
    private final List<IntConsumer> listeners = new ArrayList<>();

    private static final Color C_INPUT   = new Color(18, 18, 24);
    private static final Color C_BORDER  = new Color(38, 38, 50);
    private static final Color C_FG_HINT = new Color(80, 80, 95);
    private static final Font  F_XS      = new Font("Segoe UI", Font.PLAIN, 10);

    InventoryGridPicker(String setName, Color accent, int[] preselected) {
        this.setName = setName;
        this.accent  = accent;

        if (preselected != null) {
            for (int s : preselected) {
                if (s >= 0 && s < SLOTS) selected[s] = true;
            }
        }

        int pw = COLS * (CELL + GAP) - GAP + 2;
        int ph = ROWS * (CELL + GAP) - GAP + 2;
        setPreferredSize(new Dimension(pw, ph));
        setMinimumSize(new Dimension(pw, ph));
        setMaximumSize(new Dimension(pw, ph));
        setOpaque(false);

        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { handleClick(e.getX(), e.getY()); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            private int lastToggled = -1;
            @Override public void mouseDragged(MouseEvent e) {
                int slot = slotAt(e.getX(), e.getY());
                if (slot >= 0 && slot != lastToggled) {
                    lastToggled = slot;
                    selected[slot] = !selected[slot];
                    repaint();
                }
            }
        });
    }

    private void handleClick(int mx, int my) {
        int slot = slotAt(mx, my);
        if (slot < 0) return;
        selected[slot] = !selected[slot];
        repaint();
        for (IntConsumer l : listeners) l.accept(slot);
    }

    private int slotAt(int mx, int my) {
        int col = mx / (CELL + GAP);
        int row = my / (CELL + GAP);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        int cx = col * (CELL + GAP);
        int cy = row * (CELL + GAP);
        if (mx > cx + CELL || my > cy + CELL) return -1;
        return row * COLS + col;
    }

    int[] getSelectedSlots() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) if (selected[i]) list.add(i);
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    void setSelectedSlots(int[] slots) {
        Arrays.fill(selected, false);
        if (slots != null) {
            for (int s : slots) if (s >= 0 && s < SLOTS) selected[s] = true;
        }
        repaint();
    }

    void addSelectionListener(IntConsumer l) { listeners.add(l); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int slot = 0; slot < SLOTS; slot++) {
            int col = slot % COLS;
            int row = slot / COLS;
            int x = col * (CELL + GAP);
            int y = row * (CELL + GAP);
            boolean sel = selected[slot];

            g2.setColor(sel ? accent.darker() : C_INPUT);
            g2.fillRoundRect(x, y, CELL, CELL, 5, 5);
            g2.setColor(sel ? accent : C_BORDER);
            g2.drawRoundRect(x, y, CELL - 1, CELL - 1, 5, 5);

            g2.setFont(F_XS);
            g2.setColor(sel ? Color.WHITE : C_FG_HINT);
            String num = String.valueOf(slot);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(num,
                    x + (CELL - fm.stringWidth(num)) / 2,
                    y + (CELL - fm.getHeight()) / 2 + fm.getAscent());

            if (sel) {
                g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                g2.setColor(accent.brighter());
                g2.drawString("✓", x + 2, y + CELL - 4);
            }
        }
        g2.dispose();
    }
}
