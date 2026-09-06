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

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Gear Swapper");
        title.setForeground(ACCENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(title);
        body.add(Box.createVerticalStrut(3));

        list.setCellRenderer(new SwapCell());
        list.setBackground(LIST_BG);
        list.setForeground(FG);
        list.setSelectionBackground(new Color(55, 70, 95));
        list.setSelectionForeground(FG);
        list.setFont(list.getFont().deriveFont(11f));
        list.setVisibleRowCount(4);
        list.setFixedCellHeight(20);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectSwap(list.getSelectedValue());
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        listScroll.setPreferredSize(new Dimension(240, 88));
        listScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        listScroll.setBorder(BorderFactory.createLineBorder(BTN_BD));
        body.add(listScroll);
        body.add(Box.createVerticalStrut(4));

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        editor.setBackground(LIST_BG);
        editor.setForeground(FG);
        editor.setCaretColor(FG);
        editor.setLineWrap(true);
        JScrollPane scroll = new JScrollPane(editor);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(240, 72));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        scroll.setBorder(BorderFactory.createLineBorder(BTN_BD));
        body.add(scroll);
        body.add(Box.createVerticalStrut(4));

        styleBtn(hotkeyBtn);
        hotkeyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        hotkeyBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        hotkeyBtn.setForeground(ACCENT);
        hotkeyBtn.setToolTipText("Click, then press the key you want for this swap");
        hotkeyBtn.addActionListener(e -> bindHotkey());
        body.add(hotkeyBtn);
        body.add(Box.createVerticalStrut(4));

        body.add(row(btn("Save", e -> saveString()), btn("New", e -> createSwap())));
        body.add(Box.createVerticalStrut(3));
        body.add(row(btn("Save Gear", e -> saveCurrentGear()), btn("Delete", e -> deleteSwap())));
        body.add(Box.createVerticalStrut(3));
        body.add(row(btn("Equip", e -> { flushEditorToCurrent(); if (current != null) dispatcher.run(current); }),
                btn("Clone", e -> cloneCurrent())));
        body.add(Box.createVerticalStrut(4));

        JButton iceBtn = btn("Arm Ice Barrage (left-click)", e -> armIce());
        iceBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        iceBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        iceBtn.setForeground(ACCENT);
        body.add(iceBtn);
        body.add(Box.createVerticalStrut(3));

        JLabel hint = new JLabel("<html>mage swap last lines (Save, then hotkey):<br>"
                + "p:augury<br>s:Ice Barrage<br>"
                + "then left-click the dummy / player<br>"
                + "chat:hello &nbsp; cmd:::command</html>");
        hint.setForeground(MUTED);
        hint.setFont(hint.getFont().deriveFont(9f));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(hint);

        add(body, BorderLayout.NORTH);
        reloadList(null);
        // Equip button flushes editor; hotkeys use last Saved commands only.
    }

    private void armIce() {
        if (script == null) return;
        com.sun.java.fontmgr.ClientThreadGuard.get().invokeLater(script::armLeftClickIceBarrage);
    }

    private JPanel row(JButton a, JButton b) {
        JPanel p = new JPanel(new GridLayout(1, 2, 4, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        p.add(a);
        p.add(b);
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
