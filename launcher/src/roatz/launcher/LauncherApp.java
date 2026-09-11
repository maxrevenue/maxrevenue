package roatz.launcher;

import com.sun.java.fontmgr.Product;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Buyer-facing launcher: activate a key, start vanilla Roat, auto-attach after login grace.
 */
public final class LauncherApp extends JFrame {

    /** Wait after Start so login is not interrupted by agent patches. */
    private static final long AUTO_ATTACH_DELAY_MS = 45_000L;
    private static final long AUTO_ATTACH_RETRY_MS = 5_000L;
    private static final int AUTO_ATTACH_MAX_ATTEMPTS = 12;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LauncherApp app = new LauncherApp();
            app.setVisible(true);
            app.boot();
        });
    }

    private final CardLayout cards = new CardLayout();
    private final JPanel stack = new JPanel(cards);
    private final LicenseClient licenses = new LicenseClient();
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "roatz-io");
        t.setDaemon(true);
        return t;
    });

    private LicenseStore store = LicenseStore.load();
    private RoatInstall roat = RoatInstall.detect();
    private GameLauncher.Session session;
    /** Bumped to cancel an in-flight auto-attach watch. */
    private final AtomicInteger autoAttachEpoch = new AtomicInteger();
    private volatile boolean attachedOk;

    private final JTextField keyField = new JTextField();
    private final JLabel activateError = new JLabel(" ");
    /** The buyer's whole status surface: three chips, no jargon, no PIDs. */
    private final JLabel licenseChip = new JLabel();
    private final JLabel roatChip = new JLabel();
    private final JLabel stateChip = new JLabel();
    /** Version + key last-4. Never the full key. */
    private final JLabel footer = new JLabel();
    private final JTextArea log = new JTextArea();
    private JScrollPane logScroll;
    private JToggleButton detailsToggle;
    private final JButton playBtn = primary("Play");
    private final JButton attachBtn = secondary("Attach now");
    private final JButton officialBtn = secondary("Update Roat");

    private LauncherApp() {
        super(Product.NAME + "  " + Product.VERSION);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(460, 560));
        setSize(480, 600);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);

        stack.setBackground(Theme.BG);
        stack.add(activatePage(), "activate");
        stack.add(homePage(), "home");
        setContentPane(stack);
        cards.show(stack, "activate");
    }

    private void boot() {
        log("License service: " + licenses.apiBase());
        refreshRoat();
        if (!store.hasKey()) {
            cards.show(stack, "activate");
            return;
        }
        keyField.setText(store.key);
        setBusy(true);
        io.submit(() -> {
            String hwid = LicenseStore.currentHwid();
            LicenseClient.Result r = licenses.check(store.key, hwid);
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                if (r.ok()) {
                    store.apply(r, hwid);
                    showHome("Licensed");
                    return;
                }
                if (r.kind == LicenseClient.Kind.NETWORK || r.kind == LicenseClient.Kind.SERVER) {
                    if (store.canAttachOffline(hwid)) {
                        showHome("Licensed · offline");
                        log("License server unreachable; using cached license.");
                        return;
                    }
                    activateError.setText("Cannot reach the license server and no cached license.");
                    cards.show(stack, "activate");
                    return;
                }
                store.clear();
                activateError.setForeground(Theme.RED);
                activateError.setText(r.message);
                cards.show(stack, "activate");
            });
        });
    }

    private JPanel activatePage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Theme.BG);
        page.add(header("Activate"), BorderLayout.NORTH);

        JPanel card = cardPanel();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 4, 4, 4);

        JLabel hint = muted("Paste the key you were sent. One PC per key.");
        card.add(hint, gc);
        gc.gridy++;
        keyField.setFont(Theme.ui(16, Font.BOLD));
        keyField.setBackground(Theme.TITLE);
        keyField.setForeground(Theme.FG);
        keyField.setCaretColor(Theme.FG);
        keyField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BTN_BORDER),
                new EmptyBorder(8, 10, 8, 10)));
        card.add(keyField, gc);
        gc.gridy++;
        activateError.setForeground(Theme.RED);
        activateError.setFont(Theme.ui(12, Font.PLAIN));
        card.add(activateError, gc);
        gc.gridy++;
        JButton go = pill("Activate");
        go.addActionListener(e -> activate());
        keyField.addActionListener(e -> activate());
        card.add(go, gc);

        page.add(pad(card), BorderLayout.CENTER);

        page.add(footBar(new JLabel("v" + Product.VERSION)), BorderLayout.SOUTH);
        return page;
    }

    private JPanel homePage() {
        JPanel page = new JPanel(new BorderLayout(0, 8));
        page.setBackground(Theme.BG);
        page.add(header(Product.NAME), BorderLayout.NORTH);

        JPanel card = cardPanel();
        card.setLayout(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 4, 4, 4);

        // Status chips — the only status the buyer has to read.
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        setChip(licenseChip, "Checking…", Theme.MUTED);
        setChip(roatChip, "Checking…", Theme.MUTED);
        setChip(stateChip, "Ready", Theme.MUTED);
        chips.add(licenseChip);
        chips.add(roatChip);
        chips.add(stateChip);
        card.add(chips, gc);

        // One primary action.
        gc.gridy++;
        gc.insets = new Insets(14, 4, 2, 4);
        playBtn.addActionListener(e -> startGame());
        card.add(playBtn, gc);

        gc.gridy++;
        gc.insets = new Insets(2, 4, 4, 4);
        card.add(muted("Press Play, log into Roat, and the HUD attaches on its own."), gc);

        gc.gridy++;
        gc.insets = new Insets(8, 4, 4, 4);
        JPanel secondaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        secondaryRow.setOpaque(false);
        attachBtn.addActionListener(e -> attachNow());
        secondaryRow.add(attachBtn);
        officialBtn.addActionListener(e -> official());
        secondaryRow.add(officialBtn);
        card.add(secondaryRow, gc);

        // Raw output is diagnostics: hidden until asked for.
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setBackground(Theme.TITLE);
        log.setForeground(Theme.MUTED);
        log.setFont(Theme.ui(11, Font.PLAIN));
        log.setBorder(new EmptyBorder(8, 10, 8, 10));
        logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logScroll.setPreferredSize(new Dimension(100, 150));
        logScroll.getViewport().setBackground(Theme.TITLE);
        logScroll.setVisible(false);

        gc.gridy++;
        gc.insets = new Insets(6, 4, 1, 4);
        detailsToggle = new JToggleButton("Details");
        detailsToggle.setFocusPainted(false);
        detailsToggle.setContentAreaFilled(false);
        detailsToggle.setForeground(Theme.MUTED);
        detailsToggle.setFont(Theme.ui(12, Font.PLAIN));
        detailsToggle.setBorder(new EmptyBorder(6, 4, 6, 4));
        detailsToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        detailsToggle.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        detailsToggle.addActionListener(e -> {
            boolean open = detailsToggle.isSelected();
            detailsToggle.setText(open ? "Hide details" : "Details");
            logScroll.setVisible(open);
            page.revalidate();
            page.repaint();
        });
        card.add(detailsToggle, gc);

        gc.gridy++;
        gc.insets = new Insets(1, 4, 4, 4);
        gc.fill = GridBagConstraints.BOTH;
        gc.weighty = 1;
        card.add(logScroll, gc);

        page.add(pad(card), BorderLayout.CENTER);

        page.add(footBar(footer), BorderLayout.SOUTH);
        return page;
    }

    private void activate() {
        String key = keyField.getText() == null ? "" : keyField.getText().trim().toUpperCase();
        if (key.isEmpty()) {
            activateError.setText("Paste a key first.");
            return;
        }
        setBusy(true);
        activateError.setForeground(Theme.MUTED);
        activateError.setText("Checking…");
        io.submit(() -> {
            String hwid = LicenseStore.currentHwid();
            LicenseClient.Result r = licenses.activate(key, hwid);
            SwingUtilities.invokeLater(() -> {
                setBusy(false);
                if (r.ok()) {
                    store.key = key;
                    store.apply(r, hwid);
                    showHome("Licensed");
                    return;
                }
                activateError.setForeground(Theme.RED);
                activateError.setText(r.message);
            });
        });
    }

    private void startGame() {
        cancelAutoAttach();
        attachedOk = false;
        setBusy(true);
        io.submit(() -> {
            try {
                refreshRoat();
                if (!roat.ready()) {
                    SwingUtilities.invokeLater(() -> {
                        setBusy(false);
                        log(roat.error);
                        setChip(roatChip, "Roat missing", Theme.RED);
                        setChip(stateChip, "Not ready", Theme.RED);
                    });
                    return;
                }
                session = GameLauncher.startVanilla(roat);
                final long pid = session.pid;
                final int epoch = autoAttachEpoch.incrementAndGet();
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    setChip(stateChip, "Waiting to attach", Theme.ACCENT);
                    log("Roat is running. Log in now — attaching automatically in 45 seconds.");
                    log("Already in-game? Press Attach now.");
                });
                scheduleAutoAttach(epoch, pid);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    log("Start failed: " + ex.getMessage());
                });
            }
        });
    }

    /** Manual early attach; cancels the pending auto-attach watch. */
    private void attachNow() {
        cancelAutoAttach();
        if (store.token == null || store.token.isEmpty()) {
            log("Activate a license key first.");
            cards.show(stack, "activate");
            return;
        }
        setBusy(true);
        io.submit(() -> {
            try {
                doAttach("manual");
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        log(ex.getMessage() != null ? ex.getMessage() : "Attach failed."));
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        });
    }

    private void scheduleAutoAttach(int epoch, long startedPid) {
        io.submit(() -> {
            try {
                long deadline = System.currentTimeMillis() + AUTO_ATTACH_DELAY_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (epoch != autoAttachEpoch.get()) return;
                    if (!processAlive(startedPid)) {
                        uiState("Game closed", Theme.RED);
                        log("Roat closed before the HUD could attach. Press Play again.");
                        return;
                    }
                    long left = Math.max(0L, (deadline - System.currentTimeMillis() + 999L) / 1000L);
                    uiState("Waiting to attach · " + left + "s", Theme.ACCENT);
                    Thread.sleep(1000L);
                }
                if (epoch != autoAttachEpoch.get()) return;
                if (store.token == null || store.token.isEmpty()) {
                    log("Your key needs activating before the HUD can attach.");
                    return;
                }
                log("Attaching…");
                for (int attempt = 1; attempt <= AUTO_ATTACH_MAX_ATTEMPTS; attempt++) {
                    if (epoch != autoAttachEpoch.get()) return;
                    if (attachedOk) return;
                    if (!processAlive(startedPid) && resolvePid() <= 0) {
                        uiState("Game closed", Theme.RED);
                        log("Roat closed during attach.");
                        return;
                    }
                    try {
                        doAttach("auto");
                        return;
                    } catch (Exception ex) {
                        String msg = ex.getMessage() != null ? ex.getMessage() : "Attach failed.";
                        if (attempt >= AUTO_ATTACH_MAX_ATTEMPTS) {
                            log("Could not attach automatically: " + msg);
                            log("Finish logging in, then press Attach now.");
                            uiState("Not attached", Theme.RED);
                            return;
                        }
                        log("Still waiting for login (attempt " + attempt + ")");
                        uiState("Waiting for login · " + attempt + "/" + AUTO_ATTACH_MAX_ATTEMPTS, Theme.ACCENT);
                        Thread.sleep(AUTO_ATTACH_RETRY_MS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void doAttach(String mode) throws Exception {
        long pid = resolvePid();
        if (pid <= 0) {
            throw new IllegalStateException("Roat is not running yet. Press Play, log in, then try again.");
        }
        if (!Files.isRegularFile(AppPaths.agentJar())) {
            throw new IllegalStateException("A " + Product.NAME + " file is missing. Reinstall to fix it.");
        }
        AttachService.attach(pid, AppPaths.agentJar(), store.token);
        attachedOk = true;
        cancelAutoAttach();
        String who = "auto".equals(mode) ? "Attached automatically" : "Attached";
        SwingUtilities.invokeLater(() -> {
            setChip(stateChip, "Live", Theme.GREEN);
            log(who + ". The HUD should now be in-game.");
        });
    }

    private void cancelAutoAttach() {
        autoAttachEpoch.incrementAndGet();
    }

    private static boolean processAlive(long pid) {
        if (pid <= 0) return false;
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void uiState(String text, java.awt.Color color) {
        SwingUtilities.invokeLater(() -> setChip(stateChip, text, color));
    }

    private void official() {
        io.submit(() -> {
            try {
                refreshRoat();
                GameLauncher.openOfficial(roat);
                SwingUtilities.invokeLater(() ->
                        log("Opened the Roat updater. Let it finish, log in once, then press Play here."));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> log(ex.getMessage()));
            }
        });
    }

    private long resolvePid() {
        if (session != null && session.alive()) return session.pid;
        Long found = GameLauncher.latestRoatPid();
        return found == null ? -1L : found;
    }

    private void showHome(String licenseLabel) {
        refreshRoat();
        setChip(licenseChip, licenseLabel, Theme.GREEN);
        if (roat.ready()) {
            setChip(roatChip, "Roat found", Theme.GREEN);
            log(roat.statusLine());
        } else {
            setChip(roatChip, "Roat missing", Theme.RED);
            log(roat.error);
        }
        setChip(stateChip, "Ready", Theme.MUTED);
        footer.setText("v" + Product.VERSION
                + (keyLast4(store.key).isEmpty() ? "" : "   ·   key ••••" + keyLast4(store.key)));
        officialBtn.setEnabled(roat.canOpenOfficial() || roat.javaExe != null);
        cards.show(stack, "home");
    }

    private void refreshRoat() {
        roat = RoatInstall.detect();
    }

    private void setBusy(boolean busy) {
        keyField.setEnabled(!busy);
        playBtn.setEnabled(!busy);
        attachBtn.setEnabled(!busy);
        officialBtn.setEnabled(!busy);
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void log(String line) {
        if (line == null || line.isEmpty()) return;
        if (log.getText() == null || log.getText().isEmpty()) log.setText(line);
        else log.append("\n" + line);
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static JPanel header(String title) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.TITLE);
        bar.setBorder(new EmptyBorder(14, 18, 14, 18));
        JLabel t = new JLabel(title);
        t.setForeground(Theme.ACCENT);
        t.setFont(Theme.ui(18, Font.BOLD));
        bar.add(t, BorderLayout.WEST);
        return bar;
    }

    /** Footer strip: version and key last-4 live here, not in the header. */
    private static JPanel footBar(JLabel content) {
        JPanel foot = new JPanel(new BorderLayout());
        foot.setBackground(Theme.BG);
        foot.setBorder(new EmptyBorder(0, 18, 10, 18));
        content.setForeground(Theme.MUTED);
        content.setFont(Theme.ui(11, Font.PLAIN));
        foot.add(content, BorderLayout.WEST);
        return foot;
    }

    private static JPanel cardPanel() {
        JPanel card = new JPanel();
        card.setBackground(Theme.CARD);
        card.setBorder(new EmptyBorder(16, 16, 16, 16));
        return card;
    }

    private static JPanel pad(JPanel inner) {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.BG);
        wrap.setBorder(new EmptyBorder(16, 16, 8, 16));
        wrap.add(inner, BorderLayout.NORTH);
        return wrap;
    }

    /** Restyles a chip in place; colour drives the border so they never disagree. */
    /** Last 4 characters of the key, for the footer — never the whole key. */
    private static String keyLast4(String key) {
        if (key == null) return "";
        String k = key.trim().toUpperCase();
        if (k.isEmpty()) return "";
        return k.length() <= 4 ? k : k.substring(k.length() - 4);
    }

    /** Restyles a chip in place; colour drives the border so they never disagree. */
    private static void setChip(JLabel chip, String text, java.awt.Color color) {
        chip.setText(text);
        chip.setForeground(color);
        chip.setFont(Theme.ui(11, Font.BOLD));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color.darker(), 1),
                new EmptyBorder(3, 8, 3, 8)));
    }

    /** The single primary call to action. */
    private static JButton primary(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setBackground(Theme.ACCENT);
        b.setForeground(Theme.ON_ACCENT);
        b.setFont(Theme.ui(15, Font.BOLD));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.ACCENT),
                new EmptyBorder(12, 16, 12, 16)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    /** Quiet secondary action — must never compete with Play. */
    private static JButton secondary(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setForeground(Theme.MUTED);
        b.setFont(Theme.ui(12, Font.PLAIN));
        b.setBorder(new EmptyBorder(6, 10, 6, 10));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.MUTED);
        l.setFont(Theme.ui(12, Font.PLAIN));
        return l;
    }

    private static JButton pill(String text) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setBackground(Theme.BTN);
        b.setForeground(Theme.FG);
        b.setFont(Theme.ui(13, Font.BOLD));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BTN_BORDER),
                new EmptyBorder(10, 14, 10, 14)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }
}
