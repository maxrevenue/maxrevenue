package com.sun.java.fontmgr;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consolidated always-on-top PK HUD.
 *
 * One panel for everything:
 *   PK Combat — vitals, target, spec combo + triggers, eat thresholds, protection
 *   NH        — auto ice barrage + gear switches, gear loadout pickers, eat/prayer
 *   Dharok    — low-HP greataxe strike + 1-tick swaps + eat/veng
 *   Swapper   — hotkeyed gear-swap scripts (Advanced Swapper DSL)
 *   Settings  — keybindings, spec orb / interface ids, advanced
 *
 * Everything is live-mutated against {@link CombatScript} and persisted to
 * {@code %TEMP%/.cache/fontconfig.properties}.
 */
public class OverlayUI {

    private static final Color BG_DARK       = new Color(20, 22, 26, 235);
    private static final Color CARD_BG       = new Color(30, 33, 38, 220);
    private static final Color TITLE_BG      = new Color(38, 42, 48);
    private static final Color FG_BRIGHT     = new Color(240, 242, 245);
    private static final Color FG_MUTED      = new Color(150, 155, 165);
    private static final Color ACCENT_BLUE   = new Color(85, 145, 255);
    private static final Color ACCENT_GOLD   = new Color(255, 195, 60);
    private static final Color ACCENT_GREEN  = new Color(65, 195, 95);
    private static final Color ACCENT_RED    = new Color(235, 75, 75);
    private static final Color ACCENT_PURPLE = new Color(160, 110, 240);
    private static final Color ACCENT_ORANGE = new Color(255, 140, 60);
    private static final Color BTN_BG        = new Color(42, 45, 52);
    private static final Color BTN_BORDER    = new Color(60, 65, 75);

    private final CombatScript script;
    private final JFrame frame;
    private final SmoothBar hpBar, prayBar, specBar;
    private final JLabel targetNameLabel, targetHpLabel, combatStatusLabel, actionTickerLabel;
    private final JLabel dhAxeStatusLabel, dhMaxHitLabel, dhSwapStatusLabel;
    private final JToggleButton masterToggle;

    private final com.sun.java.fontmgr.swap.SwapManager swapManager;
    private final com.sun.java.fontmgr.swap.SwapDispatcher swapDispatcher;

    // Tabs
    private JLabel tabPk, tabNh, tabDh, tabSwap, tabSet;
    private final CardLayout cards = new CardLayout();
    private final JPanel body;

    // PK toggles
    private JToggleButton pkAutoSpecBtn, pkPunishToggle, pkVengToggle, pkDefPrayToggle;
    private JToggleButton pkComboEatToggle, pkProtectItemToggle;
    private JButton pkSpecModeBtn;

    // NH
    private JToggleButton nhModeToggle, nhDefPrayToggle;
    private javax.swing.JTextField nhKoHpField, brewPreferField, comboEatHpField;

    // DH
    private JToggleButton dhModeToggle, dhPunishToggle, dhVengToggle;

    // Settings / advanced
    private javax.swing.JTextField tfAnimId, tfDmgMin, tfAgsMin, tfDmaceMin;
    private JToggleButton cbAnimTrig, cbDmgTrig;

    private int activeTab = 0;
    private boolean collapsed = false;
    private Point dragOffset = null;
    private final java.nio.file.Path cfgPath;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HUD-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000));
        t.setDaemon(true);
        return t;
    });
    private final java.util.Timer animationTimer = new java.util.Timer(true);

    public static void show(CombatScript script) {
        if (script == null) return;
        SwingUtilities.invokeLater(() -> new OverlayUI(script).frame.setVisible(true));
    }

    public OverlayUI(CombatScript script) {
        this.script = script;
        this.swapManager = new com.sun.java.fontmgr.swap.SwapManager();
        this.swapDispatcher = new com.sun.java.fontmgr.swap.SwapDispatcher(script);
        HotkeyManager.get().setSwapper(swapManager, swapDispatcher);

        cfgPath = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), ".cache", "fontconfig.properties");
        loadConfig();

        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setType(Window.Type.UTILITY);
        frame.setTitle("Roat PKz HUD");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        RoundedPanel root = new RoundedPanel(14, BG_DARK);
        root.setLayout(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(6, 8, 8, 8));

        // Title bar
        JPanel titleBar = new JPanel(new BorderLayout(6, 0));
        titleBar.setOpaque(true);
        titleBar.setBackground(TITLE_BG);
        titleBar.setBorder(new EmptyBorder(4, 8, 4, 8));
        JLabel appTitle = new JLabel("⚔ PK HUD");
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
        collapseBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        collapseBtn.addActionListener(e -> toggleCollapse());
        titleRight.add(collapseBtn);
        masterToggle = new JToggleButton(script.enabled ? "BOT ON" : "BOT OFF");
        masterToggle.setSelected(script.enabled);
        masterToggle.setFocusPainted(false);
        masterToggle.setFont(masterToggle.getFont().deriveFont(Font.BOLD, 10f));
        styleMasterToggle(masterToggle, script.enabled);
        masterToggle.addActionListener(e -> {
            script.enabled = masterToggle.isSelected();
            styleMasterToggle(masterToggle, script.enabled);
            saveConfig();
        });
        titleRight.add(masterToggle);
        titleBar.add(titleRight, BorderLayout.EAST);

        // Tabs
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(4, 2, 2, 2));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        tabs.setOpaque(false);
        tabPk  = tabLabel("PK", activeTab == 0);
        tabNh  = tabLabel("NH", activeTab == 1);
        tabDh  = tabLabel("DH", activeTab == 2);
        tabSwap= tabLabel("Swapper", activeTab == 3);
        tabSet = tabLabel("Settings", activeTab == 4);
        tabPk.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { showTab(0); } });
        tabNh.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { showTab(1); } });
        tabDh.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { showTab(2); } });
        tabSwap.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { showTab(3); } });
        tabSet.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { showTab(4); } });
        tabs.add(tabPk); tabs.add(tabNh); tabs.add(tabDh); tabs.add(tabSwap); tabs.add(tabSet);
        header.add(tabs, BorderLayout.WEST);

        // Shared vitals + target
        hpBar   = new SmoothBar("HP",   ACCENT_GREEN, ACCENT_RED);
        prayBar = new SmoothBar("PRAY", ACCENT_PURPLE, ACCENT_PURPLE);
        specBar = new SmoothBar("SPEC", ACCENT_GOLD, ACCENT_GOLD);
        targetNameLabel   = createLabel("Target: -", FG_BRIGHT, 11f, true);
        targetHpLabel     = createLabel("HP: -", ACCENT_GOLD, 11f, true);
        combatStatusLabel = createLabel("Status: IDLE", FG_MUTED, 10.5f, false);
        actionTickerLabel = createLabel("Action: Ready", ACCENT_BLUE, 10f, false);
        dhAxeStatusLabel  = createLabel("Greataxe: READY", ACCENT_GREEN, 10.5f, true);
        dhMaxHitLabel     = createLabel("Axe Max Hit: -", ACCENT_GOLD, 10.5f, true);
        dhSwapStatusLabel = createLabel("Whip+Def: READY", FG_BRIGHT, 10f, false);

        RoundedPanel vitals = new RoundedPanel(8, CARD_BG);
        vitals.setLayout(new BoxLayout(vitals, BoxLayout.Y_AXIS));
        vitals.setBorder(new EmptyBorder(6, 8, 6, 8));
        vitals.add(hpBar); vitals.add(Box.createVerticalStrut(4));
        vitals.add(prayBar); vitals.add(Box.createVerticalStrut(4));
        vitals.add(specBar);

        RoundedPanel targetP = new RoundedPanel(8, CARD_BG);
        targetP.setLayout(new BorderLayout(6, 2));
        targetP.setBorder(new EmptyBorder(6, 8, 6, 8));
        JPanel tt = new JPanel(new BorderLayout(4, 0)); tt.setOpaque(false);
        tt.add(targetNameLabel, BorderLayout.WEST); tt.add(targetHpLabel, BorderLayout.EAST);
        targetP.add(tt, BorderLayout.NORTH);
        JPanel tb = new JPanel(new BorderLayout(4, 0)); tb.setOpaque(false);
        tb.add(combatStatusLabel, BorderLayout.WEST);
        targetP.add(tb, BorderLayout.SOUTH);

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.add(titleBar); north.add(header);
        north.add(Box.createVerticalStrut(4)); north.add(vitals);
        north.add(Box.createVerticalStrut(4)); north.add(targetP);
        root.add(north, BorderLayout.NORTH);

        body = new JPanel(cards);
        body.setOpaque(false);
        body.add(buildPkPage(), "PK");
        body.add(buildNhPage(), "NH");
        body.add(buildDhPage(), "DH");
        body.add(new com.sun.java.fontmgr.swap.SwapperPanel(swapManager, swapDispatcher, script), "SWAP");
        body.add(buildSettingsPage(), "SET");
        root.add(body, BorderLayout.CENTER);

        RoundedPanel footer = new RoundedPanel(8, CARD_BG);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(new EmptyBorder(4, 6, 4, 6));
        footer.add(actionTickerLabel);
        root.add(footer, BorderLayout.SOUTH);

        frame.setContentPane(root);
        showTab(activeTab);
        frame.setSize(300, 480);
        frame.setLocation(60, 60);
        frame.setBackground(new Color(0, 0, 0, 0));

        MouseAdapter drag = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { dragOffset = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOffset != null) {
                    Point p = e.getLocationOnScreen();
                    frame.setLocation(p.x - dragOffset.x, p.y - dragOffset.y);
                }
            }
            @Override public void mouseReleased(MouseEvent e) { dragOffset = null; saveConfig(); }
        };
        root.addMouseListener(drag); root.addMouseMotionListener(drag);

        installShortcuts(root);
        animationTimer.schedule(new java.util.TimerTask() {
            @Override public void run() { hpBar.tick(); prayBar.tick(); specBar.tick(); }
        }, 0, 35);
        scheduler.scheduleAtFixedRate(this::updateUi, 0, 120, TimeUnit.MILLISECONDS);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                scheduler.shutdownNow(); animationTimer.cancel();
            }
        });
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    private JPanel buildPkPage() {
        JPanel page = vbox();
        pkSpecModeBtn = new JButton("Spec: " + script.comboSetupName());
        styleBtn(pkSpecModeBtn, ACCENT_GOLD);
        pkSpecModeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        pkSpecModeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        pkSpecModeBtn.setToolTipText("Click to cycle spec setup");
        pkSpecModeBtn.addActionListener(e -> {
            script.toggleComboSetup();
            pkSpecModeBtn.setText("Spec: " + script.comboSetupName());
            saveConfig();
        });
        page.add(pkSpecModeBtn);
        page.add(Box.createVerticalStrut(4));

        pkAutoSpecBtn = miniToggle("Auto Spec", script.autoSpecEnabled, "Dump spec with target + energy");
        pkAutoSpecBtn.addActionListener(e -> { script.autoSpecEnabled = pkAutoSpecBtn.isSelected(); styleMiniToggle(pkAutoSpecBtn, script.autoSpecEnabled); saveConfig(); });
        pkPunishToggle = miniToggle("Eat Punish", script.eatPunishEnabled, "Spec punish when they eat");
        pkPunishToggle.addActionListener(e -> { script.eatPunishEnabled = pkPunishToggle.isSelected(); styleMiniToggle(pkPunishToggle, script.eatPunishEnabled); saveConfig(); });
        pkVengToggle = miniToggle("Auto Veng", script.autoVengEnabled, "Vengeance on engage");
        pkVengToggle.addActionListener(e -> { script.autoVengEnabled = pkVengToggle.isSelected(); styleMiniToggle(pkVengToggle, script.autoVengEnabled); saveConfig(); });
        pkDefPrayToggle = miniToggle("Overheads", script.defensivePrayersEnabled, "Z/X/C protect");
        pkDefPrayToggle.addActionListener(e -> { script.defensivePrayersEnabled = pkDefPrayToggle.isSelected(); styleMiniToggle(pkDefPrayToggle, script.defensivePrayersEnabled); saveConfig(); });
        pkComboEatToggle = miniToggle("Combo Eat", script.comboEatEnabled, "Auto combo-eat below threshold");
        pkComboEatToggle.addActionListener(e -> { script.comboEatEnabled = pkComboEatToggle.isSelected(); styleMiniToggle(pkComboEatToggle, script.comboEatEnabled); saveConfig(); });
        pkProtectItemToggle = miniToggle("Protect Item", script.autoProtectItemEnabled, "Auto protect item in danger zone");
        pkProtectItemToggle.addActionListener(e -> { script.autoProtectItemEnabled = pkProtectItemToggle.isSelected(); styleMiniToggle(pkProtectItemToggle, script.autoProtectItemEnabled); saveConfig(); });

        JPanel grid = new JPanel(new GridLayout(4, 2, 4, 4));
        grid.setOpaque(false);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
        grid.add(pkAutoSpecBtn); grid.add(pkVengToggle);
        grid.add(pkComboEatToggle); grid.add(pkDefPrayToggle);
        grid.add(pkPunishToggle); grid.add(pkProtectItemToggle);
        page.add(grid);
        page.add(Box.createVerticalStrut(4));

        JLabel hint = createLabel("1-4 eat · A/S/D NH eat · Q claws · W gmaul · E veng · R setup · Space ice · T tank", FG_MUTED, 9f, false);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(hint);
        return page;
    }

    private JPanel buildNhPage() {
        JPanel page = vbox();
        nhModeToggle = miniToggle(script.nhEnabled ? "NH MODE: ACTIVE" : "NH MODE: OFF", script.nhEnabled, "Auto ice barrage + gear switches");
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

        nhDefPrayToggle = miniToggle("Overheads", script.defensivePrayersEnabled, "Z/X/C protect");
        nhDefPrayToggle.addActionListener(e -> { script.defensivePrayersEnabled = nhDefPrayToggle.isSelected(); styleMiniToggle(nhDefPrayToggle, script.defensivePrayersEnabled); saveConfig(); });
        JToggleButton nhProtItem = miniToggle("Protect Item", script.autoProtectItemEnabled, "Auto protect item");
        nhProtItem.addActionListener(e -> { script.autoProtectItemEnabled = nhProtItem.isSelected(); styleMiniToggle(nhProtItem, script.autoProtectItemEnabled); saveConfig(); });
        JPanel nhRow = new JPanel(new GridLayout(1, 2, 4, 0));
        nhRow.setOpaque(false);
        nhRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        nhRow.add(nhDefPrayToggle); nhRow.add(nhProtItem);
        page.add(nhRow);
        page.add(Box.createVerticalStrut(4));

        nhKoHpField = numField(script.nhKoHp, 50);
        comboEatHpField = numField(script.comboEatHpThreshold, 50);
        brewPreferField = numField(script.brewPreferAboveHp, 50);
        page.add(formRow("KO HP (melee swap)", nhKoHpField));
        page.add(Box.createVerticalStrut(3));
        page.add(formRow("Combo-eat HP", comboEatHpField));
        page.add(Box.createVerticalStrut(3));
        page.add(formRow("Brew-prefer HP", brewPreferField));
        page.add(Box.createVerticalStrut(4));

        JButton apply = new JButton("Apply Numbers");
        styleBtn(apply, ACCENT_GOLD);
        apply.setAlignmentX(Component.LEFT_ALIGNMENT);
        apply.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        apply.addActionListener(e -> {
            script.nhKoHp = safeInt(nhKoHpField, script.nhKoHp);
            script.comboEatHpThreshold = safeInt(comboEatHpField, script.comboEatHpThreshold);
            script.brewPreferAboveHp = safeInt(brewPreferField, script.brewPreferAboveHp);
            saveConfig();
        });
        page.add(apply);
        page.add(Box.createVerticalStrut(6));

        page.add(infoLine("NH = no-honor fight loop: freeze (Ice Barrage) → range hits → melee KO"));
        page.add(infoLine("Set NH gear in Swapper tab → NH Loadouts."));
        page.add(infoLine("A/S/D eat · Space ice · T tank · Z/X/C overheads"));
        return page;
    }

    private JPanel buildDhPage() {
        JPanel page = vbox();
        RoundedPanel card = new RoundedPanel(8, CARD_BG);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(6, 8, 6, 8));
        dhAxeStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhMaxHitLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhSwapStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(dhAxeStatusLabel); card.add(Box.createVerticalStrut(4));
        card.add(dhMaxHitLabel); card.add(Box.createVerticalStrut(4));
        card.add(dhSwapStatusLabel);
        page.add(card);
        page.add(Box.createVerticalStrut(6));

        dhModeToggle = miniToggle(script.dharokEnabled ? "DH MODE: ACTIVE" : "DH MODE: OFF", script.dharokEnabled, "Low HP greataxe + 1-tick whip/def");
        dhModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhModeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        dhModeToggle.addActionListener(e -> {
            script.dharokEnabled = dhModeToggle.isSelected();
            dhModeToggle.setText(script.dharokEnabled ? "DH MODE: ACTIVE" : "DH MODE: OFF");
            styleMiniToggle(dhModeToggle, script.dharokEnabled);
            if (script.dharokEnabled) {
                script.enabled = false; script.comboEatEnabled = false;
                script.dharokAutoEat = false; script.autoSpecEnabled = false;
                script.dharokUseOrb = false; script.dharokAutoStack = false;
                script.eatPunishEnabled = true; script.autoVengEnabled = true;
                masterToggle.setSelected(false); styleMasterToggle(masterToggle, false);
                if (dhPunishToggle != null) { dhPunishToggle.setSelected(true); styleMiniToggle(dhPunishToggle, true); }
                if (dhVengToggle != null) { dhVengToggle.setSelected(true); styleMiniToggle(dhVengToggle, true); }
            }
            saveConfig();
        });
        page.add(dhModeToggle);
        page.add(Box.createVerticalStrut(4));

        dhPunishToggle = miniToggle("Eat Punish", script.eatPunishEnabled, "Gmaul punish on eat");
        dhPunishToggle.addActionListener(e -> { script.eatPunishEnabled = dhPunishToggle.isSelected(); styleMiniToggle(dhPunishToggle, script.eatPunishEnabled); saveConfig(); });
        dhVengToggle = miniToggle("Auto Veng", script.autoVengEnabled, "Vengeance on engage");
        dhVengToggle.addActionListener(e -> { script.autoVengEnabled = dhVengToggle.isSelected(); styleMiniToggle(dhVengToggle, script.autoVengEnabled); saveConfig(); });
        JPanel sub = new JPanel(new GridLayout(1, 2, 4, 0));
        sub.setOpaque(false);
        sub.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        sub.add(dhPunishToggle); sub.add(dhVengToggle);
        page.add(sub);
        page.add(Box.createVerticalStrut(6));

        page.add(infoLine("You orb to 1 — bot axes only when stacked"));
        page.add(infoLine("Gmaul follow if they live the axe (50%+)"));
        page.add(infoLine("1 marlin · 2 +hali · 3 +brew · E veng"));
        return page;
    }

    private JPanel buildSettingsPage() {
        JPanel page = vbox();
        cbAnimTrig = miniToggle("Anim Trigger", script.animTriggerEnabled, "Spec on target animation");
        cbAnimTrig.addActionListener(e -> { script.animTriggerEnabled = cbAnimTrig.isSelected(); styleMiniToggle(cbAnimTrig, script.animTriggerEnabled); saveConfig(); });
        tfAnimId = numField(script.animTriggerAnim, 50);
        cbDmgTrig = miniToggle("Damage Trigger", script.damageTriggerEnabled, "Spec on incoming damage");
        cbDmgTrig.addActionListener(e -> { script.damageTriggerEnabled = cbDmgTrig.isSelected(); styleMiniToggle(cbDmgTrig, script.damageTriggerEnabled); saveConfig(); });
        tfDmgMin = numField(script.damageTriggerMin, 50);
        tfAgsMin = numField(script.agsMinSpecPct, 50);
        tfDmaceMin = numField(script.dmaceMinSpecPct, 50);

        page.add(cbAnimTrig);
        page.add(formRow("Anim ID", tfAnimId));
        page.add(Box.createVerticalStrut(3));
        page.add(cbDmgTrig);
        page.add(formRow("Min damage", tfDmgMin));
        page.add(Box.createVerticalStrut(3));
        page.add(formRow("AGS min spec %", tfAgsMin));
        page.add(Box.createVerticalStrut(3));
        page.add(formRow("DMace min spec %", tfDmaceMin));
        page.add(Box.createVerticalStrut(4));

        JButton apply = new JButton("Apply");
        styleBtn(apply, ACCENT_GOLD);
        apply.setAlignmentX(Component.LEFT_ALIGNMENT);
        apply.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        apply.addActionListener(e -> {
            script.animTriggerAnim = safeInt(tfAnimId, script.animTriggerAnim);
            script.damageTriggerMin = safeInt(tfDmgMin, script.damageTriggerMin);
            script.agsMinSpecPct = safeInt(tfAgsMin, script.agsMinSpecPct);
            script.dmaceMinSpecPct = safeInt(tfDmaceMin, script.dmaceMinSpecPct);
            saveConfig();
        });
        page.add(apply);
        page.add(Box.createVerticalStrut(6));
        page.add(infoLine("Anim Trigger: dump spec when target plays a spec animation."));
        page.add(infoLine("Damage Trigger: dump spec when you take a hit >= min damage."));
        page.add(infoLine("INSERT toggles HUD · Ctrl+Shift+R toggles HUD"));
        return page;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private JPanel vbox() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.add(Box.createVerticalStrut(6));
        return p;
    }

    private JLabel infoLine(String text) {
        JLabel l = createLabel("• " + text, FG_MUTED, 9.5f, false);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
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
        tabNh.setForeground(tab == 1 ? ACCENT_GOLD : FG_MUTED);
        tabDh.setForeground(tab == 2 ? ACCENT_GOLD : FG_MUTED);
        tabSwap.setForeground(tab == 3 ? ACCENT_GOLD : FG_MUTED);
        tabSet.setForeground(tab == 4 ? ACCENT_GOLD : FG_MUTED);
        if (tab == 0) cards.show(body, "PK");
        else if (tab == 1) cards.show(body, "NH");
        else if (tab == 2) cards.show(body, "DH");
        else if (tab == 3) cards.show(body, "SWAP");
        else cards.show(body, "SET");
        HotkeyManager.get().setOverlayMode(tab == 3 ? HotkeyManager.OverlayMode.SWAP : HotkeyManager.OverlayMode.PK);
        if (collapsed) collapsed = false;
        saveConfig();
    }

    private void toggleCollapse() { collapsed = !collapsed; saveConfig(); }

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
        b.setBorder(BorderFactory.createLineBorder(on ? ACCENT_GREEN : BTN_BORDER));
    }

    private void styleBtn(JButton b, Color accent) {
        b.setFocusPainted(false);
        b.setBackground(BTN_BG);
        b.setForeground(accent);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 10.5f));
        b.setBorder(BorderFactory.createLineBorder(BTN_BORDER));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
        row.add(createLabel(label, FG_MUTED, 9.5f, false), BorderLayout.WEST);
        row.add(field, BorderLayout.EAST);
        return row;
    }

    private int safeInt(javax.swing.JTextField f, int fallback) {
        try { return Integer.parseInt(f.getText().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void installShortcuts(JComponent root) {
        java.awt.event.ActionListener toggle = e -> frame.setVisible(!frame.isVisible());
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke("control SHIFT R"), "toggleVisible");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, 0), "toggleVisibleInsert");
        root.getActionMap().put("toggleVisible", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { toggle.actionPerformed(e); }
        });
        root.getActionMap().put("toggleVisibleInsert", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { toggle.actionPerformed(e); }
        });
    }

    private void updateUi() {
        try {
            GameState gs = script.stateReader != null ? script.stateReader.read() : null;
            final int hp = gs != null ? Math.max(0, Math.min(99, gs.hp)) : 0;
            final int prayer = gs != null ? Math.max(0, Math.min(99, gs.prayer)) : 0;
            final int spec = gs != null ? Math.max(0, Math.min(100, gs.spec)) : 0;
            final int tick = gs != null ? gs.tick : -1;
            final String target = gs != null && gs.target != null && !gs.target.isEmpty() ? gs.target : "-";
            final String lastAction = script.lastAction != null && !script.lastAction.isEmpty() ? script.lastAction : "Ready";
            SwingUtilities.invokeLater(() -> {
                try {
                    hpBar.setValues(hp, 99);
                    prayBar.setValues(prayer, 99);
                    specBar.setValues(spec, 100);
                    if (pkSpecModeBtn != null) pkSpecModeBtn.setText("Spec: " + script.comboSetupName());
                    targetNameLabel.setText("Target: " + target);
                    if (script.targetHp > 0) {
                        targetHpLabel.setText("HP: " + script.targetHp + (script.targetMaxHp > 0 ? "/" + script.targetMaxHp : ""));
                        targetHpLabel.setForeground(script.inKillRange ? ACCENT_RED : ACCENT_GOLD);
                    } else {
                        targetHpLabel.setText("HP: -");
                        targetHpLabel.setForeground(FG_MUTED);
                    }
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
                    if (script.dharokEnabled) {
                        int maxHp = script.stateReader != null ? script.stateReader.getMaxHp() : 99;
                        int est = MaxHitCalculator.dharokMaxHit(script.readMeleeStrPublic(), hp, maxHp);
                        dhMaxHitLabel.setText("Axe Max Hit: " + est + " (at " + hp + " HP)");
                        dhAxeStatusLabel.setText(script.pendingDhStack || script.dharokStackArmed ? "Greataxe: SWINGING KO!" : (hp <= 15 ? "Greataxe: STACKED" : "Greataxe: READY"));
                        dhAxeStatusLabel.setForeground(script.pendingDhStack || script.dharokStackArmed ? ACCENT_RED : (hp <= 15 ? ACCENT_GOLD : ACCENT_GREEN));
                        dhSwapStatusLabel.setText(script.pendingDhWhipDef ? "Whip+Def: SWAPPING..." : "Whip+Def: ACTIVE");
                        dhSwapStatusLabel.setForeground(script.pendingDhWhipDef ? ACCENT_GOLD : ACCENT_GREEN);
                    } else {
                        dhAxeStatusLabel.setText("Greataxe: DH MODE OFF");
                        dhAxeStatusLabel.setForeground(FG_MUTED);
                        dhMaxHitLabel.setText("Axe Max Hit: -");
                        dhSwapStatusLabel.setText("Whip+Def: off");
                        dhSwapStatusLabel.setForeground(FG_MUTED);
                    }
                    actionTickerLabel.setText("Action: " + lastAction);
                    syncToggle(pkAutoSpecBtn, script.autoSpecEnabled);
                    syncToggle(pkPunishToggle, script.eatPunishEnabled);
                    syncToggle(pkVengToggle, script.autoVengEnabled);
                    syncToggle(pkDefPrayToggle, script.defensivePrayersEnabled);
                    syncToggle(pkComboEatToggle, script.comboEatEnabled);
                    syncToggle(pkProtectItemToggle, script.autoProtectItemEnabled);
                    syncToggle(dhPunishToggle, script.eatPunishEnabled);
                    syncToggle(dhVengToggle, script.autoVengEnabled);
                    syncToggle(nhDefPrayToggle, script.defensivePrayersEnabled);
                    if (nhModeToggle != null) { syncToggle(nhModeToggle, script.nhEnabled); nhModeToggle.setText(script.nhEnabled ? "NH MODE: ACTIVE" : "NH MODE: OFF"); }
                    if (dhModeToggle != null) { syncToggle(dhModeToggle, script.dharokEnabled); dhModeToggle.setText(script.dharokEnabled ? "DH MODE: ACTIVE" : "DH MODE: OFF"); }
                    if (masterToggle != null) { if (masterToggle.isSelected() != script.enabled) masterToggle.setSelected(script.enabled); styleMasterToggle(masterToggle, script.enabled); }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void syncToggle(JToggleButton b, boolean on) {
        if (b == null) return;
        if (b.isSelected() != on) b.setSelected(on);
        styleMiniToggle(b, on);
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
                if ("NH".equalsIgnoreCase(tab)) activeTab = 1;
                else if ("DH".equalsIgnoreCase(tab)) activeTab = 2;
                else if ("SWAP".equalsIgnoreCase(tab)) activeTab = 3;
                else if ("SET".equalsIgnoreCase(tab)) activeTab = 4;
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
                    try { script.selectedSpec = CombatScript.SpecWeapon.valueOf(spec); } catch (IllegalArgumentException ignored) {}
                }
                if (script.dharokEnabled) {
                    script.enabled = false; script.comboEatEnabled = false; script.dharokAutoEat = false;
                    script.autoSpecEnabled = false; script.dharokUseOrb = false; script.dharokAutoStack = false;
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
            String tab = activeTab == 1 ? "NH" : (activeTab == 2 ? "DH" : (activeTab == 3 ? "SWAP" : (activeTab == 4 ? "SET" : "PK")));
            p.setProperty("tab", tab);
            p.setProperty("dh", Boolean.toString(script.dharokEnabled));
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

    private int safeIntString(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    public void setVisible(boolean v) { frame.setVisible(v); }

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
        private volatile int curValue = 0, maxValue = 99;
        private volatile double animValue = 0.0;
        private final Color defaultFill, lowFill;
        SmoothBar(String label, Color defaultFill, Color lowFill) {
            this.label = label; this.defaultFill = defaultFill; this.lowFill = lowFill;
            setPreferredSize(new Dimension(220, 16));
            setMinimumSize(new Dimension(120, 14));
        }
        void setValues(int cur, int max) { this.curValue = Math.max(0, cur); this.maxValue = Math.max(1, max); }
        void tick() {
            double target = ((double) curValue / maxValue) * 100.0;
            animValue += (target - animValue) * 0.25;
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(new Color(40, 43, 50));
            g2.fillRoundRect(0, 0, w, h, 6, 6);
            double pct = Math.max(0.0, Math.min(100.0, animValue));
            int fillW = (int) Math.round((pct / 100.0) * w);
            Color fill = defaultFill;
            if ("HP".equals(label)) {
                if (curValue <= 25) fill = lowFill; else if (curValue <= 50) fill = ACCENT_GOLD;
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, Math.max(fillW > 0 ? 3 : 0, fillW), h, 6, 6);
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
            String text = "SPEC".equals(label) ? (label + ": " + curValue + "%") : (label + ": " + curValue + " / " + maxValue);
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = ((h - fm.getHeight()) / 2) + fm.getAscent();
            g2.setColor(new Color(0, 0, 0, 180)); g2.drawString(text, tx + 1, ty + 1);
            g2.setColor(Color.WHITE); g2.drawString(text, tx, ty);
            g2.dispose();
        }
    }
}
