package com.sun.java.fontmgr;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Modern, compact, and intuitive always-on-top PK HUD.
 * Three tabs:
 *   1. PK Combat — Real-time HP/Pray/Spec, Target tracker, Spec combos & Auto triggers
 *   2. Dharok Mode — Low HP Greataxe strike, 1-tick Whip+Def swap, combo eating & vengeances
 *   3. Gear Swapper — Visual multi-item gear switch manager
 */
public class MiniOverlayUI {

    private static final Color BG_DARK      = new Color(20, 22, 26, 235);
    private static final Color CARD_BG      = new Color(30, 33, 38, 220);
    private static final Color TITLE_BG     = new Color(38, 42, 48);
    private static final Color FG_BRIGHT    = new Color(240, 242, 245);
    private static final Color FG_MUTED     = new Color(150, 155, 165);
    private static final Color ACCENT_BLUE  = new Color(85, 145, 255);
    private static final Color ACCENT_GOLD  = new Color(255, 195, 60);
    private static final Color ACCENT_GREEN = new Color(65, 195, 95);
    private static final Color ACCENT_RED   = new Color(235, 75, 75);
    private static final Color BTN_BG       = new Color(42, 45, 52);
    private static final Color BTN_BORDER   = new Color(60, 65, 75);

    private final CombatScript script;
    private final JFrame frame;
    private final SmoothBar hpBar, prayBar, specBar;
    private final JLabel targetNameLabel, targetHpLabel, combatStatusLabel, actionTickerLabel;
    private final JLabel dhAxeStatusLabel, dhMaxHitLabel, dhSwapStatusLabel;
    private final JToggleButton masterToggle;
    private final JLabel tabPk, tabDh, tabSwap;
    private final CardLayout cards = new CardLayout();
    private final JPanel body;
    private final com.sun.java.fontmgr.swap.SwapManager swapManager;
    private final com.sun.java.fontmgr.swap.SwapDispatcher swapDispatcher;

    /** 0 = PK, 1 = DH, 2 = SWAP */
    private int activeTab = 0;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, String.format("HUD-Worker-%d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000)));
        t.setDaemon(true);
        return t;
    });

    private final java.util.Timer animationTimer = new java.util.Timer(true);
    private Point dragOffset = null;
    private boolean collapsed = false;
    private final java.nio.file.Path cfgPath;

    // Control toggles
    private JToggleButton pkAutoSpecBtn, pkPunishToggle, pkVengToggle, pkDefPrayToggle;
    private JToggleButton pkComboEatToggle, pkProtectItemToggle;
    private JToggleButton dhModeToggle, dhPunishToggle, dhVengToggle;
    private JToggleButton nhModeToggle, nhDefPrayToggle;
    private javax.swing.JTextField nhKoHpField, comboEatHpField, brewPreferField;
    private JButton pkSpecModeBtn;
    private JLabel tabNh;
    private JPanel sharedVitals;
    private JPanel sharedTarget;

    public static void show(CombatScript script) {
        if (script == null) return;
        SwingUtilities.invokeLater(() -> new MiniOverlayUI(script).setVisible(true));
    }

    public MiniOverlayUI(CombatScript script) {
        this.script = script;
        this.swapManager = new com.sun.java.fontmgr.swap.SwapManager();
        this.swapDispatcher = new com.sun.java.fontmgr.swap.SwapDispatcher(script);
        HotkeyManager.get().setSwapper(swapManager, swapDispatcher);
        script.nhEnabled = false;

        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setType(Window.Type.UTILITY);
        frame.setTitle("RoatzBot PK HUD");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        cfgPath = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), ".cache", "fontconfig.properties");
        loadConfig();

        RoundedPanel root = new RoundedPanel(14, BG_DARK);
        root.setLayout(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(6, 8, 8, 8));

        // ── Title Bar ─────────────────────────────────────────────────────────
        JPanel titleBar = new JPanel(new BorderLayout(6, 0));
        titleBar.setOpaque(true);
        titleBar.setBackground(TITLE_BG);
        titleBar.setBorder(new EmptyBorder(4, 8, 4, 8));

        JLabel appTitle = new JLabel("⚔  ROATZBOT PK");
        appTitle.setForeground(ACCENT_GOLD);
        appTitle.setFont(appTitle.getFont().deriveFont(Font.BOLD, 11.5f));
        titleBar.add(appTitle, BorderLayout.WEST);

        JPanel titleRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        titleRight.setOpaque(false);

        JButton collapseBtn = new JButton(collapsed ? "▴" : "▾");
        collapseBtn.setFocusable(false);
        collapseBtn.setBorder(null);
        collapseBtn.setContentAreaFilled(false);
        collapseBtn.setForeground(FG_BRIGHT);
        collapseBtn.setFont(collapseBtn.getFont().deriveFont(12f));
        collapseBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        collapseBtn.addActionListener(e -> toggleCollapse());
        titleRight.add(collapseBtn);

        masterToggle = new JToggleButton(script.enabled ? "BOT ON" : "BOT OFF");
        masterToggle.setSelected(script.enabled);
        masterToggle.setFocusPainted(false);
        masterToggle.setFont(masterToggle.getFont().deriveFont(Font.BOLD, 10f));
        styleMasterToggle(masterToggle, script.enabled);
        masterToggle.addActionListener(e -> {
            boolean on = masterToggle.isSelected();
            script.enabled = on;
            styleMasterToggle(masterToggle, on);
            saveConfig();
        });
        titleRight.add(masterToggle);
        titleBar.add(titleRight, BorderLayout.EAST);

        // ── Tabs Navigation ──────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(4, 2, 2, 2));

        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        tabs.setOpaque(false);
        tabPk = tabLabel("PK Combat", activeTab == 0);
        tabDh = tabLabel("Dharok", activeTab == 1);
        tabNh = tabLabel("NH", activeTab == 3);
        tabSwap = tabLabel("Swapper", activeTab == 2);

        tabPk.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showTab(0); }
        });
        tabDh.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showTab(1); }
        });
        tabNh.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showTab(3); }
        });
        tabSwap.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showTab(2); }
        });

        tabs.add(tabPk);
        tabs.add(tabDh);
        tabs.add(tabNh);
        tabs.add(tabSwap);
        header.add(tabs, BorderLayout.WEST);

        // ── Shared vitals / target (one parent — Swing forbids adding a
        //    component to both PK and DH pages) ───────────────────────────────
        hpBar   = new SmoothBar("HP",   new Color(65, 195, 95),  new Color(235, 75, 75));
        prayBar = new SmoothBar("PRAY", new Color(130, 95, 220), new Color(130, 95, 220));
        specBar = new SmoothBar("SPEC", new Color(245, 185, 45), new Color(245, 185, 45));

        targetNameLabel   = createLabel("Target: -", FG_BRIGHT, 11f, true);
        targetHpLabel     = createLabel("HP: -", ACCENT_GOLD, 11f, true);
        combatStatusLabel = createLabel("Status: IDLE", FG_MUTED, 10.5f, false);
        actionTickerLabel = createLabel("Action: Ready", ACCENT_BLUE, 10f, false);

        dhAxeStatusLabel  = createLabel("Greataxe: READY (Low HP Auto-Swing)", ACCENT_GREEN, 10.5f, true);
        dhMaxHitLabel     = createLabel("Axe Max Hit: -", ACCENT_GOLD, 10.5f, true);
        dhSwapStatusLabel = createLabel("Auto 1-Tick Whip+Def: READY", FG_BRIGHT, 10f, false);

        sharedVitals = new RoundedPanel(8, CARD_BG);
        sharedVitals.setLayout(new BoxLayout(sharedVitals, BoxLayout.Y_AXIS));
        sharedVitals.setBorder(new EmptyBorder(6, 8, 6, 8));
        sharedVitals.add(hpBar);
        sharedVitals.add(Box.createVerticalStrut(4));
        sharedVitals.add(prayBar);
        sharedVitals.add(Box.createVerticalStrut(4));
        sharedVitals.add(specBar);
        sharedVitals.setAlignmentX(Component.LEFT_ALIGNMENT);
        sharedVitals.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));

        sharedTarget = new RoundedPanel(8, CARD_BG);
        sharedTarget.setLayout(new BorderLayout(6, 2));
        sharedTarget.setBorder(new EmptyBorder(6, 8, 6, 8));
        JPanel targetTop = new JPanel(new BorderLayout(4, 0));
        targetTop.setOpaque(false);
        targetTop.add(targetNameLabel, BorderLayout.WEST);
        targetTop.add(targetHpLabel, BorderLayout.EAST);
        sharedTarget.add(targetTop, BorderLayout.NORTH);
        JPanel targetBot = new JPanel(new BorderLayout(4, 0));
        targetBot.setOpaque(false);
        targetBot.add(combatStatusLabel, BorderLayout.WEST);
        sharedTarget.add(targetBot, BorderLayout.SOUTH);
        sharedTarget.setAlignmentX(Component.LEFT_ALIGNMENT);
        sharedTarget.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.add(titleBar);
        north.add(header);
        north.add(Box.createVerticalStrut(4));
        north.add(sharedVitals);
        north.add(Box.createVerticalStrut(4));
        north.add(sharedTarget);
        root.add(north, BorderLayout.NORTH);

        body = new JPanel(cards);
        body.setOpaque(false);
        body.add(buildPkCombatPage(), "PK");
        body.add(buildDharokPage(), "DH");
        body.add(buildNhPage(), "NH");
        body.add(new com.sun.java.fontmgr.swap.SwapperPanel(swapManager, swapDispatcher, script), "SWAP");
        root.add(body, BorderLayout.CENTER);

        JPanel footer = new RoundedPanel(8, CARD_BG);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(new EmptyBorder(4, 6, 4, 6));
        actionTickerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(actionTickerLabel);
        root.add(footer, BorderLayout.SOUTH);
        frame.setContentPane(root);
        showTab(activeTab);
        frame.setLocationRelativeTo(null);
        if (!collapsed) frame.setLocation(60, 60);
        frame.setBackground(new Color(0, 0, 0, 0));

        // Dragging window support
        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragOffset = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset != null) {
                    Point p = e.getLocationOnScreen();
                    frame.setLocation(p.x - dragOffset.x, p.y - dragOffset.y);
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragOffset = null;
                saveConfig();
            }
        };
        root.addMouseListener(drag);
        root.addMouseMotionListener(drag);

        installShortcuts(root);

        animationTimer.schedule(new java.util.TimerTask() {
            @Override public void run() {
                hpBar.tick();
                prayBar.tick();
                specBar.tick();
            }
        }, 0, 35);

        scheduler.scheduleAtFixedRate(this::updateUi, 0, 120, TimeUnit.MILLISECONDS);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                scheduler.shutdownNow();
                animationTimer.cancel();
            }
        });
    }

    private JPanel buildPkCombatPage() {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setOpaque(false);
        page.add(Box.createVerticalStrut(6));

        // Spec Controls
        pkSpecModeBtn = new JButton("Spec Setup: " + script.comboSetupName());
        pkSpecModeBtn.setFocusPainted(false);
        pkSpecModeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        pkSpecModeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        pkSpecModeBtn.setFont(pkSpecModeBtn.getFont().deriveFont(Font.BOLD, 10.5f));
        pkSpecModeBtn.setBackground(BTN_BG);
        pkSpecModeBtn.setForeground(ACCENT_GOLD);
        pkSpecModeBtn.setBorder(BorderFactory.createLineBorder(BTN_BORDER));
        pkSpecModeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pkSpecModeBtn.setToolTipText("Click to cycle: Gmaul → Claws+Gmaul → AGS+Gmaul → DMace+Gmaul → VLS → DBow");
        pkSpecModeBtn.addActionListener(e -> {
            script.toggleComboSetup();
            pkSpecModeBtn.setText("Spec Setup: " + script.comboSetupName());
            saveConfig();
        });
        page.add(pkSpecModeBtn);
        page.add(Box.createVerticalStrut(4));

        // Quick Action Toggles Row
        pkAutoSpecBtn = miniToggle("Auto Spec", script.autoSpecEnabled, "Dumps claws→gmaul (or current spec setup) when you have a target and spec energy. Does not need BOT ON.");
        pkAutoSpecBtn.addActionListener(e -> {
            script.autoSpecEnabled = pkAutoSpecBtn.isSelected();
            styleMiniToggle(pkAutoSpecBtn, script.autoSpecEnabled);
            saveConfig();
        });

        pkPunishToggle = miniToggle("Eat Punish", script.eatPunishEnabled, "Punishes opponent with instant spec when they eat");
        pkPunishToggle.addActionListener(e -> {
            script.eatPunishEnabled = pkPunishToggle.isSelected();
            styleMiniToggle(pkPunishToggle, script.eatPunishEnabled);
            saveConfig();
        });

        pkVengToggle = miniToggle("Auto Veng", script.autoVengEnabled, "Casts Vengeance upon entering combat & before spec combos");
        pkVengToggle.addActionListener(e -> {
            script.autoVengEnabled = pkVengToggle.isSelected();
            styleMiniToggle(pkVengToggle, script.autoVengEnabled);
            saveConfig();
        });

        pkDefPrayToggle = miniToggle("Overheads", script.defensivePrayersEnabled,
                "Auto protect — Z mage / X range / C melee");
        pkDefPrayToggle.addActionListener(e -> {
            script.defensivePrayersEnabled = pkDefPrayToggle.isSelected();
            styleMiniToggle(pkDefPrayToggle, script.defensivePrayersEnabled);
            saveConfig();
        });

        pkComboEatToggle = miniToggle("Combo Eat", script.comboEatEnabled,
                "Auto combo-eat (marlin+brew+halibut) when HP falls below threshold");
        pkComboEatToggle.addActionListener(e -> {
            script.comboEatEnabled = pkComboEatToggle.isSelected();
            styleMiniToggle(pkComboEatToggle, script.comboEatEnabled);
            saveConfig();
        });

        pkProtectItemToggle = miniToggle("Protect Item", script.autoProtectItemEnabled,
                "Auto Protect Item when you step into a PvP / danger zone");
        pkProtectItemToggle.addActionListener(e -> {
            script.autoProtectItemEnabled = pkProtectItemToggle.isSelected();
            styleMiniToggle(pkProtectItemToggle, script.autoProtectItemEnabled);
            saveConfig();
        });

        JPanel togglesRow = new JPanel(new GridLayout(3, 2, 4, 4));
        togglesRow.setOpaque(false);
        togglesRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
        togglesRow.add(pkAutoSpecBtn);
        togglesRow.add(pkPunishToggle);
        togglesRow.add(pkVengToggle);
        togglesRow.add(pkDefPrayToggle);
        togglesRow.add(pkComboEatToggle);
        togglesRow.add(pkProtectItemToggle);
        page.add(togglesRow);
        page.add(Box.createVerticalStrut(6));

        JPanel keys = new RoundedPanel(8, CARD_BG);
        keys.setLayout(new BoxLayout(keys, BoxLayout.Y_AXIS));
        keys.setBorder(new EmptyBorder(4, 6, 4, 6));
        JLabel hotkeys = createLabel("1–4 eat  ·  Q claws+gmaul  ·  W gmaul  ·  E veng  ·  R setup", FG_MUTED, 9.5f, false);
        hotkeys.setAlignmentX(Component.LEFT_ALIGNMENT);
        keys.add(hotkeys);
        page.add(keys);
        return page;
    }

    private JPanel buildDharokPage() {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setOpaque(false);
        page.add(Box.createVerticalStrut(6));

        // DH Status Card
        JPanel dhCard = new RoundedPanel(8, CARD_BG);
        dhCard.setLayout(new BoxLayout(dhCard, BoxLayout.Y_AXIS));
        dhCard.setBorder(new EmptyBorder(6, 8, 6, 8));

        dhAxeStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhMaxHitLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhSwapStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        dhCard.add(dhAxeStatusLabel);
        dhCard.add(Box.createVerticalStrut(4));
        dhCard.add(dhMaxHitLabel);
        dhCard.add(Box.createVerticalStrut(4));
        dhCard.add(dhSwapStatusLabel);
        page.add(dhCard);
        page.add(Box.createVerticalStrut(6));

        // DH Toggles
        dhModeToggle = miniToggle(script.dharokEnabled ? "DH MODE: ACTIVE" : "DH MODE: OFF", script.dharokEnabled,
                "Enables low HP Dharok Greataxe auto-equip & 1-tick Whip+Def restore");
        dhModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhModeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        dhModeToggle.addActionListener(e -> {
            script.dharokEnabled = dhModeToggle.isSelected();
            dhModeToggle.setText(script.dharokEnabled ? "DH MODE: ACTIVE" : "DH MODE: OFF");
            styleMiniToggle(dhModeToggle, script.dharokEnabled);
            if (script.dharokEnabled) {
                script.enabled = false;
                script.comboEatEnabled = false;
                script.dharokAutoEat = false;
                script.autoSpecEnabled = false;
                script.dharokUseOrb = false;
                script.dharokAutoStack = false;
                script.eatPunishEnabled = true;
                script.autoVengEnabled = true;
                masterToggle.setSelected(false);
                styleMasterToggle(masterToggle, false);
                if (dhPunishToggle != null) {
                    dhPunishToggle.setSelected(true);
                    styleMiniToggle(dhPunishToggle, true);
                }
                if (dhVengToggle != null) {
                    dhVengToggle.setSelected(true);
                    styleMiniToggle(dhVengToggle, true);
                }
            }
            saveConfig();
        });

        dhPunishToggle = miniToggle("Eat Punish", script.eatPunishEnabled, "Gmaul punish when opponent eats");
        dhPunishToggle.addActionListener(e -> {
            script.eatPunishEnabled = dhPunishToggle.isSelected();
            styleMiniToggle(dhPunishToggle, script.eatPunishEnabled);
            saveConfig();
        });

        dhVengToggle = miniToggle("Auto Veng", script.autoVengEnabled, "Vengeance on engage and before strikes");
        dhVengToggle.addActionListener(e -> {
            script.autoVengEnabled = dhVengToggle.isSelected();
            styleMiniToggle(dhVengToggle, script.autoVengEnabled);
            saveConfig();
        });

        page.add(dhModeToggle);
        page.add(Box.createVerticalStrut(4));

        JPanel dhSubToggles = new JPanel(new GridLayout(1, 2, 4, 0));
        dhSubToggles.setOpaque(false);
        dhSubToggles.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        dhSubToggles.add(dhPunishToggle);
        dhSubToggles.add(dhVengToggle);
        page.add(dhSubToggles);
        page.add(Box.createVerticalStrut(6));

        // DH Info Card
        JPanel dhGuide = new RoundedPanel(8, CARD_BG);
        dhGuide.setLayout(new BoxLayout(dhGuide, BoxLayout.Y_AXIS));
        dhGuide.setBorder(new EmptyBorder(6, 8, 6, 8));

        JLabel l1 = createLabel("• You orb to 1. Bot axes only when stacked — not at 55 HP.", FG_MUTED, 9.5f, false);
        JLabel l2 = createLabel("• Gmaul follow if they live the axe (50%+ spec)", FG_MUTED, 9.5f, false);
        JLabel l3 = createLabel("• 1 Marlin  ·  2 +Hali  ·  3 +Brew  ·  E Veng", ACCENT_GOLD, 9.5f, false);
        l1.setAlignmentX(Component.LEFT_ALIGNMENT);
        l2.setAlignmentX(Component.LEFT_ALIGNMENT);
        l3.setAlignmentX(Component.LEFT_ALIGNMENT);

        dhGuide.add(l1);
        dhGuide.add(Box.createVerticalStrut(2));
        dhGuide.add(l2);
        dhGuide.add(Box.createVerticalStrut(2));
        dhGuide.add(l3);

        page.add(dhGuide);
        return page;
    }

    private JPanel buildNhPage() {
        JPanel page = new JPanel();
        page.setLayout(new BoxLayout(page, BoxLayout.Y_AXIS));
        page.setOpaque(false);
        page.add(Box.createVerticalStrut(6));

        // NH enable toggle
        nhModeToggle = miniToggle(script.nhEnabled ? "NH MODE: ACTIVE" : "NH MODE: OFF", script.nhEnabled,
                "Auto ice barrage + staggered gear switches (mage→range→melee)");
        nhModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        nhModeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        nhModeToggle.addActionListener(e -> {
            script.nhEnabled = nhModeToggle.isSelected();
            nhModeToggle.setText(script.nhEnabled ? "NH MODE: ACTIVE" : "NH MODE: OFF");
            script.nhPhaseName = script.nhEnabled ? "AUTO" : "IDLE";
            styleMiniToggle(nhModeToggle, script.nhEnabled);
            saveConfig();
        });
        page.add(nhModeToggle);
        page.add(Box.createVerticalStrut(4));

        // Def pray + protect item (reuses pk toggles behavior on NH)
        nhDefPrayToggle = miniToggle("Overheads", script.defensivePrayersEnabled,
                "Auto protect — Z mage / X range / C melee");
        nhDefPrayToggle.addActionListener(e -> {
            script.defensivePrayersEnabled = nhDefPrayToggle.isSelected();
            styleMiniToggle(nhDefPrayToggle, script.defensivePrayersEnabled);
            saveConfig();
        });
        JPanel nhToggleRow = new JPanel(new GridLayout(1, 2, 4, 0));
        nhToggleRow.setOpaque(false);
        nhToggleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        nhToggleRow.add(nhDefPrayToggle);
        nhToggleRow.add(miniToggle("Protect Item", script.autoProtectItemEnabled,
                "Auto Protect Item in danger zone"));
        ((JToggleButton) nhToggleRow.getComponent(1)).addActionListener(e -> {
            script.autoProtectItemEnabled = ((JToggleButton) nhToggleRow.getComponent(1)).isSelected();
            styleMiniToggle((JToggleButton) nhToggleRow.getComponent(1), script.autoProtectItemEnabled);
            saveConfig();
        });
        page.add(nhToggleRow);
        page.add(Box.createVerticalStrut(6));

        // Tunable numbers
        nhKoHpField = numField(script.nhKoHp, 50);
        comboEatHpField = numField(script.comboEatHpThreshold, 50);
        brewPreferField = numField(script.brewPreferAboveHp, 50);

        page.add(formRow("KO HP (melee swap)", nhKoHpField));
        page.add(Box.createVerticalStrut(3));
        page.add(formRow("Combo-eat HP", comboEatHpField));
        page.add(Box.createVerticalStrut(3));
        page.add(formRow("Brew-prefer HP", brewPreferField));
        page.add(Box.createVerticalStrut(6));

        // Save / apply
        JButton applyBtn = new JButton("Apply Numbers");
        applyBtn.setFocusPainted(false);
        applyBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        applyBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        applyBtn.setBackground(BTN_BG);
        applyBtn.setForeground(ACCENT_GOLD);
        applyBtn.setBorder(BorderFactory.createLineBorder(BTN_BORDER));
        applyBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        applyBtn.addActionListener(e -> {
            script.nhKoHp = safeInt(nhKoHpField, script.nhKoHp);
            script.comboEatHpThreshold = safeInt(comboEatHpField, script.comboEatHpThreshold);
            script.brewPreferAboveHp = safeInt(brewPreferField, script.brewPreferAboveHp);
            saveConfig();
        });
        page.add(applyBtn);
        page.add(Box.createVerticalStrut(6));

        // Guide
        JPanel guide = new RoundedPanel(8, CARD_BG);
        guide.setLayout(new BoxLayout(guide, BoxLayout.Y_AXIS));
        guide.setBorder(new EmptyBorder(6, 8, 6, 8));
        JLabel g1 = createLabel("• Assign gear in full overlay → NH tab.", FG_MUTED, 9.5f, false);
        JLabel g2 = createLabel("A/S/D eat  ·  Space ice  ·  T tank", ACCENT_GOLD, 9.5f, false);
        JLabel g3 = createLabel("Z/X/C overheads  ·  Num9 auto-pray", FG_MUTED, 9.5f, false);
        g1.setAlignmentX(Component.LEFT_ALIGNMENT);
        g2.setAlignmentX(Component.LEFT_ALIGNMENT);
        g3.setAlignmentX(Component.LEFT_ALIGNMENT);
        guide.add(g1);
        guide.add(Box.createVerticalStrut(2));
        guide.add(g2);
        guide.add(Box.createVerticalStrut(2));
        guide.add(g3);
        page.add(guide);
        return page;
    }

    private javax.swing.JTextField numField(int value, int cols) {
        javax.swing.JTextField f = new javax.swing.JTextField(Integer.toString(value), cols);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        f.setPreferredSize(new Dimension(60, 22));
        f.setBackground(CARD_BG);
        f.setForeground(FG_BRIGHT);
        f.setCaretColor(FG_BRIGHT);
        f.setFont(f.getFont().deriveFont(Font.BOLD, 10.5f));
        return f;
    }

    private JPanel formRow(String label, javax.swing.JTextField field) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        JLabel l = createLabel(label, FG_MUTED, 9.5f, false);
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.EAST);
        return row;
    }

    private int safeInt(javax.swing.JTextField f, int fallback) {
        try { return Integer.parseInt(f.getText().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private int safeIntString(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private JLabel tabLabel(String text, boolean on) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11.5f));
        l.setForeground(on ? ACCENT_GOLD : FG_MUTED);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return l;
    }

    private void showTab(int tab) {
        activeTab = tab;
        tabPk.setForeground(tab == 0 ? ACCENT_GOLD : FG_MUTED);
        tabDh.setForeground(tab == 1 ? ACCENT_GOLD : FG_MUTED);
        tabNh.setForeground(tab == 3 ? ACCENT_GOLD : FG_MUTED);
        tabSwap.setForeground(tab == 2 ? ACCENT_GOLD : FG_MUTED);

        if (tab == 0) cards.show(body, "PK");
        else if (tab == 1) cards.show(body, "DH");
        else if (tab == 3) cards.show(body, "NH");
        else cards.show(body, "SWAP");

        HotkeyManager.get().setOverlayMode(tab == 2
                ? HotkeyManager.OverlayMode.SWAP
                : HotkeyManager.OverlayMode.PK);

        if (sharedVitals != null) sharedVitals.setVisible(tab != 2);
        if (sharedTarget != null) sharedTarget.setVisible(tab != 2);

        if (collapsed) collapsed = false;
        applySize();
        saveConfig();
    }

    private void applySize() {
        if (collapsed) {
            frame.setSize(240, 48);
        } else if (activeTab == 2) {
            frame.setSize(300, 460);
        } else if (activeTab == 3) {
            frame.setSize(290, 460);
        } else {
            frame.setSize(270, 400);
        }
    }

    private void toggleCollapse() {
        collapsed = !collapsed;
        applySize();
        saveConfig();
    }

    private JLabel createLabel(String text, Color color, float size, boolean bold) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, size));
        return l;
    }

    private JToggleButton miniToggle(String label, boolean on, String tip) {
        JToggleButton b = new JToggleButton(label);
        b.setSelected(on);
        b.setFocusPainted(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 10f));
        if (tip != null) b.setToolTipText(tip);
        styleMiniToggle(b, on);
        return b;
    }

    private void styleMiniToggle(JToggleButton b, boolean on) {
        b.setBackground(on ? new Color(45, 110, 60) : BTN_BG);
        b.setForeground(on ? new Color(225, 255, 230) : FG_MUTED);
        b.setBorder(BorderFactory.createLineBorder(on ? new Color(70, 160, 90) : BTN_BORDER));
    }

    private void styleMasterToggle(JToggleButton b, boolean on) {
        b.setText(on ? "BOT ON" : "BOT OFF");
        b.setBackground(on ? new Color(40, 140, 60) : new Color(50, 53, 60));
        b.setForeground(on ? Color.WHITE : FG_MUTED);
        b.setBorder(BorderFactory.createLineBorder(on ? new Color(65, 195, 95) : BTN_BORDER));
    }

    private void updateUi() {
        try {
            final GameState gs = (script.stateReader != null) ? script.stateReader.read() : null;
            final int hp = gs != null ? Math.max(0, Math.min(99, gs.hp)) : 0;
            final int prayer = gs != null ? Math.max(0, Math.min(99, gs.prayer)) : 0;
            final int spec = gs != null ? Math.max(0, Math.min(100, gs.spec)) : 0;
            final int tick = gs != null ? gs.tick : -1;
            final String target = gs != null && gs.target != null && !gs.target.isEmpty() ? gs.target : "-";
            final String lastAction = script.lastAction != null && !script.lastAction.isEmpty()
                    ? script.lastAction : "Ready";

            SwingUtilities.invokeLater(() -> {
                try {
                    hpBar.setValues(hp, 99);
                    prayBar.setValues(prayer, 99);
                    specBar.setValues(spec, 100);

                    if (pkSpecModeBtn != null) {
                        pkSpecModeBtn.setText("Spec Setup: " + script.comboSetupName());
                    }

                    // Target Update
                    targetNameLabel.setText("Target: " + target);
                    if (script.targetHp > 0) {
                        targetHpLabel.setText("HP: " + script.targetHp + (script.targetMaxHp > 0 ? "/" + script.targetMaxHp : ""));
                        targetHpLabel.setForeground(script.inKillRange ? ACCENT_RED : ACCENT_GOLD);
                    } else {
                        targetHpLabel.setText("HP: -");
                        targetHpLabel.setForeground(FG_MUTED);
                    }

                    // Combat Status
                    if (script.isInActivePvpFightPublic()) {
                        if (script.inKillRange) {
                            combatStatusLabel.setText("Status: KO IN KILL RANGE!");
                            combatStatusLabel.setForeground(ACCENT_RED);
                        } else {
                            combatStatusLabel.setText("Status: IN FIGHT (Tick: " + tick + ")");
                            combatStatusLabel.setForeground(ACCENT_GREEN);
                        }
                    } else {
                        combatStatusLabel.setText("Status: IDLE (Tick: " + (tick >= 0 ? tick : "-") + ")");
                        combatStatusLabel.setForeground(FG_MUTED);
                    }

                    // Dharok Status
                    if (script.dharokEnabled) {
                        int maxHp = script.stateReader != null ? script.stateReader.getMaxHp() : 99;
                        int estimatedMax = MaxHitCalculator.dharokMaxHit(script.readMeleeStrPublic(), hp, maxHp);
                        dhMaxHitLabel.setText("Axe Max Hit: " + estimatedMax + " (at " + hp + " HP)");

                        if (script.hasPendingDhGmaul()) {
                            dhAxeStatusLabel.setText("Greataxe: GMAUL FINISHER ARMED");
                            dhAxeStatusLabel.setForeground(ACCENT_RED);
                        } else if (script.pendingDhStack || script.dharokStackArmed) {
                            dhAxeStatusLabel.setText("Greataxe: SWINGING KO HIT!");
                            dhAxeStatusLabel.setForeground(ACCENT_RED);
                        } else if (script.inSelfOrbWindow(script.currentTick)) {
                            dhAxeStatusLabel.setText("Greataxe: STACKING — axe next");
                            dhAxeStatusLabel.setForeground(ACCENT_GOLD);
                        } else if (hp <= 15) {
                            dhAxeStatusLabel.setText("Greataxe: STACKED — waiting KO/orb");
                            dhAxeStatusLabel.setForeground(ACCENT_GOLD);
                        } else {
                            dhAxeStatusLabel.setText("Greataxe: READY (Whip baseline)");
                            dhAxeStatusLabel.setForeground(ACCENT_GREEN);
                        }

                        if (script.pendingDhWhipDef && script.hasPendingDhGmaul()) {
                            dhSwapStatusLabel.setText("Whip+Def: waiting on gmaul");
                            dhSwapStatusLabel.setForeground(ACCENT_GOLD);
                        } else if (script.pendingDhWhipDef) {
                            dhSwapStatusLabel.setText("Auto 1-Tick Whip+Def: SWAPPING...");
                            dhSwapStatusLabel.setForeground(ACCENT_GOLD);
                        } else {
                            dhSwapStatusLabel.setText("Auto 1-Tick Whip+Def: ACTIVE");
                            dhSwapStatusLabel.setForeground(ACCENT_GREEN);
                        }
                    } else {
                        dhAxeStatusLabel.setText("Greataxe: DH MODE OFF");
                        dhAxeStatusLabel.setForeground(FG_MUTED);
                        dhMaxHitLabel.setText("Axe Max Hit: -");
                        dhSwapStatusLabel.setText("Auto 1-Tick Whip+Def: off");
                        dhSwapStatusLabel.setForeground(FG_MUTED);
                    }

                    // Action Ticker
                    actionTickerLabel.setText("Action: " + formatAction(lastAction));

                    // Sync toggles (selected + color — color-only left buttons visually stuck)
                    syncToggle(pkAutoSpecBtn, script.autoSpecEnabled);
                    syncToggle(pkPunishToggle, script.eatPunishEnabled);
                    syncToggle(pkVengToggle, script.autoVengEnabled);
                    syncToggle(pkDefPrayToggle, script.defensivePrayersEnabled);
                    syncToggle(pkComboEatToggle, script.comboEatEnabled);
                    syncToggle(pkProtectItemToggle, script.autoProtectItemEnabled);
                    syncToggle(dhPunishToggle, script.eatPunishEnabled);
                    syncToggle(dhVengToggle, script.autoVengEnabled);
                    syncToggle(nhDefPrayToggle, script.defensivePrayersEnabled);
                    if (nhModeToggle != null) {
                        syncToggle(nhModeToggle, script.nhEnabled);
                        nhModeToggle.setText(script.nhEnabled ? "NH MODE: ACTIVE" : "NH MODE: OFF");
                    }
                    if (dhModeToggle != null) {
                        syncToggle(dhModeToggle, script.dharokEnabled);
                        dhModeToggle.setText(script.dharokEnabled ? "DH MODE: ACTIVE" : "DH MODE: OFF");
                    }
                    if (masterToggle != null) {
                        if (masterToggle.isSelected() != script.enabled) masterToggle.setSelected(script.enabled);
                        styleMasterToggle(masterToggle, script.enabled);
                    }

                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void syncToggle(JToggleButton b, boolean on) {
        if (b == null) return;
        if (b.isSelected() != on) b.setSelected(on);
        styleMiniToggle(b, on);
    }

    private String formatAction(String raw) {
        if (raw == null || raw.isEmpty() || "Ready".equals(raw)) return "Ready";
        if (raw.contains("TRIPLE") || raw.contains("FORCE_TRIPLE")) return "Triple Eat: Marlin ➔ Brew ➔ Halibut";
        if (raw.contains("DBL_HALI") || raw.contains("DOUBLE")) return "Double Eat: Marlin ➔ Halibut";
        if (raw.contains("SINGLE") || raw.contains("FORCE_EAT") || raw.contains("DH_AXE_EAT")
                || raw.contains("DH_PANIC") || raw.contains("DH_LOW_EAT") || raw.contains("DH_STACK_EAT"))
            return "Eating food";
        if (raw.contains("RESTORE") || raw.contains("SANFEW")) return "Drank Restore / Sanfew";
        if (raw.contains("DH_WHIP_DEF") || raw.contains("MH_DEF")) return "Restored Whip + Defender";
        if (raw.contains("DH_GMAUL_HOLD")) return "Gmaul waiting on hitsplat";
        if (raw.contains("DH_GMAUL")) return "Gmaul finisher";
        if (raw.contains("DH_AXE_ARM_GMAUL")) return "Axe swung — gmaul next tick";
        if (raw.contains("DH_AXE") || raw.contains("DH_MANUAL_AXE")) return "Swung Dharok Greataxe";
        if (raw.contains("NO_CLAWS") || raw.contains("NO_CLAWS@")) return "Claws not in inv/equip";
        if (raw.contains("CLAWS_WIELD")) return "Wielding dragon claws";
        if (raw.contains("Q_CLAWS") || raw.contains("Q_CLAWS+GMAUL")) return "Q — claws dump next tick";
        if (raw.contains("NOGMAUL")) return "Claws/AGS too low — no gmaul";
        if (raw.contains("GMAUL_ON_")) return "High hit — gmaul follow";
        if (raw.contains("LC_WAIT")) return "Ice next tick — then left-click";
        if (raw.contains("LC_ICE") || raw.contains("LC_ICE_BARRAGE")) return "Ice Barrage armed — left-click a player";
        if (raw.startsWith("LC_")) return "Spell armed — left-click a player";
        if (raw.contains("SWAP_CAST_ARMED")) return "Spell armed — left-click a player";
        if (raw.contains("CLAWS")) return "Dragon claws spec";
        if (raw.contains("PUNISH")) return "Eat Punish Executed";
        if (raw.contains("VENG")) return "Casted Vengeance";
        if (raw.contains("DH_IDLE")) return "DH idle — out of fight";
        if (raw.contains("DH_ENGAGE")) return "Fight start — vengeance";
        return raw;
    }

    private void installShortcuts(JComponent root) {
        AbstractAction toggleVisible = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                frame.setVisible(!frame.isVisible());
            }
        };
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke("control SHIFT R"), "toggleVisible");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, 0), "toggleVisibleInsert");
        root.getActionMap().put("toggleVisible", toggleVisible);
        root.getActionMap().put("toggleVisibleInsert", toggleVisible);
    }

    private void loadConfig() {
        try {
            java.nio.file.Path dir = cfgPath.getParent();
            if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
            if (java.nio.file.Files.exists(cfgPath)) {
                java.util.Properties p = new java.util.Properties();
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(cfgPath)) { p.load(in); }
                collapsed = "true".equalsIgnoreCase(p.getProperty("collapsed"));
                String tab = p.getProperty("tab", "PK");
                if ("DH".equalsIgnoreCase(tab)) activeTab = 1;
                else if ("SWAP".equalsIgnoreCase(tab)) activeTab = 2;
                else if ("NH".equalsIgnoreCase(tab)) activeTab = 3;
                else activeTab = 0;

                if (p.containsKey("dh")) script.dharokEnabled = "true".equalsIgnoreCase(p.getProperty("dh"));
                if (p.containsKey("pun")) script.eatPunishEnabled = "true".equalsIgnoreCase(p.getProperty("pun"));
                if (p.containsKey("veng")) script.autoVengEnabled = "true".equalsIgnoreCase(p.getProperty("veng"));
                if (p.containsKey("autospec")) script.autoSpecEnabled = "true".equalsIgnoreCase(p.getProperty("autospec"));
                if (p.containsKey("defpray")) script.defensivePrayersEnabled = "true".equalsIgnoreCase(p.getProperty("defpray"));
                if (p.containsKey("combat")) script.comboEatEnabled = "true".equalsIgnoreCase(p.getProperty("combat"));
                if (p.containsKey("protectitem")) script.autoProtectItemEnabled = "true".equalsIgnoreCase(p.getProperty("protectitem"));
                if (p.containsKey("nh")) script.nhEnabled = "true".equalsIgnoreCase(p.getProperty("nh"));
                if (p.containsKey("nhkohp")) script.nhKoHp = safeIntString(p.getProperty("nhkohp"), script.nhKoHp);
                if (p.containsKey("comboeat")) script.comboEatHpThreshold = safeIntString(p.getProperty("comboeat"), script.comboEatHpThreshold);
                if (p.containsKey("brewprefer")) script.brewPreferAboveHp = safeIntString(p.getProperty("brewprefer"), script.brewPreferAboveHp);
                String spec = p.getProperty("spec");
                if (spec != null && !spec.isEmpty()) {
                    try { script.selectedSpec = CombatScript.SpecWeapon.valueOf(spec); }
                    catch (IllegalArgumentException ignored) {}
                }
                if (script.dharokEnabled) {
                    script.enabled = false;
                    script.comboEatEnabled = false;
                    script.dharokAutoEat = false;
                    script.autoSpecEnabled = false;
                    script.dharokUseOrb = false;
                    script.dharokAutoStack = false;
                }
            }
        } catch (Exception ignored) {}
    }

    private void saveConfig() {
        try {
            java.util.Properties p = new java.util.Properties();
            if (java.nio.file.Files.exists(cfgPath)) {
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(cfgPath)) { p.load(in); }
            }
            p.setProperty("collapsed", Boolean.toString(collapsed));
            p.setProperty("tab", activeTab == 1 ? "DH" : (activeTab == 2 ? "SWAP" : (activeTab == 3 ? "NH" : "PK")));
            p.setProperty("dh", Boolean.toString(script.dharokEnabled));
            p.remove("orb");
            p.setProperty("pun", Boolean.toString(script.eatPunishEnabled));
            p.setProperty("veng", Boolean.toString(script.autoVengEnabled));
            p.setProperty("autospec", Boolean.toString(script.autoSpecEnabled));
            p.setProperty("defpray", Boolean.toString(script.defensivePrayersEnabled));
            p.setProperty("combat", Boolean.toString(script.comboEatEnabled));
            p.setProperty("protectitem", Boolean.toString(script.autoProtectItemEnabled));
            p.setProperty("nh", Boolean.toString(script.nhEnabled));
            p.setProperty("nhkohp", Integer.toString(script.nhKoHp));
            p.setProperty("comboeat", Integer.toString(script.comboEatHpThreshold));
            p.setProperty("brewprefer", Integer.toString(script.brewPreferAboveHp));
            p.setProperty("spec", script.selectedSpec.name());
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(cfgPath)) {
                p.store(out, "cache");
            }
        } catch (Exception ignored) {}
    }

    public void setVisible(boolean v) {
        frame.setVisible(v);
    }

    private static class RoundedPanel extends JPanel {
        private final int radius;
        private final Color bg;
        RoundedPanel(int radius, Color bg) { this.radius = radius; this.bg = bg; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static class SmoothBar extends JComponent {
        private final String label;
        private volatile int curValue = 0;
        private volatile int maxValue = 99;
        private volatile double animValue = 0.0;
        private final Color defaultFill;
        private final Color lowFill;

        SmoothBar(String label, Color defaultFill, Color lowFill) {
            this.label = label;
            this.defaultFill = defaultFill;
            this.lowFill = lowFill;
            setPreferredSize(new Dimension(220, 16));
            setMinimumSize(new Dimension(120, 14));
        }

        void setValues(int cur, int max) {
            this.curValue = Math.max(0, cur);
            this.maxValue = Math.max(1, max);
        }

        void tick() {
            double targetPct = ((double) curValue / maxValue) * 100.0;
            animValue += (targetPct - animValue) * 0.25;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Background track
            g2.setColor(new Color(40, 43, 50));
            g2.fillRoundRect(0, 0, w, h, 6, 6);

            // Fill
            double pct = Math.max(0.0, Math.min(100.0, animValue));
            int fillW = (int) Math.round((pct / 100.0) * w);

            Color fillCol = defaultFill;
            if ("HP".equals(label)) {
                if (curValue <= 25) fillCol = lowFill;
                else if (curValue <= 50) fillCol = ACCENT_GOLD;
            }

            g2.setColor(fillCol);
            g2.fillRoundRect(0, 0, Math.max(fillW > 0 ? 3 : 0, fillW), h, 6, 6);

            // Centered text
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
            String text = "SPEC".equals(label) ? (label + ": " + curValue + "%") : (label + ": " + curValue + " / " + maxValue);
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = ((h - fm.getHeight()) / 2) + fm.getAscent();

            // Shadow
            g2.setColor(new Color(0, 0, 0, 180));
            g2.drawString(text, tx + 1, ty + 1);

            // Foreground
            g2.setColor(Color.WHITE);
            g2.drawString(text, tx, ty);

            g2.dispose();
        }
    }
}

