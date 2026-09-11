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
 * Consolidated always-on-top HUD.
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

    //
    // Palette comes from the shared com.sun.java.fontmgr.Theme so the HUD and the
    // launcher cannot drift apart. The HUD floats over the game, so its surfaces
    // are the shared colours with alpha applied here rather than in Theme.
    private static final Color BG_DARK       = Theme.withAlpha(Theme.BG_DARK, 235);
    private static final Color CARD_BG       = Theme.withAlpha(Theme.CARD_BG, 220);
    private static final Color TITLE_BG      = Theme.TITLE_BG;
    private static final Color FG_BRIGHT     = Theme.FG_BRIGHT;
    private static final Color FG_MUTED      = Theme.FG_MUTED;
    private static final Color ACCENT_BLUE   = Theme.ACCENT_BLUE;
    private static final Color ACCENT_GOLD   = Theme.ACCENT_GOLD;
    private static final Color ACCENT_GREEN  = Theme.ACCENT_GREEN;
    private static final Color ACCENT_RED    = Theme.ACCENT_RED;
    private static final Color ACCENT_PURPLE = Theme.ACCENT_PURPLE;
    private static final Color ACCENT_ORANGE = Theme.ACCENT_ORANGE;
    private static final Color BTN_BG        = Theme.BTN_BG;
    private static final Color BTN_BORDER    = Theme.BTN_BORDER;

    private final CombatScript script;
    private final JFrame frame;
    private final SmoothBar hpBar, prayBar, specBar;
    private final JLabel targetNameLabel, targetHpLabel, actionTickerLabel;
    /**
     * Pro-figures strip under the target line: what the opponent is actually
     * holding ({@link OpponentLoadout} from #1), then the decision chips.
     */
    private final JLabel targetWeaponLabel, fightStateLabel, koLabel, specReadyLabel;
    private final JLabel dhAxeStatusLabel, dhMaxHitLabel, dhSwapStatusLabel;
    private final JToggleButton masterToggle;

    private final com.sun.java.fontmgr.swap.SwapManager swapManager;
    private final com.sun.java.fontmgr.swap.SwapDispatcher swapDispatcher;
    private com.sun.java.fontmgr.swap.SwapperPanel swapperPanel;

    // Tabs — built around the Swapper: Swapper (hub) · Fight · DH
    private static final String[] TAB_KEYS   = { "SWAP", "FIGHT", "DH" };
    private static final String[] TAB_NAMES  = { "Swapper", "Fight", "DH" };
    private final JLabel[] tabLabels = new JLabel[TAB_KEYS.length];
    private final CardLayout cards = new CardLayout();
    private final JPanel body;

    // PK toggles
    private JToggleButton pkAutoSpecBtn, pkPunishToggle, pkVengToggle, pkDefPrayToggle;
    private JToggleButton pkGearPrayToggle;
    private JToggleButton pkComboEatToggle, pkProtectItemToggle;
    private JToggleButton pkAutoEatToggle, dhAutoEatToggle;
    private JButton pkSpecModeBtn;
    private JToggleButton staffLcToggle;
    private JLabel iceLcStatusLabel;

    // NH
    private JToggleButton nhModeToggle, nhDefPrayToggle;
    private javax.swing.JTextField nhKoHpField, brewPreferField, comboEatHpField;
    private JLabel nhStatusLabel;

    // DH
    private JToggleButton dhModeToggle, dhPunishToggle, dhVengToggle;

    // Settings / advanced
    private javax.swing.JTextField tfAnimId, tfDmgMin, tfAgsMin, tfDmaceMin;
    private JToggleButton cbAnimTrig, cbDmgTrig;
    private JPanel hkSpecBtn, hkGmaulBtn, hkVengBtn, hkSetupBtn;
    /** Preset buttons, one per {@link Presets.Mode}; restyled each tick. */
    private JButton[] presetBtns;

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
        // Num5 (or the configured key) toggles auto-eat and keeps the HUD + config in sync.
        HotkeyManager.get().setAutoEatListener(this::setAutoEat);

        cfgPath = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), ".cache", "fontconfig.properties");
        loadConfig();

        frame = new JFrame();
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setType(Window.Type.UTILITY);
        frame.setTitle(Product.NAME + " " + Product.VERSION);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        RoundedPanel root = new RoundedPanel(14, BG_DARK);
        root.setLayout(new BorderLayout(6, 6));
        root.setBorder(new EmptyBorder(6, 8, 8, 8));

        // Title bar
        JPanel titleBar = new JPanel(new BorderLayout(6, 0));
        titleBar.setOpaque(true);
        titleBar.setBackground(TITLE_BG);
        titleBar.setBorder(new EmptyBorder(4, 8, 4, 8));
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        brand.setOpaque(false);
        JLabel appTitle = new JLabel(Product.NAME);
        appTitle.setForeground(ACCENT_GOLD);
        appTitle.setFont(appTitle.getFont().deriveFont(Font.BOLD, 11.5f));
        brand.add(appTitle);
        brand.add(createLabel("v" + Product.VERSION, FG_MUTED, 9f, false));
        titleBar.add(brand, BorderLayout.WEST);

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
        masterToggle = new JToggleButton(script.actions().masterEnabled() ? "LIVE" : "ARMED");
        masterToggle.setSelected(script.actions().masterEnabled());
        masterToggle.setFocusPainted(false);
        masterToggle.setFont(masterToggle.getFont().deriveFont(Font.BOLD, 10f));
        styleMasterToggle(masterToggle, script.actions().masterEnabled());
        masterToggle.addActionListener(e -> {
            script.actions().setMasterEnabled(masterToggle.isSelected());
            styleMasterToggle(masterToggle, script.actions().masterEnabled());
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
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int idx = i;
            tabLabels[i] = tabLabel(TAB_NAMES[i], activeTab == idx);
            tabLabels[i].addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { showTab(idx); }
            });
            tabs.add(tabLabels[i]);
        }
        header.add(tabs, BorderLayout.WEST);

        // Shared vitals + target
        hpBar   = new SmoothBar("HP",   ACCENT_GREEN, ACCENT_RED);
        prayBar = new SmoothBar("PRAY", ACCENT_PURPLE, ACCENT_PURPLE);
        specBar = new SmoothBar("SPEC", ACCENT_GOLD, ACCENT_GOLD);
        targetNameLabel   = createLabel("Target: -", FG_BRIGHT, 11f, true);
        targetHpLabel     = createLabel("HP: -", ACCENT_GOLD, 11f, true);
        targetWeaponLabel = createLabel("-", FG_MUTED, 9.5f, false);
        fightStateLabel   = createLabel("Idle", FG_MUTED, 10f, true);
        koLabel           = createLabel("KO range", ACCENT_RED, 10f, true);
        specReadyLabel    = createLabel("Spec ready", ACCENT_GOLD, 10f, true);
        koLabel.setVisible(false);
        specReadyLabel.setVisible(false);
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
        targetP.setLayout(new BoxLayout(targetP, BoxLayout.Y_AXIS));
        targetP.setBorder(new EmptyBorder(6, 8, 6, 8));
        targetP.add(splitRow(targetNameLabel, targetHpLabel));
        targetP.add(Box.createVerticalStrut(1));
        targetP.add(splitRow(targetWeaponLabel, null));
        targetP.add(Box.createVerticalStrut(3));
        targetP.add(chipRow(fightStateLabel, koLabel, specReadyLabel));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.setOpaque(false);
        north.add(titleBar); north.add(header);
        north.add(Box.createVerticalStrut(4)); north.add(vitals);
        north.add(Box.createVerticalStrut(4)); north.add(targetP);
        root.add(north, BorderLayout.NORTH);

        body = new JPanel(cards);
        body.setOpaque(false);
        body.add(buildSwapperHubPage(), "SWAP");
        body.add(buildFightPage(), "FIGHT");
        body.add(buildDhPage(), "DH");
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

    /** Swapper is the hub — everything for NH pking is driven from here. */
    private JPanel buildSwapperHubPage() {
        JPanel page = vbox();
        JLabel t = createLabel("Swapper Hub", ACCENT_GOLD, 12f, true);
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(t);
        page.add(Box.createVerticalStrut(2));
        JLabel sub = createLabel("Make swaps, snapshot NH gear, bind hotkeys. Fight toggles on the Fight tab.",
                FG_MUTED, 9.5f, false);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(sub);
        page.add(Box.createVerticalStrut(4));
        swapperPanel = new com.sun.java.fontmgr.swap.SwapperPanel(swapManager, swapDispatcher, script);
        HotkeyManager.get().setSwapFlush(swapperPanel::flushEditorIfEditing);
        page.add(swapperPanel);
        return page;
    }

    /** Fight = the few combat switches you actually use. */
    private JPanel buildFightPage() {
        JPanel page = vbox();
        page.add(buildPresetRow());
        page.add(Box.createVerticalStrut(4));
        page.add(buildPkPage());
        page.add(Box.createVerticalStrut(3));

        staffLcToggle = miniToggle("Staff = L-Click Barrage", script.actions().staffLeftClickCast(),
                "While a mage staff/wand/Blue moon spear is equipped, Ice Barrage stays left-click armed");
        staffLcToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        staffLcToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        staffLcToggle.addActionListener(e -> {
            script.actions().setStaffLeftClickCast(staffLcToggle.isSelected());
            styleMiniToggle(staffLcToggle, script.actions().staffLeftClickCast());
            saveConfig();
        });
        page.add(staffLcToggle);
        iceLcStatusLabel = createLabel("Ice LC: —", FG_MUTED, 9.5f, false);
        iceLcStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(iceLcStatusLabel);
        JButton pinMageBtn = new JButton("Pin current weapon as Ice staff");
        styleBtn(pinMageBtn, ACCENT_GOLD);
        pinMageBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        pinMageBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        pinMageBtn.setToolTipText("Wield Blue moon spear, then click — fixes Roat custom item ids");
        pinMageBtn.addActionListener(e -> {
            script.learnCurrentWeaponAsMagePublic();
            if (iceLcStatusLabel != null) iceLcStatusLabel.setText(script.iceLcStatusPublic());
        });
        page.add(pinMageBtn);
        page.add(Box.createVerticalStrut(3));
        page.add(stepper("Auto-spec on your hit ≥ (dmg)", script.actions().damageTriggerMin(), 1, 99, 5,
                v -> { script.actions().setDamageTriggerMin(v); saveConfig(); }));
        page.add(Box.createVerticalStrut(3));
        return page;
    }

    /**
     * Quiz-free starting points (#4). Three buttons that flip existing toggles;
     * the one whose flags still all hold is highlighted, so the row never claims a
     * preset is active after the user has changed something.
     */
    private JPanel buildPresetRow() {
        JPanel wrap = new JPanel();
        wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
        wrap.setOpaque(false);
        wrap.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = createLabel("Preset", FG_BRIGHT, 10.5f, true);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(title);

        Presets.Mode[] modes = Presets.Mode.values();
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        presetBtns = new JButton[modes.length];
        for (int i = 0; i < modes.length; i++) {
            final Presets.Mode mode = modes[i];
            JButton b = new JButton(mode.label);
            b.setFocusPainted(false);
            b.setFont(b.getFont().deriveFont(Font.BOLD, 10f));
            b.setToolTipText(mode.hint + " — sets the toggles below; change anything and it un-highlights");
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> {
                Presets.apply(mode, script.actions());
                saveConfig();
                stylePresetBtns();
            });
            presetBtns[i] = b;
            row.add(b);
        }
        wrap.add(row);

        JLabel hint = createLabel("Sets the toggles below. Your own settings are kept until you click one.",
                FG_MUTED, 9f, false);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrap.add(hint);

        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, wrap.getPreferredSize().height));
        stylePresetBtns();
        return wrap;
    }

    /** Highlights the preset whose flags currently all hold; none are highlighted by default. */
    private void stylePresetBtns() {
        if (presetBtns == null) return;
        Presets.Mode activePreset = Presets.active(script.actions());
        Presets.Mode[] modes = Presets.Mode.values();
        for (int i = 0; i < presetBtns.length && i < modes.length; i++) {
            JButton b = presetBtns[i];
            if (b == null) continue;
            boolean on = modes[i] == activePreset;
            b.setBackground(on ? ACCENT_GOLD : BTN_BG);
            b.setForeground(on ? Theme.ON_ACCENT : FG_MUTED);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(on ? ACCENT_GOLD : BTN_BORDER),
                    new EmptyBorder(4, 9, 4, 9)));
        }
    }

    private JPanel buildPkPage() {
        JPanel page = vbox();
        pkSpecModeBtn = new JButton("Spec: " + script.actions().comboSetupName());
        styleBtn(pkSpecModeBtn, ACCENT_GOLD);
        pkSpecModeBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        pkSpecModeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        pkSpecModeBtn.setToolTipText("Click to cycle spec setup");
        pkSpecModeBtn.addActionListener(e -> {
            script.actions().toggleComboSetup();
            pkSpecModeBtn.setText("Spec: " + script.actions().comboSetupName());
            saveConfig();
        });
        page.add(pkSpecModeBtn);
        page.add(Box.createVerticalStrut(4));

        pkAutoSpecBtn = miniToggle("Auto Spec", script.actions().autoSpecEnabled(),
                "OFF recommended for NH — auto AGS was yanking your Blue moon spear. Dump with Spec hotkey (R) instead");
        pkAutoSpecBtn.addActionListener(e -> { script.actions().setAutoSpec(pkAutoSpecBtn.isSelected()); styleMiniToggle(pkAutoSpecBtn, script.actions().autoSpecEnabled()); saveConfig(); });
        pkPunishToggle = miniToggle("Eat Punish", script.actions().eatPunishEnabled(), "Spec punish when they eat");
        pkPunishToggle.addActionListener(e -> { script.actions().setEatPunish(pkPunishToggle.isSelected()); styleMiniToggle(pkPunishToggle, script.actions().eatPunishEnabled()); saveConfig(); });
        pkVengToggle = miniToggle("Auto Veng", script.actions().autoVengEnabled(), "Vengeance on engage");
        pkVengToggle.addActionListener(e -> { script.actions().setAutoVeng(pkVengToggle.isSelected()); styleMiniToggle(pkVengToggle, script.actions().autoVengEnabled()); saveConfig(); });
        pkDefPrayToggle = miniToggle("Overheads", script.actions().defensivePrayersEnabled(), "Z/X/C protect");
        pkDefPrayToggle.addActionListener(e -> { script.actions().setDefensivePrayers(pkDefPrayToggle.isSelected()); styleMiniToggle(pkDefPrayToggle, script.actions().defensivePrayersEnabled()); saveConfig(); });
        pkGearPrayToggle = miniToggle("Fast Overheads", script.actions().gearCorroboratedDefPrayer(), "Trust a weapon switch the tick their armour also changes (skips the 2-tick wait). Off = old 2-tick behaviour");
        pkGearPrayToggle.addActionListener(e -> { script.actions().setGearCorroboratedDefPrayer(pkGearPrayToggle.isSelected()); styleMiniToggle(pkGearPrayToggle, script.actions().gearCorroboratedDefPrayer()); saveConfig(); });
        pkComboEatToggle = miniToggle("Combo Eat", script.actions().comboEatEnabled(), "Auto combo-eat below threshold");
        pkComboEatToggle.addActionListener(e -> { script.actions().setComboEat(pkComboEatToggle.isSelected()); styleMiniToggle(pkComboEatToggle, script.actions().comboEatEnabled()); saveConfig(); });
        pkAutoEatToggle = miniToggle("Auto Eat", script.actions().autoEatEnabled(), "Master switch for ALL automatic eating (Num5). Off = only your 1-4 keys eat");
        pkAutoEatToggle.addActionListener(e -> { setAutoEat(pkAutoEatToggle.isSelected()); });
        pkProtectItemToggle = miniToggle("Protect Item", script.actions().protectItemEnabled(), "Auto protect item in danger zone");
        pkProtectItemToggle.addActionListener(e -> { script.actions().setProtectItem(pkProtectItemToggle.isSelected()); styleMiniToggle(pkProtectItemToggle, script.actions().protectItemEnabled()); saveConfig(); });

        JPanel grid = new JPanel(new GridLayout(4, 2, 4, 4));
        grid.setOpaque(false);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 88));
        grid.add(pkAutoSpecBtn); grid.add(pkVengToggle);
        grid.add(pkComboEatToggle); grid.add(pkAutoEatToggle);
        grid.add(pkDefPrayToggle); grid.add(pkProtectItemToggle);
        grid.add(pkPunishToggle); grid.add(pkGearPrayToggle);
        page.add(grid);
        page.add(Box.createVerticalStrut(4));

        JLabel hint = createLabel("1-4 eat · R spec · G gmaul · V veng · F cycle setup · Space ice · Num9 pray · Num5 auto-eat · Z/X/C overheads · QWE = your swaps", FG_MUTED, 9f, false);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(hint);
        return page;
    }

    private JPanel buildNhPage() {
        JPanel page = vbox();
        
        // NH V2 — auto barrage, prayers, walk-under
        JLabel nhv2Title = createLabel("NH V2", ACCENT_GOLD, 12f, true);
        nhv2Title.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(nhv2Title);
        page.add(Box.createVerticalStrut(6));
        
        // Main NH V2 toggle
        nhModeToggle = miniToggle(script.actions().nhV2Enabled() ? "NH V2: ACTIVE" : "NH V2: OFF", script.actions().nhV2Enabled(), "New reliable NH system");
        nhModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        nhModeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        nhModeToggle.addActionListener(e -> {
            script.actions().toggleNhV2();
            nhModeToggle.setSelected(script.actions().nhV2Enabled());
            nhModeToggle.setText(script.actions().nhV2Enabled() ? "NH V2: ACTIVE" : "NH V2: OFF");
            styleMiniToggle(nhModeToggle, script.actions().nhV2Enabled());
            saveConfig();
        });
        page.add(nhModeToggle);
        page.add(Box.createVerticalStrut(4));
        
        // Status display
        nhStatusLabel = createLabel("Status: " + script.actions().nhV2Status(), ACCENT_BLUE, 10.5f, false);
        nhStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.add(nhStatusLabel);
        page.add(Box.createVerticalStrut(6));
        
        // Feature toggles
        JToggleButton autoPrayerToggle = miniToggle("Auto Prayer", script.actions().nhAutoPrayerEnabled(), "Smart prayer switching");
        autoPrayerToggle.addActionListener(e -> {
            script.actions().toggleNhAutoPrayer();
            styleMiniToggle(autoPrayerToggle, script.actions().nhAutoPrayerEnabled());
            saveConfig();
        });
        
        JToggleButton autoBarrageToggle = miniToggle("Auto Barrage", script.actions().nhAutoBarrageEnabled(), "Automatic ice barrage casting");
        autoBarrageToggle.addActionListener(e -> {
            script.actions().toggleNhAutoBarrage();
            styleMiniToggle(autoBarrageToggle, script.actions().nhAutoBarrageEnabled());
            saveConfig();
        });
        
        JPanel featureRow = new JPanel(new GridLayout(1, 2, 4, 0));
        featureRow.setOpaque(false);
        featureRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        featureRow.add(autoPrayerToggle);
        featureRow.add(autoBarrageToggle);
        page.add(featureRow);
        page.add(Box.createVerticalStrut(4));
        
        // Auto walk-under toggle
        JToggleButton walkUnderToggle = miniToggle("Auto Walk-Under", script.actions().nhAutoWalkUnderEnabled(), "Smart walk-under positioning");
        walkUnderToggle.addActionListener(e -> {
            script.actions().toggleNhAutoWalkUnder();
            styleMiniToggle(walkUnderToggle, script.actions().nhAutoWalkUnderEnabled());
            saveConfig();
        });
        
        JToggleButton protItemToggle = miniToggle("Protect Item", script.actions().protectItemEnabled(), "Auto protect item in PvP");
        protItemToggle.addActionListener(e -> {
            script.actions().setProtectItem(protItemToggle.isSelected());
            styleMiniToggle(protItemToggle, script.actions().protectItemEnabled());
            saveConfig();
        });
        
        JPanel utilRow = new JPanel(new GridLayout(1, 2, 4, 0));
        utilRow.setOpaque(false);
        utilRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        utilRow.add(walkUnderToggle);
        utilRow.add(protItemToggle);
        page.add(utilRow);
        page.add(Box.createVerticalStrut(4));

        // Staff = left-click Ice Barrage (never staff-bash while a staff is on).
        JToggleButton staffLcToggle = miniToggle("Staff = L-Click Barrage", script.actions().staffLeftClickCast(),
                "While a staff/wand is equipped (by item id), Ice Barrage stays left-click armed");
        staffLcToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        staffLcToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        staffLcToggle.addActionListener(e -> {
            script.actions().setStaffLeftClickCast(staffLcToggle.isSelected());
            styleMiniToggle(staffLcToggle, script.actions().staffLeftClickCast());
            saveConfig();
        });
        page.add(staffLcToggle);
        page.add(Box.createVerticalStrut(6));
        
        // Manual controls
        JButton forceBarrageBtn = new JButton("Test Barrage");
        styleBtn(forceBarrageBtn, ACCENT_BLUE);
        forceBarrageBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        forceBarrageBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        forceBarrageBtn.addActionListener(e -> script.actions().forceNhBarrage());
        
        JButton walkUnderBtn = new JButton("Test Walk-Under");
        styleBtn(walkUnderBtn, ACCENT_PURPLE);
        walkUnderBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        walkUnderBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        walkUnderBtn.addActionListener(e -> ClientThreadGuard.get().invokeLater(() -> {
            boolean result = script.actions().walkUnderNow();
            FontManager.log("[WalkUnder] Test button result=" + result);
        }));
        
        JButton prayerTestBtn = new JButton("Test Prayer");
        styleBtn(prayerTestBtn, ACCENT_GREEN);
        prayerTestBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        prayerTestBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        prayerTestBtn.addActionListener(e -> script.actions().testPrayerSwitch());
        
        JPanel controlRow1 = new JPanel(new GridLayout(1, 2, 4, 0));
        controlRow1.setOpaque(false);
        controlRow1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        controlRow1.add(forceBarrageBtn);
        controlRow1.add(walkUnderBtn);
        
        JPanel controlRow2 = new JPanel(new GridLayout(1, 1, 4, 0));
        controlRow2.setOpaque(false);
        controlRow2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        controlRow2.add(prayerTestBtn);
        
        page.add(controlRow1);
        page.add(Box.createVerticalStrut(3));
        page.add(controlRow2);
        page.add(Box.createVerticalStrut(6));
        
        // KO HP — auto NH loop only. Swapper-driven NH ignores this.
        page.add(stepper("KO HP (auto melee switch)", script.actions().nhKoHp(), 1, 99, 1,
                v -> { script.actions().setNhKoHp(v); saveConfig(); }));
        page.add(Box.createVerticalStrut(8));
        
        // Info
        page.add(infoLine("NH V2: ice barrage + smart prayers + auto walk-under"));
        page.add(infoLine("Cycle: Freeze → Range → Melee KO"));
        page.add(infoLine("Set gear in Swapper → NH Loadouts"));
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

        dhModeToggle = miniToggle(script.actions().dharokEnabled() ? "DH MODE: ACTIVE" : "DH MODE: OFF", script.actions().dharokEnabled(), "Low HP greataxe + 1-tick whip/def");
        dhModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhModeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        dhModeToggle.addActionListener(e -> {
            script.actions().setDharokEnabled(dhModeToggle.isSelected());
            dhModeToggle.setText(script.actions().dharokEnabled() ? "DH MODE: ACTIVE" : "DH MODE: OFF");
            styleMiniToggle(dhModeToggle, script.actions().dharokEnabled());
            if (script.actions().dharokEnabled()) {
                masterToggle.setSelected(false); styleMasterToggle(masterToggle, false);
                if (dhPunishToggle != null) { dhPunishToggle.setSelected(true); styleMiniToggle(dhPunishToggle, true); }
                if (dhVengToggle != null) { dhVengToggle.setSelected(true); styleMiniToggle(dhVengToggle, true); }
            }
            saveConfig();
        });
        page.add(dhModeToggle);
        page.add(Box.createVerticalStrut(4));

        dhPunishToggle = miniToggle("Eat Punish", script.actions().eatPunishEnabled(), "Gmaul punish on eat");
        dhPunishToggle.addActionListener(e -> { script.actions().setEatPunish(dhPunishToggle.isSelected()); styleMiniToggle(dhPunishToggle, script.actions().eatPunishEnabled()); saveConfig(); });
        dhVengToggle = miniToggle("Auto Veng", script.actions().autoVengEnabled(), "Vengeance on engage");
        dhVengToggle.addActionListener(e -> { script.actions().setAutoVeng(dhVengToggle.isSelected()); styleMiniToggle(dhVengToggle, script.actions().autoVengEnabled()); saveConfig(); });
        dhAutoEatToggle = miniToggle("Auto Eat", script.actions().autoEatEnabled(), "Master switch for ALL automatic eating (Num5). Turn OFF to stay low HP for the greataxe");
        dhAutoEatToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        dhAutoEatToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        dhAutoEatToggle.addActionListener(e -> setAutoEat(dhAutoEatToggle.isSelected()));
        JPanel sub = new JPanel(new GridLayout(1, 2, 4, 0));
        sub.setOpaque(false);
        sub.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        sub.add(dhPunishToggle); sub.add(dhVengToggle);
        page.add(sub);
        page.add(Box.createVerticalStrut(4));
        page.add(dhAutoEatToggle);
        page.add(Box.createVerticalStrut(6));

        page.add(infoLine("You orb to 1 — bot axes only when stacked"));
        page.add(infoLine("Auto Eat OFF keeps your HP low (= bigger greataxe)"));
        page.add(infoLine("Gmaul follow if they live the axe (50%+)"));
        page.add(infoLine("1 marlin · 2 +hali · 3 +brew · E veng"));
        return page;
    }

    private JPanel buildSettingsPage() {
        JPanel page = vbox();

        page.add(createLabel("Combat hotkeys", FG_BRIGHT, 11f, true));
        page.add(infoLine("Click a bind, then press a key. Q/W/E stay free for Swapper."));
        page.add(Box.createVerticalStrut(2));
        hkSpecBtn = hotkeyBindRow("Spec dump (AGS/claws…)", HotkeyManager.get().specKeyName(),
                HotkeyManager.get()::setSpecKey);
        hkGmaulBtn = hotkeyBindRow("Gmaul follow", HotkeyManager.get().gmaulKeyName(),
                HotkeyManager.get()::setGmaulKey);
        hkVengBtn = hotkeyBindRow("Vengeance", HotkeyManager.get().vengKeyName(),
                HotkeyManager.get()::setVengKey);
        hkSetupBtn = hotkeyBindRow("Cycle spec setup", HotkeyManager.get().setupKeyName(),
                HotkeyManager.get()::setSetupKey);
        page.add(hkSpecBtn);
        page.add(hkGmaulBtn);
        page.add(hkVengBtn);
        page.add(hkSetupBtn);
        JButton resetHk = new JButton("Reset hotkeys (Spec=R)");
        styleBtn(resetHk, ACCENT_GOLD);
        resetHk.setAlignmentX(Component.LEFT_ALIGNMENT);
        resetHk.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        resetHk.addActionListener(e -> {
            HotkeyManager.get().resetCombatHotkeys();
            refreshHotkeyLabels();
        });
        page.add(Box.createVerticalStrut(2));
        page.add(resetHk);
        HotkeyManager.get().setHotkeyChangeListener(this::refreshHotkeyLabels);

        page.add(Box.createVerticalStrut(8));
        cbAnimTrig = miniToggle("Anim Trigger", script.actions().animTriggerEnabled(), "Spec on target animation");
        cbAnimTrig.addActionListener(e -> { script.actions().setAnimTrigger(cbAnimTrig.isSelected()); styleMiniToggle(cbAnimTrig, script.actions().animTriggerEnabled()); saveConfig(); });
        cbDmgTrig = miniToggle("Damage Trigger", script.actions().damageTriggerEnabled(), "Spec on incoming damage");
        cbDmgTrig.addActionListener(e -> { script.actions().setDamageTrigger(cbDmgTrig.isSelected()); styleMiniToggle(cbDmgTrig, script.actions().damageTriggerEnabled()); saveConfig(); });

        page.add(cbAnimTrig);
        page.add(stepper("Anim ID", script.actions().animTriggerAnim(), 1, 9999, 1,
                v -> { script.actions().setAnimTriggerAnim(v); saveConfig(); }));
        page.add(Box.createVerticalStrut(3));
        page.add(cbDmgTrig);
        page.add(stepper("Min damage", script.actions().damageTriggerMin(), 1, 99, 1,
                v -> { script.actions().setDamageTriggerMin(v); saveConfig(); }));
        page.add(Box.createVerticalStrut(3));
        page.add(stepper("AGS min spec %", script.actions().agsMinSpecPct(), 0, 100, 5,
                v -> { script.actions().setAgsMinSpecPct(v); saveConfig(); }));
        page.add(Box.createVerticalStrut(3));
        page.add(stepper("DMace min spec %", script.actions().dmaceMinSpecPct(), 0, 100, 5,
                v -> { script.actions().setDmaceMinSpecPct(v); saveConfig(); }));
        page.add(Box.createVerticalStrut(4));
        page.add(infoLine("Anim Trigger: dump spec when target plays a spec animation."));
        page.add(infoLine("Damage Trigger: dump spec when you take a hit >= min damage."));
        page.add(infoLine("INSERT toggles HUD · Ctrl+Shift+R toggles HUD"));
        return page;
    }

    /** Click → capture next keypress → persist via {@link HotkeyManager}. */
    private JPanel hotkeyBindRow(String label, String currentKey,
                                 java.util.function.IntConsumer onKey) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        JLabel lab = createLabel(label, FG_MUTED, 10f, false);
        lab.setPreferredSize(new Dimension(150, 22));
        row.add(lab, BorderLayout.WEST);
        JButton btn = new JButton(currentKey);
        styleBtn(btn, ACCENT_BLUE);
        btn.setPreferredSize(new Dimension(90, 22));
        btn.setToolTipText("Click, then press the key you want");
        btn.putClientProperty("hkLabel", label);
        btn.putClientProperty("hkSetter", onKey);
        btn.addActionListener(e -> captureCombatHotkey(btn, onKey));
        row.add(btn, BorderLayout.EAST);
        row.putClientProperty("hkBtn", btn);
        return row;
    }

    private void captureCombatHotkey(JButton btn, java.util.function.IntConsumer onKey) {
        btn.setText("Press key…");
        btn.setForeground(ACCENT_GOLD);
        HotkeyManager.get().setCaptureSink(e -> SwingUtilities.invokeLater(() -> {
            int code = e.getKeyCode();
            if (code == java.awt.event.KeyEvent.VK_ESCAPE
                    || code == java.awt.event.KeyEvent.VK_UNDEFINED) {
                refreshHotkeyLabels();
                return;
            }
            onKey.accept(code);
            refreshHotkeyLabels();
        }));
    }

    private void refreshHotkeyLabels() {
        if (hkSpecBtn != null) setHotkeyBtnText(hkSpecBtn, HotkeyManager.get().specKeyName());
        if (hkGmaulBtn != null) setHotkeyBtnText(hkGmaulBtn, HotkeyManager.get().gmaulKeyName());
        if (hkVengBtn != null) setHotkeyBtnText(hkVengBtn, HotkeyManager.get().vengKeyName());
        if (hkSetupBtn != null) setHotkeyBtnText(hkSetupBtn, HotkeyManager.get().setupKeyName());
    }

    private void setHotkeyBtnText(JPanel row, String text) {
        Object b = row.getClientProperty("hkBtn");
        if (b instanceof JButton) {
            JButton btn = (JButton) b;
            btn.setText(text);
            btn.setForeground(ACCENT_BLUE);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Left/right row for the target card. Either side may be null. */
    private static JPanel splitRow(JComponent west, JComponent east) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (west != null) p.add(west, BorderLayout.WEST);
        if (east != null) p.add(east, BorderLayout.EAST);
        // BoxLayout children default to an unbounded max size and would stretch;
        // pin the height to the natural one, as the other vbox() children do.
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /** Left-aligned decision chips; only the chips that apply are visible. */
    private static JPanel chipRow(JComponent... chips) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JComponent c : chips) p.add(c);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /**
     * "Abyssal whip · melee" straight off the per-tick {@link OpponentLoadout},
     * or "—" when the opponent's gear is not readable yet.
     */
    private static String opponentLine(CombatState st) {
        String name = displayName(st.opponentLoadout != null
                ? st.opponentLoadout.weaponName() : null);
        if (name.isEmpty()) return "—";
        return name + " · " + styleWord(st.opponentWeaponStyle());
    }

    /** Product language for a style — never the enum name. */
    private static String styleWord(AnimationDb.AttackStyle style) {
        if (style == null) return "unknown";
        switch (style) {
            case MELEE:  return "melee";
            case RANGED: return "range";
            case MAGIC:  return "mage";
            default:     return "unknown";
        }
    }

    /**
     * Item names arrive with the client's colour tags attached and can be very
     * long on custom servers; strip the markup and cap the width so the target
     * card cannot be stretched by an opponent's gear.
     */
    private static String displayName(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]*>", "").replaceAll("@[^@]*@", "").trim();
        return s.length() > 26 ? s.substring(0, 26) + "…" : s;
    }

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
        if (tab < 0 || tab >= tabLabels.length) tab = 0;
        activeTab = tab;
        for (int i = 0; i < tabLabels.length; i++) {
            tabLabels[i].setForeground(i == tab ? ACCENT_GOLD : FG_MUTED);
        }
        cards.show(body, TAB_KEYS[tab]);
        HotkeyManager.get().setOverlayMode(
                TAB_KEYS[tab].equals("SWAP") ? HotkeyManager.OverlayMode.SWAP
                                             : HotkeyManager.OverlayMode.PK);
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
        b.setText(on ? "LIVE" : "ARMED");
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

    /** +/- stepper (no typing) — auto-applies through {@code onSet}. */
    private JPanel stepper(String label, int value, int min, int max, int step,
                           java.util.function.IntConsumer onSet) {
        JPanel row = new JPanel(new java.awt.BorderLayout(6, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel lab = createLabel(label, FG_MUTED, 10f, false);
        lab.setPreferredSize(new Dimension(150, 20));
        row.add(lab, java.awt.BorderLayout.WEST);

        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
        controls.setOpaque(false);
        JButton minus = new JButton("-");
        JButton plus = new JButton("+");
        styleBtn(minus, ACCENT_BLUE);
        styleBtn(plus, ACCENT_BLUE);
        minus.setPreferredSize(new Dimension(26, 20));
        plus.setPreferredSize(new Dimension(26, 20));
        JLabel val = createLabel(Integer.toString(value), FG_BRIGHT, 11f, true);
        val.setPreferredSize(new Dimension(32, 20));
        val.setHorizontalAlignment(SwingConstants.CENTER);

        final int[] v = { Math.max(min, Math.min(max, value)) };
        java.util.function.Consumer<Boolean> apply = (up) -> {
            v[0] = Math.max(min, Math.min(max, v[0] + (up ? step : -step)));
            val.setText(Integer.toString(v[0]));
            if (onSet != null) onSet.accept(v[0]);
        };
        minus.addActionListener(e -> apply.accept(false));
        plus.addActionListener(e -> apply.accept(true));
        controls.add(minus);
        controls.add(val);
        controls.add(plus);
        row.add(controls, java.awt.BorderLayout.EAST);
        return row;
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
            // Single volatile read of the per-tick read-model: everything the
            // status panel shows below comes from one tick, never a blend. The
            // target name comes from here too, so it can never disagree with the
            // target HP / kill-range / weapon line beside it.
            final CombatState st = script.state();
            final CombatActions act = script.actions();
            final String lastAction = st.actionLabel();
            SwingUtilities.invokeLater(() -> {
                try {
                    hpBar.setValues(hp, 99);
                    prayBar.setValues(prayer, 99);
                    specBar.setValues(spec, 100);
                    if (pkSpecModeBtn != null) pkSpecModeBtn.setText("Spec: " + act.comboSetupName());
                    targetNameLabel.setText(st.targetName == null || st.targetName.isEmpty()
                            ? "Target: -" : "Target: " + st.targetName);
                    if (st.hasTargetHp()) {
                        targetHpLabel.setText("HP: " + st.targetHp + (st.targetMaxHp > 0 ? "/" + st.targetMaxHp : ""));
                        targetHpLabel.setForeground(st.inKillRange ? ACCENT_RED : ACCENT_GOLD);
                    } else {
                        targetHpLabel.setText("HP: -");
                        targetHpLabel.setForeground(FG_MUTED);
                    }
                    // What they are holding, from the per-tick opponent loadout.
                    targetWeaponLabel.setText(opponentLine(st));
                    // Decision line: four states, colour-coded, no tick counters.
                    fightStateLabel.setText(st.inActiveFight ? "Fighting" : "Idle");
                    fightStateLabel.setForeground(st.inActiveFight ? ACCENT_GREEN : FG_MUTED);
                    koLabel.setVisible(st.inKillRange);
                    specReadyLabel.setVisible(st.inActiveFight
                            && st.specReady(act.primaryMinSpecPct()));
                    if (act.dharokEnabled()) {
                        int maxHp = script.stateReader != null ? script.stateReader.getMaxHp() : 99;
                        int est = MaxHitCalculator.dharokMaxHit(act.meleeStr(), hp, maxHp);
                        dhMaxHitLabel.setText("Axe max: " + est);
                        dhAxeStatusLabel.setText(st.dhSwinging() ? "Greataxe: SWINGING KO!" : (hp <= 15 ? "Greataxe: STACKED" : "Greataxe: READY"));
                        dhAxeStatusLabel.setForeground(st.dhSwinging() ? ACCENT_RED : (hp <= 15 ? ACCENT_GOLD : ACCENT_GREEN));
                        dhSwapStatusLabel.setText(st.pendingDhWhipDef ? "Whip+Def: SWAPPING..." : "Whip+Def: ACTIVE");
                        dhSwapStatusLabel.setForeground(st.pendingDhWhipDef ? ACCENT_GOLD : ACCENT_GREEN);
                    } else {
                        dhAxeStatusLabel.setText("Greataxe: DH MODE OFF");
                        dhAxeStatusLabel.setForeground(FG_MUTED);
                        dhMaxHitLabel.setText("Axe Max Hit: -");
                        dhSwapStatusLabel.setText("Whip+Def: off");
                        dhSwapStatusLabel.setForeground(FG_MUTED);
                    }
                    actionTickerLabel.setText("Action: " + lastAction);
                    if (iceLcStatusLabel != null) {
                        String ice = script.iceLcStatusPublic();
                        iceLcStatusLabel.setText(ice);
                        iceLcStatusLabel.setForeground(ice.startsWith("Ice LC: READY")
                                ? ACCENT_GREEN : FG_MUTED);
                    }
                    syncToggle(pkAutoSpecBtn, act.autoSpecEnabled());
                    syncToggle(pkPunishToggle, act.eatPunishEnabled());
                    syncToggle(pkVengToggle, act.autoVengEnabled());
                    syncToggle(pkDefPrayToggle, act.defensivePrayersEnabled());
                    syncToggle(pkGearPrayToggle, act.gearCorroboratedDefPrayer());
                    stylePresetBtns();
                    syncToggle(pkComboEatToggle, act.comboEatEnabled());
                    syncToggle(pkAutoEatToggle, act.autoEatEnabled());
                    syncToggle(dhAutoEatToggle, act.autoEatEnabled());
                    syncToggle(pkProtectItemToggle, act.protectItemEnabled());
                    syncToggle(dhPunishToggle, act.eatPunishEnabled());
                    syncToggle(dhVengToggle, act.autoVengEnabled());
                    syncToggle(staffLcToggle, act.staffLeftClickCast());
                    if (dhModeToggle != null) { syncToggle(dhModeToggle, act.dharokEnabled()); dhModeToggle.setText(act.dharokEnabled() ? "DH MODE: ACTIVE" : "DH MODE: OFF"); }
                    if (masterToggle != null) { if (masterToggle.isSelected() != act.masterEnabled()) masterToggle.setSelected(act.masterEnabled()); styleMasterToggle(masterToggle, act.masterEnabled()); }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /**
     * Single place where the auto-eat master switch changes, so the HUD, the
     * hotkey and the persisted config can never disagree.
     */
    private void setAutoEat(boolean on) {
        script.actions().setAutoEat(on);
        syncToggle(pkAutoEatToggle, on);
        syncToggle(dhAutoEatToggle, on);
        saveConfig();
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
                String tab = p.getProperty("tab", "SWAP");
                if ("SWAP".equalsIgnoreCase(tab)) activeTab = 0;
                else if ("FIGHT".equalsIgnoreCase(tab) || "PK".equalsIgnoreCase(tab)
                        || "COMBAT".equalsIgnoreCase(tab) || "NH".equalsIgnoreCase(tab)) activeTab = 1;
                else if ("DH".equalsIgnoreCase(tab)) activeTab = 2;
                else activeTab = 0;
                if (p.containsKey("dh")) script.dharokEnabled = "true".equalsIgnoreCase(p.getProperty("dh"));
                if (p.containsKey("pun")) script.eatPunishEnabled = "true".equalsIgnoreCase(p.getProperty("pun"));
                if (p.containsKey("veng")) script.autoVengEnabled = "true".equalsIgnoreCase(p.getProperty("veng"));
                if (p.containsKey("autospec")) script.autoSpecEnabled = "true".equalsIgnoreCase(p.getProperty("autospec"));
                if (p.containsKey("defpray")) script.defensivePrayersEnabled = "true".equalsIgnoreCase(p.getProperty("defpray"));
                if (p.containsKey("gearpray")) script.gearCorroboratedDefPrayer = "true".equalsIgnoreCase(p.getProperty("gearpray"));
                if (p.containsKey("combat")) script.comboEatEnabled = "true".equalsIgnoreCase(p.getProperty("combat"));
                if (p.containsKey("protectitem")) script.autoProtectItemEnabled = "true".equalsIgnoreCase(p.getProperty("protectitem"));
                // Master auto-eat switch (absent in older configs => keep default ON).
                if (p.containsKey("autoeat")) script.autoEatEnabled = "true".equalsIgnoreCase(p.getProperty("autoeat"));
                if (p.containsKey("nh")) script.nhEnabled = "true".equalsIgnoreCase(p.getProperty("nh"));
                if (p.containsKey("nhv2")) script.nhV2Enabled = "true".equalsIgnoreCase(p.getProperty("nhv2"));
                if (p.containsKey("nhpray")) script.nhAutoPrayerEnabled = "true".equalsIgnoreCase(p.getProperty("nhpray"));
                if (p.containsKey("nhbarrage")) script.nhAutoBarrageEnabled = "true".equalsIgnoreCase(p.getProperty("nhbarrage"));
                if (p.containsKey("nhwalk")) script.nhAutoWalkUnderEnabled = "true".equalsIgnoreCase(p.getProperty("nhwalk"));
                if (p.containsKey("stafflc")) script.staffLcCast = "true".equalsIgnoreCase(p.getProperty("stafflc"));
                // NH engines are exclusive — NH V2 wins when both were persisted.
                if (script.nhV2Enabled) {
                    script.nhEnabled = false;
                    script.simpleNHEnabled = false;
                }
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
            p.setProperty("tab", TAB_KEYS[activeTab >= 0 && activeTab < TAB_KEYS.length ? activeTab : 0]);
            p.setProperty("dh", Boolean.toString(script.dharokEnabled));
            p.setProperty("pun", Boolean.toString(script.eatPunishEnabled));
            p.setProperty("veng", Boolean.toString(script.autoVengEnabled));
            p.setProperty("autospec", Boolean.toString(script.autoSpecEnabled));
            p.setProperty("defpray", Boolean.toString(script.defensivePrayersEnabled));
            p.setProperty("gearpray", Boolean.toString(script.gearCorroboratedDefPrayer));
            p.setProperty("combat", Boolean.toString(script.comboEatEnabled));
            p.setProperty("protectitem", Boolean.toString(script.autoProtectItemEnabled));
            p.setProperty("autoeat", Boolean.toString(script.autoEatEnabled));
            p.setProperty("nh", Boolean.toString(script.nhEnabled));
            p.setProperty("nhv2", Boolean.toString(script.nhV2Enabled));
            p.setProperty("nhpray", Boolean.toString(script.nhAutoPrayerEnabled));
            p.setProperty("nhbarrage", Boolean.toString(script.nhAutoBarrageEnabled));
            p.setProperty("nhwalk", Boolean.toString(script.nhAutoWalkUnderEnabled));
            p.setProperty("stafflc", Boolean.toString(script.staffLcCast));
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
