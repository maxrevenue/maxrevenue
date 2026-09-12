package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.HotkeyManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;

/** Compact Advanced Swapper editor for the MiniOverlay SWAP tab. */
public final class SwapperPanel extends JPanel {

    private static final Color FG = new Color(240, 242, 245);
    private static final Color MUTED = new Color(150, 155, 165);
    private static final Color ACCENT = new Color(255, 195, 60);
    private static final Color BTN_BG = new Color(42, 45, 52);
    private static final Color BTN_BD = new Color(60, 65, 75);
    private static final Color LIST_BG = new Color(30, 33, 38);

    private final SwapManager manager;
    private final SwapDispatcher dispatcher;
    private final com.sun.java.fontmgr.CombatScript script;

    private final DefaultComboBoxModel<String> profileModel = new DefaultComboBoxModel<>();
    private final JComboBox<String> profileCombo = new JComboBox<>(profileModel);
    private boolean syncingProfiles;

    private final DefaultListModel<Swap> listModel = new DefaultListModel<>();
    private final JList<Swap> list = new JList<>(listModel);
    private final JTextArea editor = new JTextArea(5, 18);
    private final JButton hotkeyBtn = new JButton("Hotkey: Not set");
    private Swap current;
    private boolean capturing;

    public SwapperPanel(SwapManager manager, SwapDispatcher dispatcher,
                        com.sun.java.fontmgr.CombatScript script) {
        this.manager = manager;
        this.dispatcher = dispatcher;
        this.script = script;
        setOpaque(false);
        setLayout(new BorderLayout(4, 4));
        setBorder(new EmptyBorder(2, 0, 0, 0));

        // ── Always-visible PK loadout chrome (NORTH) ─────────────────────────
        JPanel pinned = new JPanel();
        pinned.setOpaque(false);
        pinned.setLayout(new BoxLayout(pinned, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Gear Swapper · v" + com.sun.java.fontmgr.Product.VERSION);
        title.setForeground(ACCENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        pinned.add(title);
        pinned.add(Box.createVerticalStrut(2));

        JLabel loadoutLabel = new JLabel("PK Loadouts (switch full gear sets here)");
        loadoutLabel.setForeground(ACCENT);
        loadoutLabel.setFont(loadoutLabel.getFont().deriveFont(Font.BOLD, 10f));
        loadoutLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        pinned.add(loadoutLabel);
        pinned.add(Box.createVerticalStrut(1));

        profileCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        profileCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        profileCombo.setBackground(LIST_BG);
        profileCombo.setForeground(FG);
        profileCombo.setFont(profileCombo.getFont().deriveFont(11f));
        profileCombo.setToolTipText("PK loadout — switch gear sets without deleting swaps");
        profileCombo.addActionListener(e -> {
            if (syncingProfiles) return;
            Object sel = profileCombo.getSelectedItem();
            if (sel == null) return;
            switchLoadout(sel.toString());
        });
        pinned.add(profileCombo);
        pinned.add(Box.createVerticalStrut(2));
        pinned.add(row4(
                btn("New", e -> newLoadout()),
                btn("Save As", e -> saveAsLoadout()),
                btn("Rename", e -> renameLoadout()),
                btn("Delete", e -> deleteLoadout())));
        pinned.add(Box.createVerticalStrut(4));
        add(pinned, BorderLayout.NORTH);

        // ── Scrollable swap editor (CENTER) ──────────────────────────────────
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        list.setCellRenderer(new SwapCell());
        list.setBackground(LIST_BG);
        list.setForeground(FG);
        list.setSelectionBackground(new Color(55, 70, 95));
        list.setSelectionForeground(FG);
        list.setFont(list.getFont().deriveFont(11f));
        list.setVisibleRowCount(7);
        list.setFixedCellHeight(18);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectSwap(list.getSelectedValue());
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        listScroll.setPreferredSize(new Dimension(240, 130));
        listScroll.setBorder(BorderFactory.createLineBorder(BTN_BD));
        body.add(listScroll);
        body.add(Box.createVerticalStrut(3));

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        editor.setBackground(LIST_BG);
        editor.setForeground(FG);
        editor.setCaretColor(FG);
        editor.setLineWrap(true);
        editor.setRows(6);
        JScrollPane scroll = new JScrollPane(editor);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(240, 110));
        scroll.setBorder(BorderFactory.createLineBorder(BTN_BD));
        body.add(scroll);
        body.add(Box.createVerticalStrut(3));

        styleBtn(hotkeyBtn);
        hotkeyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        hotkeyBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        hotkeyBtn.setForeground(ACCENT);
        hotkeyBtn.setToolTipText("Click to bind · right-click to clear");
        hotkeyBtn.addActionListener(e -> bindHotkey());
        hotkeyBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) clearHotkey();
            }
        });
        body.add(hotkeyBtn);
        body.add(Box.createVerticalStrut(3));

        body.add(row(btn("Save", e -> saveString()), btn("New Swap", e -> createSwap())));
        body.add(Box.createVerticalStrut(2));
        body.add(row(btn("Save Gear", e -> saveCurrentGear()), btn("Delete", e -> deleteSwap())));
        body.add(Box.createVerticalStrut(2));
        body.add(row(btn("Equip", e -> { flushEditorToCurrent(); if (current != null) dispatcher.run(current); }),
                btn("Clone", e -> cloneCurrent())));
        body.add(Box.createVerticalStrut(2));
        body.add(row(btn("Rename Swap", e -> renameSwap()),
                btn("Arm Ice LC", e -> armIce())));
        body.add(Box.createVerticalStrut(4));

        JLabel nhTitle = new JLabel("NH snapshots");
        nhTitle.setForeground(ACCENT);
        nhTitle.setFont(nhTitle.getFont().deriveFont(Font.BOLD, 10f));
        nhTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(nhTitle);
        body.add(Box.createVerticalStrut(2));
        body.add(row4(
                btn("Mage", e -> snapshotNh(com.sun.java.fontmgr.CombatScript.NH_SET.MAGE)),
                btn("Range", e -> snapshotNh(com.sun.java.fontmgr.CombatScript.NH_SET.RANGE)),
                btn("Melee", e -> snapshotNh(com.sun.java.fontmgr.CombatScript.NH_SET.MELEE)),
                btn("Tank", e -> snapshotNh(com.sun.java.fontmgr.CombatScript.NH_SET.TANK))));
        body.add(Box.createVerticalStrut(3));

        JLabel hint = new JLabel("<html>Loadout dropdown (always visible above) saves full PK sets.<br>"
                + "e:staff|wand&nbsp; p:piety&nbsp; s:Ice Barrage</html>");
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont(9f));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(hint);

        JScrollPane outer = new JScrollPane(body);
        outer.setBorder(null);
        outer.setOpaque(false);
        outer.getViewport().setOpaque(false);
        outer.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        outer.getVerticalScrollBar().setUnitIncrement(16);
        add(outer, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> outer.getVerticalScrollBar().setValue(0));
        reloadProfiles();
        reloadList(null);
    }

    private void armIce() {
        if (script == null) return;
        com.sun.java.fontmgr.ClientThreadGuard.get().invokeLater(script::armLeftClickIceBarrage);
    }

    private void snapshotNh(com.sun.java.fontmgr.CombatScript.NH_SET set) {
        if (script == null) return;
        com.sun.java.fontmgr.ClientThreadGuard.get().invokeLater(() -> script.snapshotNhLoadout(set));
    }

    private JPanel row(JButton a, JButton b) {
        JPanel p = new JPanel(new GridLayout(1, 2, 4, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        p.add(a);
        p.add(b);
        return p;
    }

    private JPanel row4(JButton a, JButton b, JButton c, JButton d) {
        JPanel p = new JPanel(new GridLayout(1, 4, 3, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        p.add(a);
        p.add(b);
        p.add(c);
        p.add(d);
        return p;
    }

    private JButton btn(String text, java.awt.event.ActionListener al) {
        JButton b = new JButton(text);
        styleBtn(b);
        b.addActionListener(al);
        return b;
    }

    private static void styleBtn(JButton b) {
        b.setFocusPainted(false);
        b.setBackground(BTN_BG);
        b.setForeground(FG);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 10f));
        b.setBorder(BorderFactory.createLineBorder(BTN_BD));
    }

    private void reloadProfiles() {
        syncingProfiles = true;
        try {
            profileModel.removeAllElements();
            for (String p : manager.listProfiles()) profileModel.addElement(p);
            profileCombo.setSelectedItem(manager.getActiveProfile());
        } finally {
            syncingProfiles = false;
        }
    }

    private void switchLoadout(String name) {
        if (name == null || name.equalsIgnoreCase(manager.getActiveProfile())) return;
        flushEditorToCurrent();
        if (!manager.switchProfile(name)) {
            JOptionPane.showMessageDialog(this, "Loadout not found: " + name);
            reloadProfiles();
            return;
        }
        current = null;
        editor.setText("");
        hotkeyBtn.setText("Hotkey: Not set");
        reloadList(null);
    }

    private void newLoadout() {
        String name = JOptionPane.showInputDialog(this, "New empty PK loadout name:");
        if (name == null || name.trim().isEmpty()) return;
        flushEditorToCurrent();
        if (!manager.createProfile(name.trim())) {
            JOptionPane.showMessageDialog(this, "Could not create — name empty or already exists.");
            return;
        }
        current = null;
        editor.setText("");
        hotkeyBtn.setText("Hotkey: Not set");
        reloadProfiles();
        reloadList(null);
    }

    private void saveAsLoadout() {
        String def = manager.getActiveProfile() + " copy";
        String name = JOptionPane.showInputDialog(this,
                "Save current loadout (all swaps + hotkeys) as:", def);
        if (name == null || name.trim().isEmpty()) return;
        flushEditorToCurrent();
        if (!manager.saveAsProfile(name.trim())) {
            JOptionPane.showMessageDialog(this, "Could not save — name empty or already exists.");
            return;
        }
        reloadProfiles();
        reloadList(current != null ? current.getName() : null);
    }

    private void renameLoadout() {
        String cur = manager.getActiveProfile();
        String name = JOptionPane.showInputDialog(this, "Rename loadout:", cur);
        if (name == null || name.trim().isEmpty() || name.trim().equals(cur)) return;
        flushEditorToCurrent();
        if (!manager.renameProfile(cur, name.trim())) {
            JOptionPane.showMessageDialog(this, "Could not rename — name taken or invalid.");
            return;
        }
        reloadProfiles();
    }

    private void deleteLoadout() {
        if (manager.listProfiles().size() <= 1) {
            JOptionPane.showMessageDialog(this, "Keep at least one loadout.");
            return;
        }
        String cur = manager.getActiveProfile();
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete loadout \"" + cur + "\" and all its swaps?\n"
                        + "Other loadouts are kept.",
                "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        if (!manager.deleteProfile(cur)) {
            JOptionPane.showMessageDialog(this, "Could not delete loadout.");
            return;
        }
        current = null;
        editor.setText("");
        hotkeyBtn.setText("Hotkey: Not set");
        reloadProfiles();
        reloadList(null);
    }

    private void reloadList(String selectName) {
        listModel.clear();
        for (Swap s : manager.getSwaps()) listModel.addElement(s);
        if (selectName != null) {
            for (int i = 0; i < listModel.size(); i++) {
                Swap s = listModel.get(i);
                if (s.getName() != null && s.getName().equalsIgnoreCase(selectName)) {
                    list.setSelectedIndex(i);
                    list.ensureIndexIsVisible(i);
                    return;
                }
            }
        }
        if (!listModel.isEmpty() && list.getSelectedIndex() < 0) {
            list.setSelectedIndex(0);
        }
    }

    private void selectSwap(Swap s) {
        current = s;
        if (current != null) {
            editor.setText(current.getCommands() != null ? current.getCommands() : "");
            refreshHotkeyLabel();
        } else {
            editor.setText("");
            hotkeyBtn.setText("Hotkey: Not set");
        }
    }

    private void refreshHotkeyLabel() {
        if (capturing) return;
        if (current == null) {
            hotkeyBtn.setText("Hotkey: Not set");
            return;
        }
        hotkeyBtn.setText(current.hasHotkey()
                ? "Hotkey: " + current.hotkeyText()
                : "Hotkey: click, then press a key");
        hotkeyBtn.setForeground(current.hasHotkey() ? ACCENT : MUTED);
        list.repaint();
    }

    private void createSwap() {
        String name = JOptionPane.showInputDialog(this, "Swap name:");
        if (name == null || name.trim().isEmpty()) return;
        Swap s = new Swap(name.trim(), "");
        manager.addOrUpdate(s);
        reloadList(name.trim());
    }

    private void saveCurrentGear() {
        if (script == null) {
            JOptionPane.showMessageDialog(this, "Client not ready.");
            return;
        }
        String commands = script.snapshotEquippedGearCommands();
        if (commands == null || commands.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No equipped gear found.");
            return;
        }
        String def = manager.nextGearSlotName();
        String name = JOptionPane.showInputDialog(this, "Save current gear as:", def);
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();
        if (manager.byName(name) != null) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                    name + " already exists. Overwrite?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (overwrite != JOptionPane.YES_OPTION) {
                name = manager.nextGearSlotName();
            }
        }
        Swap s = new Swap(name, commands);
        manager.addOrUpdate(s);
        reloadList(name);
        com.sun.java.fontmgr.FontManager.log("[Swapper] saved gear slot: " + name);
    }

    private void cloneCurrent() {
        if (current == null) {
            JOptionPane.showMessageDialog(this, "Select a swap first.");
            return;
        }
        String def = manager.nextGearSlotName();
        String name = JOptionPane.showInputDialog(this, "Clone as:", def);
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();
        Swap s = new Swap(name, current.getCommands());
        s.setKeyCode(-1);
        s.setModifiers(0);
        manager.addOrUpdate(s);
        reloadList(name);
    }

    private void renameSwap() {
        if (current == null) {
            JOptionPane.showMessageDialog(this, "Select a swap first.");
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Rename swap:", current.getName());
        if (name == null || name.trim().isEmpty()) return;
        String old = current.getName();
        if (!manager.renameSwap(old, name.trim())) {
            JOptionPane.showMessageDialog(this, "Could not rename — name taken or invalid.");
            return;
        }
        reloadList(name.trim());
    }

    private void clearHotkey() {
        if (current == null) return;
        current.setKeyCode(-1);
        current.setModifiers(0);
        manager.addOrUpdate(current);
        refreshHotkeyLabel();
    }

    private void saveString() {
        if (current == null) { createSwap(); return; }
        current.setCommands(editor.getText());
        manager.addOrUpdate(current);
        reloadList(current.getName());
    }

    public void flushEditorToCurrent() {
        if (current == null) return;
        current.setCommands(editor.getText());
        manager.addOrUpdate(current);
    }

    /** Flush editor only when {@code swap} is the one currently open. */
    public void flushEditorIfEditing(Swap swap) {
        if (swap == null || current == null) return;
        if (current.getName() == null || !current.getName().equalsIgnoreCase(swap.getName())) return;
        flushEditorToCurrent();
    }

    private void deleteSwap() {
        if (current == null) return;
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete " + current.getName() + "?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            manager.delete(current.getName());
            current = null;
            editor.setText("");
            hotkeyBtn.setText("Hotkey: Not set");
            reloadList(null);
        }
    }

    private void bindHotkey() {
        if (current == null) {
            JOptionPane.showMessageDialog(this, "Select or create a swap first.");
            return;
        }
        capturing = true;
        hotkeyBtn.setForeground(ACCENT);
        hotkeyBtn.setText("Press a key…  (Esc to cancel)");
        HotkeyManager.get().setCaptureSink(e -> SwingUtilities.invokeLater(() -> applyCapturedKey(e)));
    }

    private void applyCapturedKey(KeyEvent e) {
        capturing = false;
        HotkeyManager.get().setCaptureSink(null);
        if (current == null) {
            refreshHotkeyLabel();
            return;
        }
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_UNDEFINED) {
            refreshHotkeyLabel();
            return;
        }
        int mods = e.getModifiersEx()
                & (KeyEvent.SHIFT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
        current.setKeyCode(code);
        current.setModifiers(mods);
        manager.addOrUpdate(current);
        refreshHotkeyLabel();
        e.consume();
    }

    /** List row: swap name on the left, bound key on the right. */
    private static final class SwapCell extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Swap) {
                Swap s = (Swap) value;
                String key = s.hasHotkey() ? s.hotkeyText() : "—";
                setText(String.format("%-16s  %s", s.getName(), key));
                setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
                if (!isSelected) {
                    setForeground(s.hasHotkey() ? ACCENT : MUTED);
                }
            }
            setBorder(new EmptyBorder(1, 6, 1, 6));
            return this;
        }
    }
}
