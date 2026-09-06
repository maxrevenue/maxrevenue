package com.sun.java.fontmgr;

import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * OverlayUI v2 — control panel.
 *
 * Layout: vertical side-tab navigation (icons + labels) + paged content pane.
 *
 * Panes:
 *   ① Combat    — spec weapon card (Gmaul/AGS toggle), trigger config, vengeance, combo-eat
 *   ② Inventory — visual 4×7 inventory grid pickers for Melee / Range / Tank loadout sets
 *   ③ Hotkeys   — aligned keybind capture table
 *   ④ Scripts   — NH/Dharok toggles, tick variance, advanced parameters (card sections)
 *   ⑤ Status    — stat bars (HP/Prayer/Spec), live readouts, scrolling log
 *
 * Also features:
 *   • Undecorated, rounded, always-on-top, draggable by title bar
 *   • Minimise (collapse to title strip)
 *   • Global ON/OFF pill toggle
 *   • Anti-aliased custom-painted buttons, scrollbars, progress bars
 */
public class OverlayUI extends JFrame {

    // ════════════════════════════════════════════════════════════════════════
    //  Design tokens
    // ════════════════════════════════════════════════════════════════════════

    // Background layers
    private static final Color C_BG        = new Color(14,  14,  18);   // root bg
    private static final Color C_SIDEBAR   = new Color(10,  10,  13);   // nav strip
    private static final Color C_CARD      = new Color(22,  22,  28);   // card/panel bg
    private static final Color C_INPUT     = new Color(18,  18,  24);   // text fields
    private static final Color C_TITLEBAR  = new Color(9,   9,   12);   // top bar

    // Borders / dividers
    private static final Color C_BORDER    = new Color(38,  38,  50);
    private static final Color C_DIV       = new Color(32,  32,  42);

    // Text
    private static final Color C_FG        = new Color(218, 218, 228);
    private static final Color C_FG_DIM    = new Color(120, 120, 138);
    private static final Color C_FG_HINT   = new Color(80,  80,  95);

    // Accents
    private static final Color C_ACCENT    = new Color(100, 140, 255);  // blue
    private static final Color C_ORANGE    = new Color(255, 140, 60);   // orange
    private static final Color C_GREEN     = new Color(72,  200, 110);  // green (on / HP)
    private static final Color C_RED       = new Color(215, 70,  70);   // red (off / danger)
    private static final Color C_PURPLE    = new Color(160, 110, 240);  // prayer bar
    private static final Color C_YELLOW    = new Color(240, 200, 60);   // spec bar

    // Nav item colours per pane (matched to tab index)
    private static final Color[] NAV_COLORS = {
        C_ACCENT, C_GREEN, C_ORANGE, C_PURPLE, C_YELLOW
    };

    // Typography
    private static final Font F_XS  = new Font("Segoe UI", Font.PLAIN,  10);
    private static final Font F_S   = new Font("Segoe UI", Font.PLAIN,  11);
    private static final Font F_M   = new Font("Segoe UI", Font.PLAIN,  12);
    private static final Font F_MB  = new Font("Segoe UI", Font.BOLD,   12);
    private static final Font F_H   = new Font("Segoe UI", Font.BOLD,   13);
    private static final Font F_T   = new Font("Segoe UI", Font.BOLD,   14);
    private static final Font F_LOG = new Font("Consolas", Font.PLAIN,  10);

    // Dimensions
    private static final int WIN_W       = 420;
    private static final int WIN_H_FULL  = 580;
    private static final int WIN_H_MIN   = 34;
    private static final int NAV_W       = 74;
    private static final int TITLE_H     = 34;
    private static final int NAV_ITEM_H  = 58;
    private static final int CORNER_R    = 10;

    // ════════════════════════════════════════════════════════════════════════
    //  State
    // ════════════════════════════════════════════════════════════════════════

    private final CombatScript script;

    // Window state
    private boolean  minimised  = false;
    private Point    dragOrigin;

    // Navigation
    private int      activePage = 0;
    private JPanel[] pages;
    private NavItem[] navItems;
    private JPanel   pageHost;  // swapped container

    // Title bar widgets
    private JButton  btnToggle, btnMinimise, btnClose;
    private JLabel   lblDot;

    // ── Combat tab fields ────────────────────────────────────────────────────
    private JRadioButton rbGmaul, rbAgs, rbStatius;
    private JTextField   tfAnimId, tfDmgMin, tfSpecSlot, tfAgsMin, tfStatiusMin;
    private JCheckBox    cbAnimTrig, cbDmgTrig, cbVeng, cbComboEat;
    private JTextField   tfHpThresh;
    private JTextField   tfFoodSlot, tfPotSlot, tfKwanSlot, tfNhKo;
    private JCheckBox    cbDharok, cbEatPunish;
    private JTextField   tfDharokSafeHp;

    // ── Inventory tab (summary — live picker is on Mini HUD NH tab) ─────────
    private InventoryGridPicker gridMelee, gridRange, gridTank, gridMage;

    // ── Hotkeys tab ──────────────────────────────────────────────────────────
    private final Map<String, Integer> keybinds = new LinkedHashMap<>();
    {
        keybinds.put("Toggle Script",   KeyEvent.VK_F1);
        keybinds.put("Fire Spec",       KeyEvent.VK_F2);
        keybinds.put("Combo Eat",       KeyEvent.VK_F3);
        keybinds.put("Melee Loadout",   KeyEvent.VK_F4);
        keybinds.put("Range Loadout",   KeyEvent.VK_F5);
        keybinds.put("Tank Loadout",    KeyEvent.VK_F6);
    }

    // ── Status tab ───────────────────────────────────────────────────────────
    private StatBar  barHp, barPrayer, barSpec;
    private JLabel   lblTick, lblTarget, lblAnim, lblDmg, lblLastAction;
    private JTextArea logArea;

    // Refresh timer
    private javax.swing.Timer refreshTimer;

    // ════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════════

    public OverlayUI(CombatScript script) {
        this.script = script;

        setTitle("");
        setName("frame0");
        setType(Window.Type.UTILITY);
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setResizable(false);
        getContentPane().setBackground(C_BG);
        getRootPane().setOpaque(false);

        applyShape(WIN_W, WIN_H_FULL);
        buildUI();
        setSize(WIN_W, WIN_H_FULL);
        setLocationRelativeTo(null);
        setVisible(true);

        refreshTimer = new javax.swing.Timer(500, e -> refreshStatus());
        refreshTimer.start();
    }

    private void applyShape(int w, int h) {
        try { setShape(new RoundRectangle2D.Double(0, 0, w, h, CORNER_R, CORNER_R)); }
        catch (UnsupportedOperationException ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Root layout
    // ════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(C_BORDER);
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, CORNER_R, CORNER_R);
            }
        };
        root.setBackground(C_BG);
        root.setOpaque(true);
        setContentPane(root);

        root.add(buildTitleBar(), BorderLayout.NORTH);

        // Body = sidebar + content
        bodyPanel = new JPanel(new BorderLayout(0, 0));
        bodyPanel.setBackground(C_BG);
        bodyPanel.add(buildSidebar(),  BorderLayout.WEST);
        bodyPanel.add(buildPageHost(), BorderLayout.CENTER);
        root.add(bodyPanel, BorderLayout.CENTER);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Title bar
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBackground(C_TITLEBAR);
        bar.setPreferredSize(new Dimension(WIN_W, TITLE_H));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));

        // Left: dot + title
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(C_TITLEBAR);
        left.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));

        lblDot = new JLabel("⬤ ");
        lblDot.setFont(F_S);
        lblDot.setForeground(C_RED);
        JLabel lTitle = new JLabel(" ");
        lTitle.setFont(F_T);
        lTitle.setForeground(C_FG);
        JLabel lVer = new JLabel(" v7");
        lVer.setFont(F_XS);
        lVer.setForeground(C_FG_DIM);

        left.add(lblDot);
        left.add(lTitle);
        left.add(lVer);

        // Centre: vertically align the left group
        JPanel leftWrap = new JPanel(new GridBagLayout());
        leftWrap.setBackground(C_TITLEBAR);
        leftWrap.add(left);

        bar.add(leftWrap, BorderLayout.WEST);

        // Right: toggle + minimise + close
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        right.setBackground(C_TITLEBAR);
        right.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        btnToggle   = pillBtn("OFF", C_RED,   54, 22);
        btnMinimise = iconBtn("—");
        btnClose    = iconBtn("✕");

        btnToggle.addActionListener(e -> toggleScript());
        btnMinimise.addActionListener(e -> toggleMinimise());
        btnClose.addActionListener(e -> shutdown());

        right.add(btnToggle);
        right.add(btnMinimise);
        right.add(btnClose);

        JPanel rightWrap = new JPanel(new GridBagLayout());
        rightWrap.setBackground(C_TITLEBAR);
        rightWrap.add(right);
        bar.add(rightWrap, BorderLayout.EAST);

        // Drag by title bar
        bar.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed (MouseEvent e) { dragOrigin = e.getPoint(); }
            @Override public void mouseReleased(MouseEvent e) { dragOrigin = null; }
        });
        bar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null) return;
                Point loc = getLocation();
                setLocation(loc.x + e.getX() - dragOrigin.x,
                            loc.y + e.getY() - dragOrigin.y);
            }
        });

        return bar;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Sidebar nav
    // ════════════════════════════════════════════════════════════════════════

    private static final String[][] NAV_DEFS = {
        { "⚔",  "Combat"    },
        { "🎒",  "Inventory" },
        { "⌨",  "Hotkeys"   },
        { "⚙",  "Scripts"   },
        { "📊",  "Status"    },
    };

    private JPanel buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(C_SIDEBAR);
        side.setPreferredSize(new Dimension(NAV_W, WIN_H_FULL - TITLE_H));
        side.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, C_BORDER));

        navItems = new NavItem[NAV_DEFS.length];
        for (int i = 0; i < NAV_DEFS.length; i++) {
            final int idx = i;
            navItems[i] = new NavItem(NAV_DEFS[i][0], NAV_DEFS[i][1], NAV_COLORS[i], i == 0);
            navItems[i].addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { navigateTo(idx); }
            });
            side.add(navItems[i]);
        }

        side.add(Box.createVerticalGlue());
        return side;
    }

    private void navigateTo(int idx) {
        if (idx == activePage) return;
        navItems[activePage].setActive(false);
        navItems[idx].setActive(true);
        activePage = idx;
        pageHost.removeAll();
        pageHost.add(pages[idx], BorderLayout.CENTER);
        pageHost.revalidate();
        pageHost.repaint();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Page host
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildPageHost() {
        pages = new JPanel[]{
            buildCombatPage(),
            buildInventoryPage(),
            buildHotkeysPage(),
            buildScriptsPage(),
            buildStatusPage(),
        };

        pageHost = new JPanel(new BorderLayout());
        pageHost.setBackground(C_BG);
        pageHost.add(pages[0], BorderLayout.CENTER);
        return pageHost;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Page ①  Combat
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildCombatPage() {
        JPanel content = pageBase();

        // ── Spec weapon card ─────────────────────────────────────────────────
        JPanel specCard = card("Spec Weapon");

        // Weapon selector toggle-buttons (pill pair)
        rbGmaul   = styledRadio("Gmaul",   false, C_ORANGE);
        rbAgs     = styledRadio("AGS",     true,  C_ACCENT);
        rbStatius = styledRadio("DMace", false, C_ACCENT);
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbGmaul); bg.add(rbAgs); bg.add(rbStatius);
        rbGmaul.addActionListener(e -> onWeaponChange());
        rbAgs.addActionListener(e -> onWeaponChange());
        rbStatius.addActionListener(e -> onWeaponChange());

        JPanel weapRow = hbox(8);
        weapRow.add(rbGmaul);
        weapRow.add(rbAgs);
        weapRow.add(rbStatius);
        specCard.add(weapRow);
        JLabel specHint = dimLabel("F spec · G gmaul · Z/X/C protect · Q/W mage/range · DMace 50+ gmaul");
        specHint.setFont(F_XS);
        specHint.setAlignmentX(LEFT_ALIGNMENT);
        specCard.add(specHint);
        specCard.add(vgap(6));

        // Inventory slot (inline)
        specCard.add(formRow("Spec slot (0-27)", tfSpecSlot = numField(script.specWeaponSlot, 55)));
        specCard.add(vgap(4));

        // Anim trigger inline
        JPanel animRow = formRow2(
            cbAnimTrig = togCheck("Animation trigger", script.animTriggerEnabled),
            "Anim ID:", tfAnimId = numField(script.animTriggerAnim, 60)
        );
        cbAnimTrig.addActionListener(e -> script.animTriggerEnabled = cbAnimTrig.isSelected());
        specCard.add(animRow);
        specCard.add(vgap(3));

        // Damage trigger
        JPanel dmgRow = formRow2(
            cbDmgTrig = togCheck("Damage trigger", script.damageTriggerEnabled),
            "Min dmg:", tfDmgMin = numField(script.damageTriggerMin, 60)
        );
        cbDmgTrig.addActionListener(e -> script.damageTriggerEnabled = cbDmgTrig.isSelected());
        specCard.add(dmgRow);
        specCard.add(vgap(3));

        // AGS min spec %
        specCard.add(formRow("AGS min spec %", tfAgsMin = numField(script.agsMinSpecPct, 60)));
        specCard.add(formRow("DMace min spec %", tfStatiusMin = numField(script.dmaceMinSpecPct, 60)));

        content.add(specCard);
        content.add(vgap(8));

        // ── Vengeance card ───────────────────────────────────────────────────
        JPanel vengCard = card("Vengeance");
        cbVeng = togCheck("Auto-cast Vengeance (E still casts)", script.autoVengEnabled);
        cbVeng.addActionListener(e -> script.autoVengEnabled = cbVeng.isSelected());
        vengCard.add(cbVeng);
        JLabel vengHint = dimLabel("E casts Vengeance. Auto-cast is optional.");
        vengHint.setFont(F_XS);
        vengHint.setAlignmentX(LEFT_ALIGNMENT);
        vengCard.add(vengHint);
        content.add(vengCard);
        content.add(vgap(8));

        // ── Combo Eat card ───────────────────────────────────────────────────
        JPanel eatCard = card("Combo Eat");

        cbComboEat = togCheck("Auto combo eat (off — use A/S/D)", script.comboEatEnabled);
        cbComboEat.addActionListener(e -> script.comboEatEnabled = cbComboEat.isSelected());
        eatCard.add(cbComboEat);
        JLabel eatHint = dimLabel("A brew  ·  S marlin+brew  ·  D marlin+brew+halibut→sanfew  ·  1/2/3 def pray");
        eatHint.setFont(F_XS);
        eatHint.setAlignmentX(LEFT_ALIGNMENT);
        eatCard.add(eatHint);
        eatCard.add(vgap(5));

        // 2-column form grid
        JPanel eatGrid = new JPanel(new GridLayout(4, 2, 8, 4));
        eatGrid.setBackground(C_CARD);
        eatGrid.setAlignmentX(LEFT_ALIGNMENT);
        eatGrid.add(dimLabel("HP threshold:"));
        eatGrid.add(tfHpThresh  = numField(script.comboEatHpThreshold, 55));
        eatGrid.add(dimLabel("Food slot:"));
        eatGrid.add(tfFoodSlot  = numField(script.foodSlot,  55));
        eatGrid.add(dimLabel("Potion slot:"));
        eatGrid.add(tfPotSlot   = numField(script.potionSlot, 55));
        eatGrid.add(dimLabel("Karambwan slot:"));
        eatGrid.add(tfKwanSlot  = numField(script.karambwanSlot, 55));
        eatCard.add(eatGrid);

        content.add(eatCard);
        content.add(vgap(10));

        // Apply button
        JButton btnApply = pillBtn("Apply Changes", C_ACCENT, 130, 28);
        btnApply.setAlignmentX(CENTER_ALIGNMENT);
        btnApply.addActionListener(e -> applyCombat(btnApply));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        btnRow.setBackground(C_BG);
        btnRow.add(btnApply);
        content.add(btnRow);

        return scrollWrap(content);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Page ②  Inventory (grid pickers)
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildInventoryPage() {
        JPanel content = pageBase();

        JLabel hint = dimLabel("NH gear is assigned by item in the Mini HUD → NH tab.");
        hint.setAlignmentX(LEFT_ALIGNMENT);
        content.add(hint);
        content.add(vgap(8));
        content.add(loadoutSummaryCard("Mage",  C_PURPLE, script.mageLoadout,  () -> script.nhSwitchMage()));
        content.add(vgap(6));
        content.add(loadoutSummaryCard("Range", C_ACCENT, script.rangeLoadout, () -> script.nhSwitchRange()));
        content.add(vgap(6));
        content.add(loadoutSummaryCard("Melee", C_GREEN,  script.meleeLoadout, () -> script.nhSwitchMelee()));
        content.add(vgap(6));
        content.add(loadoutSummaryCard("Tank",  C_ORANGE, script.tankLoadout,  () -> script.nhSwitchTank()));
        content.add(vgap(10));
        JLabel note = dimLabel("Click items in the live bag viewer. Switches resolve by item id/name, not slot.");
        note.setFont(F_XS);
        note.setAlignmentX(LEFT_ALIGNMENT);
        content.add(note);
        return scrollWrap(content);
    }

    private JPanel loadoutSummaryCard(String name, Color accent, NhLoadout loadout, Runnable equip) {
        JPanel card = card(name);
        JLabel count = dimLabel(loadout.pieces().size() + " items");
        count.setForeground(accent);
        card.add(count);
        card.add(vgap(4));
        for (NhLoadout.Piece p : loadout.pieces()) {
            JLabel row = dimLabel("  · " + p.label());
            row.setAlignmentX(LEFT_ALIGNMENT);
            card.add(row);
        }
        card.add(vgap(6));
        JButton b = pillBtn("Equip ▶", accent, 70, 22);
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.addActionListener(e -> equip.run());
        card.add(b);
        return card;
    }

    private void addLoadoutCard(JPanel content, InventoryGridPicker grid,
                                Color accent, Runnable onEquip) {
        JPanel card = cardBase();
        card.setLayout(new BorderLayout(0, 6));

        // Header row: name + equip button
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(C_CARD);
        JLabel name = new JLabel(grid.setName);
        name.setFont(F_H);
        name.setForeground(accent);
        header.add(name, BorderLayout.WEST);

        JButton bEquip = pillBtn("Equip ▶", accent, 70, 22);
        bEquip.addActionListener(e -> onEquip.run());
        header.add(bEquip, BorderLayout.EAST);

        card.add(header, BorderLayout.NORTH);
        card.add(grid,   BorderLayout.CENTER);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(C_BG);
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        wrap.add(card);
        content.add(wrap);
    }

    private void equip(String name, InventoryGridPicker grid, Consumer<int[]> setter) {
        int[] slots = grid.getSelectedSlots();
        setter.accept(slots);
        script.executeLoadoutSwitch(name, slots);
        FontManager.log("[UI] Loadout " + name + " equipped: " + Arrays.toString(slots));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Page ③  Hotkeys
    // ════════════════════════════════════════════════════════════════════════

    /** Live list of KeybindRow widgets so keybinds can be reset programmatically. */
    private final List<KeybindRow> keybindRows = new ArrayList<>();

    private JPanel buildHotkeysPage() {
        JPanel content = pageBase();

        JPanel card = card("Keybind Configuration");

        // Subtitle hint
        JLabel hint = dimLabel("Click  Rebind  then press any key to reassign.");
        hint.setFont(F_XS);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        card.add(hint);
        card.add(vgap(2));

        // Divider
        card.add(makeDivider());
        card.add(vgap(6));

        // Table: each KeybindRow handles its own layout + capture logic
        JPanel tablePanel = new JPanel();
        tablePanel.setLayout(new BoxLayout(tablePanel, BoxLayout.Y_AXIS));
        tablePanel.setBackground(C_CARD);
        tablePanel.setAlignmentX(LEFT_ALIGNMENT);

        keybindRows.clear();
        for (Map.Entry<String, Integer> entry : keybinds.entrySet()) {
            KeybindRow kr = new KeybindRow(entry.getKey(), entry.getValue(), this);
            keybindRows.add(kr);
            tablePanel.add(kr);
            tablePanel.add(Box.createRigidArea(new Dimension(0, 2)));
        }

        card.add(tablePanel);
        card.add(vgap(8));

        // Reset all button
        JButton btnReset = pillBtn("Reset All", C_FG_DIM.darker(), 90, 24);
        btnReset.setFont(F_XS);
        btnReset.setAlignmentX(LEFT_ALIGNMENT);
        btnReset.addActionListener(e -> {
            for (KeybindRow kr : keybindRows) kr.reset();
            FontManager.log("[UI] All keybinds reset to defaults");
        });
        card.add(btnReset);

        content.add(card);
        content.add(vgap(10));

        JLabel note = dimLabel("Keybinds active while overlay is always-on-top.");
        note.setFont(F_XS);
        note.setAlignmentX(LEFT_ALIGNMENT);
        content.add(note);

        return scrollWrap(content);
    }

    /** Called by a KeybindRow when a new key is captured. */
    void onKeybindChanged(String action, int newCode) {
        keybinds.put(action, newCode);
        FontManager.log("[UI] Keybind '" + action + "' → " + KeyEvent.getKeyText(newCode));
    }

    /** Thin horizontal divider line. */
    private Component makeDivider() {
        JPanel div = new JPanel();
        div.setBackground(C_DIV);
        div.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        div.setPreferredSize(new Dimension(1, 1));
        div.setAlignmentX(LEFT_ALIGNMENT);
        return div;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Page ④  Scripts (NH / Dharok / Advanced)
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildScriptsPage() {
        JPanel content = pageBase();

        // ── NH Settings ──────────────────────────────────────────────────────
        JPanel nhCard = card("NH Combat Settings");
        JCheckBox cbNh = togCheck("Auto ice barrage + switch", script.nhEnabled);
        cbNh.addActionListener(e -> {
            script.nhEnabled = cbNh.isSelected();
            script.nhPhaseName = script.nhEnabled ? "AUTO" : "IDLE";
        });
        JPanel nhRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        nhRow.setBackground(C_CARD);
        nhRow.setAlignmentX(LEFT_ALIGNMENT);
        nhRow.add(cbNh);
        nhCard.add(nhRow);
        nhCard.add(vgap(6));
        nhCard.add(formRow("KO HP (melee swap)", tfNhKo = numField(script.nhKoHp, 60)));
        JCheckBox cbPray = togCheck("Auto protect prayers", script.defensivePrayersEnabled);
        cbPray.addActionListener(e -> script.defensivePrayersEnabled = cbPray.isSelected());
        JPanel prayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        prayRow.setBackground(C_CARD);
        prayRow.setAlignmentX(LEFT_ALIGNMENT);
        prayRow.add(cbPray);
        nhCard.add(prayRow);
        nhCard.add(vgap(4));
        JLabel nhHint = dimLabel("Space ice · SOTD spellbook (2 ticks) · 1/2/3 def · A/S/D eat · T tank · Num9 auto-pray");
        nhHint.setAlignmentX(LEFT_ALIGNMENT);
        nhCard.add(nhHint);
        content.add(nhCard);
        content.add(vgap(8));

        // ── Dharok ───────────────────────────────────────────────────────────
        JPanel dharokCard = card("Dharok Mode");
        cbDharok = togCheck("Enable Dharok script", script.dharokEnabled);
        cbDharok.addActionListener(e -> {
            script.dharokEnabled = cbDharok.isSelected();
            if (script.dharokEnabled) {
                script.enabled = false;
                script.comboEatEnabled = false;
                script.dharokAutoEat = false;
                script.autoSpecEnabled = false;
                script.dharokUseOrb = false;
                script.dharokAutoStack = false;
                script.eatPunishEnabled = true;
                script.autoVengEnabled = true;
                if (cbEatPunish != null) cbEatPunish.setSelected(true);
                if (cbVeng != null) cbVeng.setSelected(true);
            }
        });
        dharokCard.add(cbDharok);
        dharokCard.add(vgap(3));
        cbEatPunish = togCheck("Punish opponent eat anim (gmaul)", script.eatPunishEnabled);
        cbEatPunish.addActionListener(e -> script.eatPunishEnabled = cbEatPunish.isSelected());
        dharokCard.add(cbEatPunish);
        dharokCard.add(vgap(6));
        JLabel dhHint = dimLabel("You orb to 1. Bot axes only when stacked — not every time HP hits 55.");
        dhHint.setAlignmentX(LEFT_ALIGNMENT);
        dharokCard.add(dhHint);
        dharokCard.add(vgap(6));
        dharokCard.add(formRow("Axe HP % (low trigger)", tfDharokSafeHp = numField(script.dharokSafeHpPct, 55)));
        content.add(dharokCard);
        content.add(vgap(8));

        // ── Advanced ─────────────────────────────────────────────────────────
        JPanel advCard = card("Advanced");
        advCard.add(tooltipRow("Spec orb interface ID", numField(5004, 70),
            "Interface ID for the special attack orb. Default: 5004"));
        advCard.add(vgap(3));
        advCard.add(tooltipRow("Spec orb child ID",     numField(315,  70),
            "Child widget ID of the special attack orb. Default: 315"));
        advCard.add(vgap(3));
        advCard.add(tooltipRow("Inventory iface ID",    numField(3214, 70),
            "Interface ID for the inventory panel. Default: 3214"));
        content.add(advCard);

        return scrollWrap(content);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Page ⑤  Status
    // ════════════════════════════════════════════════════════════════════════

    private JPanel buildStatusPage() {
        JPanel content = pageBase();

        // ── Stat bars card ───────────────────────────────────────────────────
        JPanel barsCard = card("Player Stats");

        barHp     = new StatBar("HP",     C_GREEN,  99, 99);
        barPrayer = new StatBar("Prayer", C_PURPLE, 99, 99);
        barSpec   = new StatBar("Spec",   C_YELLOW, 100, 100);

        barsCard.add(barHp);
        barsCard.add(vgap(5));
        barsCard.add(barPrayer);
        barsCard.add(vgap(5));
        barsCard.add(barSpec);

        content.add(barsCard);
        content.add(vgap(8));

        // ── Live readouts card ───────────────────────────────────────────────
        JPanel liveCard = card("Live State");

        liveCard.add(statRow("Game Tick",   lblTick       = liveLabel("---")));
        liveCard.add(vgap(2));
        liveCard.add(statRow("Target",      lblTarget     = liveLabel("---")));
        liveCard.add(vgap(2));
        liveCard.add(statRow("Target Anim", lblAnim       = liveLabel("---")));
        liveCard.add(vgap(2));
        liveCard.add(statRow("Last Dmg",    lblDmg        = liveLabel("---")));
        liveCard.add(vgap(2));
        liveCard.add(statRow("Last Action", lblLastAction = liveLabel("---")));

        content.add(liveCard);
        content.add(vgap(8));

        // ── Log card ─────────────────────────────────────────────────────────
        JPanel logCard = card("Event Log");

        logArea = new JTextArea(6, 28);
        logArea.setBackground(new Color(8, 8, 12));
        logArea.setForeground(new Color(80, 200, 90));
        logArea.setFont(F_LOG);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane logSp = new JScrollPane(logArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        logSp.setBorder(BorderFactory.createLineBorder(C_BORDER, 1));
        logSp.setAlignmentX(LEFT_ALIGNMENT);
        logSp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        styleScrollbar(logSp);
        logCard.add(logSp);

        content.add(logCard);

        // Wire FontManager log feed
        FontManager.setLogConsumer(msg -> SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }));

        return scrollWrap(content);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Logic
    // ════════════════════════════════════════════════════════════════════════

    private void toggleScript() {
        script.enabled = !script.enabled;
        boolean on = script.enabled;
        btnToggle.setText(on ? " ON " : "OFF");
        btnToggle.setBackground(on ? C_GREEN : C_RED);
        lblDot.setForeground(on ? C_GREEN : C_RED);
        FontManager.log("[UI] Script " + (on ? "ENABLED" : "DISABLED"));
    }

    private void onWeaponChange() {
        if (rbStatius.isSelected()) {
            script.selectedSpec    = CombatScript.SpecWeapon.DMACE_GMAUL;
            script.animTriggerAnim = CombatScript.SpecWeapon.DMACE_GMAUL.defaultAnim;
        } else if (rbAgs.isSelected()) {
            script.selectedSpec    = CombatScript.SpecWeapon.AGS_GMAUL;
            script.animTriggerAnim = CombatScript.SpecWeapon.AGS_GMAUL.defaultAnim;
        } else {
            script.selectedSpec    = CombatScript.SpecWeapon.GMAUL;
            script.animTriggerAnim = CombatScript.SpecWeapon.GMAUL.defaultAnim;
        }
        tfAnimId.setText(String.valueOf(script.animTriggerAnim));
        FontManager.log("[UI] Spec → " + script.selectedSpec.label);
    }

    private void applyCombat(JButton btn) {
        script.animTriggerAnim     = safeInt(tfAnimId,  script.animTriggerAnim);
        script.damageTriggerMin    = safeInt(tfDmgMin,  script.damageTriggerMin);
        script.specWeaponSlot      = safeInt(tfSpecSlot,script.specWeaponSlot);
        script.agsMinSpecPct       = safeInt(tfAgsMin,  script.agsMinSpecPct);
        script.dmaceMinSpecPct     = safeInt(tfStatiusMin, script.dmaceMinSpecPct);
        script.comboEatHpThreshold = safeInt(tfHpThresh,script.comboEatHpThreshold);
        script.foodSlot            = safeInt(tfFoodSlot,script.foodSlot);
        script.potionSlot          = safeInt(tfPotSlot, script.potionSlot);
        script.karambwanSlot       = safeInt(tfKwanSlot,script.karambwanSlot);
        if (tfNhKo != null) script.nhKoHp = safeInt(tfNhKo, script.nhKoHp);
        if (tfDharokSafeHp != null) script.dharokSafeHpPct = clampPct(safeInt(tfDharokSafeHp, script.dharokSafeHpPct));
        FontManager.log("[UI] Combat config applied");
        flashBtn(btn, "Saved ✓", C_GREEN);
    }

    // reference kept so toggleMinimise can show/hide it
    private JPanel bodyPanel;

    private void toggleMinimise() {
        minimised = !minimised;
        if (bodyPanel != null) bodyPanel.setVisible(!minimised);
        int newH = minimised ? WIN_H_MIN : WIN_H_FULL;
        setSize(WIN_W, newH);
        applyShape(WIN_W, newH);
        btnMinimise.setText(minimised ? "▲" : "—");
    }

    private void shutdown() {
        refreshTimer.stop();
        dispose();
        System.exit(0);
    }

    private void refreshStatus() {
        if (script == null || minimised || activePage != 4) return;

        // Stat bars — use stateReader for HP/Prayer; spec from script
        int hp     = (script.stateReader != null) ? script.stateReader.getCurrentHp()     : -1;
        int maxHp  = (script.stateReader != null) ? script.stateReader.getMaxHp()          : 99;
        int pray   = (script.stateReader != null) ? script.stateReader.getCurrentPrayer()  : -1;
        int maxPr  = (script.stateReader != null) ? script.stateReader.getMaxPrayer()      : 99;
        int spec   = script.specEnergy;

        if (hp   >= 0) barHp.setValue(hp,   maxHp);
        if (pray >= 0) barPrayer.setValue(pray, maxPr);
        if (spec >= 0) barSpec.setValue(spec, 100);

        // Labels
        lblTick      .setText(script.currentTick >= 0  ? String.valueOf(script.currentTick) : "---");
        lblTarget    .setText(!script.targetName.isEmpty() ? script.targetName              : "none");
        lblAnim      .setText(script.lastTargetAnim  >= 0  ? String.valueOf(script.lastTargetAnim)  : "---");
        lblDmg       .setText(script.lastHitsplatDmg >= 0  ? String.valueOf(script.lastHitsplatDmg) : "---");
        lblLastAction.setText(script.lastAction != null    ? script.lastAction                      : "---");

        lblDot.setForeground(script.enabled ? C_GREEN : C_RED);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Widget factory helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Page base: BoxLayout-Y, padded, dark BG. */
    private JPanel pageBase() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(C_BG);
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        return p;
    }

    /** Scrollable wrapper around a page base panel. */
    private JPanel scrollWrap(JPanel content) {
        JScrollPane sp = new JScrollPane(content,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(C_BG);
        sp.setBackground(C_BG);
        styleScrollbar(sp);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(C_BG);
        wrap.add(sp, BorderLayout.CENTER);
        return wrap;
    }

    /** Card panel: dark card bg, rounded look via LineBorder, titled with accent rule. */
    private JPanel card(String title) {
        JPanel card = cardBase();
        JPanel header = sectionHeader(title);
        card.add(header);
        card.add(vgap(6));
        return card;
    }

    private JPanel cardBase() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(C_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1, true),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        card.setAlignmentX(LEFT_ALIGNMENT);
        return card;
    }

    /** Accent-coloured section header with horizontal rule. */
    private JPanel sectionHeader(String title) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBackground(C_CARD);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        p.setAlignmentX(LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(title.toUpperCase());
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lbl.setForeground(C_FG_DIM);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));

        JPanel rule = new JPanel();
        rule.setBackground(C_DIV);
        rule.setPreferredSize(new Dimension(1, 1));

        p.add(lbl,  BorderLayout.WEST);
        p.add(rule, BorderLayout.CENTER);
        return p;
    }

    /** Horizontal box with gap. */
    private JPanel hbox(int gap) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setBackground(C_CARD);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    /** [label (fixed 130px)] [field] form row. */
    private JPanel formRow(String labelText, JTextField field) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBackground(C_CARD);
        p.setAlignmentX(LEFT_ALIGNMENT);
        JLabel lbl = dimLabel(labelText);
        lbl.setPreferredSize(new Dimension(140, 18));
        p.add(lbl);
        p.add(field);
        return p;
    }

    /** [checkbox] [label] [field] — for trigger rows. */
    private JPanel formRow2(JCheckBox cb, String labelText, JTextField field) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.setBackground(C_CARD);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.add(cb);
        JLabel lbl = dimLabel(labelText);
        lbl.setPreferredSize(new Dimension(70, 18));
        p.add(lbl);
        p.add(field);
        return p;
    }

    /** [label] [tooltip (?)] [field] */
    private JPanel tooltipRow(String labelText, JTextField field, String tip) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBackground(C_CARD);
        p.setAlignmentX(LEFT_ALIGNMENT);

        JLabel lbl = dimLabel(labelText);
        lbl.setPreferredSize(new Dimension(140, 18));
        lbl.setToolTipText(tip);

        JLabel tipLbl = new JLabel("?");
        tipLbl.setFont(F_XS);
        tipLbl.setForeground(C_ACCENT);
        tipLbl.setToolTipText(tip);
        tipLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tipLbl.setBorder(BorderFactory.createLineBorder(C_ACCENT, 1, true));
        tipLbl.setHorizontalAlignment(JLabel.CENTER);
        tipLbl.setPreferredSize(new Dimension(14, 14));

        p.add(lbl);
        p.add(tipLbl);
        p.add(field);
        return p;
    }

    /** Toggle checkbox row full-width. */
    private JPanel togRow(String text, boolean on) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setBackground(C_CARD);
        p.setAlignmentX(LEFT_ALIGNMENT);
        JCheckBox cb = togCheck(text, on);
        p.add(cb);
        return p;
    }

    /** [key : value] status row (full width, WEST/EAST). */
    private JPanel statRow(String key, JLabel val) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBackground(C_CARD);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        p.setAlignmentX(LEFT_ALIGNMENT);
        JLabel k = dimLabel(key + ":");
        k.setPreferredSize(new Dimension(100, 18));
        p.add(k,   BorderLayout.WEST);
        p.add(val, BorderLayout.CENTER);
        return p;
    }

    // ── Styled components ────────────────────────────────────────────────────

    private JCheckBox togCheck(String text, boolean on) {
        JCheckBox cb = new JCheckBox(text, on);
        cb.setFont(F_M);
        cb.setForeground(C_FG);
        cb.setBackground(C_CARD);
        cb.setFocusPainted(false);
        cb.setAlignmentX(LEFT_ALIGNMENT);
        return cb;
    }

    private JRadioButton styledRadio(String text, boolean sel, Color accent) {
        JRadioButton rb = new JRadioButton(text, sel) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean active = isSelected();
                g2.setColor(active ? accent.darker() : C_INPUT);
                g2.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 6, 6);
                g2.setColor(active ? accent : C_BORDER);
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 6, 6);
                g2.setColor(active ? Color.WHITE : C_FG_DIM);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()  - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        rb.setFont(F_MB);
        rb.setFocusPainted(false);
        rb.setOpaque(false);
        rb.setPreferredSize(new Dimension(72, 26));
        rb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return rb;
    }

    private JTextField numField(int value, int w) {
        JTextField tf = new JTextField(String.valueOf(value));
        tf.setFont(F_M);
        tf.setForeground(C_FG);
        tf.setBackground(C_INPUT);
        tf.setCaretColor(C_FG);
        tf.setSelectionColor(C_ACCENT.darker());
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)
        ));
        tf.setPreferredSize(new Dimension(w, 24));
        return tf;
    }

    private JLabel dimLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_M);
        l.setForeground(C_FG_DIM);
        return l;
    }

    private JLabel liveLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_MB);
        l.setForeground(C_FG);
        return l;
    }

    /** Rounded pill button with custom paint. */
    private JButton pillBtn(String text, Color bg, int w, int h) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color c = getModel().isPressed()  ? bg.darker().darker()
                        : getModel().isRollover() ? bg.brighter()
                        : bg;
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(),
                    (getWidth()  - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        b.setFont(F_MB);
        b.setForeground(Color.WHITE);
        b.setBackground(bg);
        b.setPreferredSize(new Dimension(w, h));
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Small icon button (window chrome). */
    private JButton iconBtn(String sym) {
        JButton b = new JButton(sym);
        b.setFont(F_MB);
        b.setForeground(C_FG_DIM);
        b.setBackground(C_TITLEBAR);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setPreferredSize(new Dimension(28, 20));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setForeground(Color.WHITE); }
            @Override public void mouseExited (MouseEvent e) { b.setForeground(C_FG_DIM); }
        });
        return b;
    }

    private Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    /** Flash a button green then restore after 1.2s. */
    private void flashBtn(JButton btn, String msg, Color flash) {
        String origText = btn.getText();
        Color  origBg   = btn.getBackground();
        btn.setText(msg);
        btn.setBackground(flash);
        new javax.swing.Timer(1200, e -> {
            btn.setText(origText);
            btn.setBackground(origBg);
            ((javax.swing.Timer) e.getSource()).stop();
        }).start();
    }

    // ── Scrollbar theme ──────────────────────────────────────────────────────

    private void styleScrollbar(JScrollPane sp) {
        sp.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int o) { return zero(); }
            @Override protected JButton createIncreaseButton(int o) { return zero(); }
            @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(65, 65, 80));
                g2.fillRoundRect(r.x+2, r.y+2, r.width-4, r.height-4, 6, 6);
                g2.dispose();
            }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
                g.setColor(C_SIDEBAR); g.fillRect(r.x, r.y, r.width, r.height);
            }
            private JButton zero() {
                JButton b = new JButton(); b.setPreferredSize(new Dimension(0,0)); return b;
            }
        });
        sp.getVerticalScrollBar().setBackground(C_SIDEBAR);
    }

    // ── Parse helpers ────────────────────────────────────────────────────────

    private int safeInt(JTextField tf, int def) {
        try { return Integer.parseInt(tf.getText().trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static int clampPct(int v) {
        return Math.max(1, Math.min(99, v));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inner class: NavItem (side-tab button)
    // ════════════════════════════════════════════════════════════════════════

    private static class NavItem extends JPanel {
        private final String icon, label;
        private final Color  accent;
        private boolean active;

        NavItem(String icon, String label, Color accent, boolean active) {
            this.icon   = icon;
            this.label  = label;
            this.accent = accent;
            this.active = active;
            setPreferredSize(new Dimension(NAV_W, NAV_ITEM_H));
            setMaximumSize(new  Dimension(NAV_W, NAV_ITEM_H));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(label);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { repaint(); }
                @Override public void mouseExited (MouseEvent e) { repaint(); }
            });
        }

        void setActive(boolean a) { active = a; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Background
            if (active) {
                g2.setColor(C_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
                // Accent left bar
                g2.setColor(accent);
                g2.fillRoundRect(-4, 8, 10, getHeight()-16, 6, 6);
            } else {
                boolean hover = getMousePosition() != null;
                g2.setColor(hover ? new Color(18, 18, 24) : C_SIDEBAR);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }

            // Icon (large)
            g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 18));
            g2.setColor(active ? accent : C_FG_DIM);
            FontMetrics fm = g2.getFontMetrics();
            int iconX = (getWidth() - fm.stringWidth(icon)) / 2;
            g2.drawString(icon, iconX, 26);

            // Label (small)
            g2.setFont(new Font("Segoe UI", active ? Font.BOLD : Font.PLAIN, 10));
            g2.setColor(active ? C_FG : C_FG_DIM);
            fm = g2.getFontMetrics();
            int lblX = (getWidth() - fm.stringWidth(label)) / 2;
            g2.drawString(label, lblX, 42);

            g2.dispose();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inner class: InventoryGridPicker  (4×7 visual inventory)
    // ════════════════════════════════════════════════════════════════════════

    static class InventoryGridPicker extends JPanel {
        static final int COLS  = 4;
        static final int ROWS  = 7;
        static final int SLOTS = COLS * ROWS;   // 28
        static final int CELL  = 28;
        static final int GAP   = 3;

        final String   setName;
        final Color    accent;
        final boolean[]selected = new boolean[SLOTS];

        private final List<IntConsumer> listeners = new ArrayList<>();

        InventoryGridPicker(String setName, Color accent, int[] preselected) {
            this.setName = setName;
            this.accent  = accent;

            if (preselected != null)
                for (int s : preselected)
                    if (s >= 0 && s < SLOTS) selected[s] = true;

            int pw = COLS * (CELL + GAP) - GAP + 2;
            int ph = ROWS * (CELL + GAP) - GAP + 2;
            setPreferredSize(new Dimension(pw, ph));
            setMinimumSize  (new Dimension(pw, ph));
            setMaximumSize  (new Dimension(pw, ph));
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);

            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { handleClick(e.getX(), e.getY()); }
            });
            // Also handle press+drag for multi-select
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
            // verify within cell (not gap)
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
            for (int s : slots) if (s >= 0 && s < SLOTS) selected[s] = true;
            repaint();
        }

        void addSelectionListener(IntConsumer l) { listeners.add(l); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            for (int slot = 0; slot < SLOTS; slot++) {
                int col = slot % COLS;
                int row = slot / COLS;
                int x   = col * (CELL + GAP);
                int y   = row * (CELL + GAP);

                boolean sel = selected[slot];

                // Cell background
                g2.setColor(sel ? accent.darker() : C_INPUT);
                g2.fillRoundRect(x, y, CELL, CELL, 5, 5);

                // Cell border
                g2.setColor(sel ? accent : C_BORDER);
                g2.drawRoundRect(x, y, CELL-1, CELL-1, 5, 5);

                // Slot number
                g2.setFont(F_XS);
                g2.setColor(sel ? Color.WHITE : C_FG_HINT);
                String num = String.valueOf(slot);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(num,
                    x + (CELL - fm.stringWidth(num)) / 2,
                    y + (CELL - fm.getHeight())       / 2 + fm.getAscent());

                // Tick overlay if selected
                if (sel) {
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 11));
                    g2.setColor(accent.brighter());
                    g2.drawString("✓", x + 2, y + CELL - 4);
                }
            }

            g2.dispose();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inner class: StatBar  (HP / Prayer / Spec progress bar)
    // ════════════════════════════════════════════════════════════════════════

    static class StatBar extends JPanel {
        private final String label;
        private final Color  barColor;
        private int   value, max;
        private static final int BAR_H = 18;

        StatBar(String label, Color barColor, int value, int max) {
            this.label    = label;
            this.barColor = barColor;
            this.value    = value;
            this.max      = max;
            setPreferredSize(new Dimension(Integer.MAX_VALUE, BAR_H + 4));
            setMaximumSize  (new Dimension(Integer.MAX_VALUE, BAR_H + 4));
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
        }

        void setValue(int v, int m) { value = v; max = m; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = BAR_H;
            int y = (getHeight() - h) / 2;

            // Label area (60px)
            g2.setFont(F_S);
            g2.setColor(C_FG_DIM);
            g2.drawString(label + ":", 0, y + h / 2 + g2.getFontMetrics().getAscent() / 2);

            // Track
            int barX = 58;
            int barW = w - barX - 38;
            g2.setColor(C_INPUT);
            g2.fillRoundRect(barX, y, barW, h, 6, 6);
            g2.setColor(C_BORDER);
            g2.drawRoundRect(barX, y, barW, h, 6, 6);

            // Fill
            if (max > 0) {
                double ratio    = Math.max(0, Math.min(1, (double) value / max));
                int    fillW    = (int)(barW * ratio);
                Color  fillCol  = ratio < 0.25 ? C_RED : ratio < 0.5 ? C_ORANGE : barColor;
                GradientPaint gp = new GradientPaint(barX, y, fillCol.brighter(), barX, y+h, fillCol);
                g2.setPaint(gp);
                if (fillW > 6) g2.fillRoundRect(barX, y, fillW, h, 6, 6);
                else if (fillW > 0) g2.fillRect(barX, y, fillW, h);
            }

            // Value text inside bar
            String valText = value + " / " + max;
            g2.setFont(F_XS);
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(valText,
                barX + (barW - fm.stringWidth(valText)) / 2,
                y + (h - fm.getHeight()) / 2 + fm.getAscent());

            g2.dispose();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Inner class: KeybindRow  (single row: [action label] [key badge] [Rebind btn])
    // ════════════════════════════════════════════════════════════════════════

    /**
     * A single row in the keybind configuration table.
     * <p>Layout:  [Action label – expands] | [Key badge – fixed] | [Rebind btn – fixed]</p>
     * Clicking <b>Rebind</b> arms the row for a single KEY_PRESSED event; pressing Escape cancels.
     */
    class KeybindRow extends JPanel {

        private final String     action;
        private       int        defaultCode;
        private final JLabel     badge;
        private final JButton    rebindBtn;
        private final OverlayUI  ui;

        /** Armed state: true while waiting for a key press. */
        private boolean capturing = false;

        private static final int BTN_W = 62;
        private static final int BTN_H = 22;
        private static final int ROW_H = 28;

        /** Colour of the badge background when active (key is set). */
        private final Color C_BADGE = new Color(0x2e, 0x2e, 0x3a);

        KeybindRow(String action, int keyCode, OverlayUI parent) {
            this.action      = action;
            this.defaultCode = keyCode;
            this.ui          = parent;

            setLayout(new GridBagLayout());
            setBackground(C_CARD);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_H));
            setPreferredSize(new Dimension(300, ROW_H));
            setAlignmentX(LEFT_ALIGNMENT);

            GridBagConstraints gc = new GridBagConstraints();
            gc.insets  = new Insets(2, 0, 2, 8);
            gc.anchor  = GridBagConstraints.WEST;

            // ── Column 0: action name ──────────────────────────────────────
            gc.gridx    = 0;
            gc.gridy    = 0;
            gc.weightx  = 1.0;
            gc.fill     = GridBagConstraints.HORIZONTAL;
            JLabel lbl = new JLabel(action);
            lbl.setFont(F_M);
            lbl.setForeground(C_FG);
            add(lbl, gc);

            // ── Column 1: key badge ────────────────────────────────────────
            gc.gridx   = 1;
            gc.weightx = 0;
            gc.fill    = GridBagConstraints.NONE;
            gc.insets  = new Insets(2, 0, 2, 8);
            badge = buildBadge(KeyEvent.getKeyText(keyCode));
            add(badge, gc);

            // ── Column 2: rebind button ────────────────────────────────────
            gc.gridx  = 2;
            gc.insets = new Insets(2, 0, 2, 0);
            rebindBtn = buildRebindBtn();
            add(rebindBtn, gc);
        }

        // ── helpers ──────────────────────────────────────────────────────────

        private JLabel buildBadge(String text) {
            JLabel lbl = new JLabel(text, SwingConstants.CENTER) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(C_BADGE);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.setColor(C_BORDER);
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            lbl.setFont(F_S.deriveFont(Font.BOLD));
            lbl.setForeground(C_ACCENT);
            lbl.setOpaque(false);
            lbl.setPreferredSize(new Dimension(68, 20));
            lbl.setMaximumSize(new Dimension(68, 20));
            return lbl;
        }

        private JButton buildRebindBtn() {
            JButton btn = pillBtn("Rebind", C_FG_DIM.darker(), BTN_W, BTN_H);
            btn.setFont(F_XS);
            btn.addActionListener(e -> {
                if (!capturing) startCapture();
                else            cancelCapture();
            });
            return btn;
        }

        // ── capture logic ─────────────────────────────────────────────────────

        private KeyEventDispatcher dispatcher;

        private void startCapture() {
            capturing = true;
            rebindBtn.setText("…");
            rebindBtn.setBackground(C_ACCENT.darker());
            badge.setText("?");

            dispatcher = e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) {
                    cancelCapture();
                } else {
                    commitCapture(code);
                }
                return true;
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addKeyEventDispatcher(dispatcher);
        }

        private void commitCapture(int code) {
            capturing = false;
            badge.setText(KeyEvent.getKeyText(code));
            rebindBtn.setText("Rebind");
            rebindBtn.setBackground(C_FG_DIM.darker());
            removeDispatcher();
            ui.onKeybindChanged(action, code);
        }

        private void cancelCapture() {
            capturing = false;
            String cur = KeyEvent.getKeyText(ui.keybinds.getOrDefault(action, defaultCode));
            badge.setText(cur);
            rebindBtn.setText("Rebind");
            rebindBtn.setBackground(C_FG_DIM.darker());
            removeDispatcher();
        }

        private void removeDispatcher() {
            if (dispatcher != null) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .removeKeyEventDispatcher(dispatcher);
                dispatcher = null;
            }
        }

        /** Reset this row to its default binding. */
        void reset() {
            ui.keybinds.put(action, defaultCode);
            badge.setText(KeyEvent.getKeyText(defaultCode));
            if (capturing) cancelCapture();
        }
    }

}
