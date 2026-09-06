package com.sun.java.fontmgr;

import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Global hotkeys scoped to the active mini-overlay tab.
 * <ul>
 *   <li>{@link OverlayMode#PK} — PK combat keys only (1–4 eat, Q/W/E, …)</li>
 *   <li>{@link OverlayMode#SWAP} — Advanced Swapper bindings only</li>
 * </ul>
 * Same physical keys can be bound in both modes without conflict.
 */
public final class HotkeyManager {

    public enum OverlayMode { PK, SWAP }

    public static final int DEFAULT_SPEC_KEY  = KeyEvent.VK_F;
    public static final int DEFAULT_GMAUL_KEY = KeyEvent.VK_G;
    public static final int DEFAULT_VENG_KEY  = KeyEvent.VK_V;
    public static final int DEFAULT_SETUP_KEY = KeyEvent.VK_R;
    public static final int DEFAULT_EAT_KEY   = KeyEvent.VK_NUMPAD3;
    public static final int DEFAULT_AUTO_KEY  = KeyEvent.VK_NUMPAD0;

    private static HotkeyManager instance;

    private CombatScript script;
    private com.sun.java.fontmgr.swap.SwapManager swapManager;
    private com.sun.java.fontmgr.swap.SwapDispatcher swapDispatcher;
    private volatile Runnable swapFlush;
    private int specKey  = DEFAULT_SPEC_KEY;
    private int gmaulKey = DEFAULT_GMAUL_KEY;
    private int vengKey  = DEFAULT_VENG_KEY;
    private int setupKey = DEFAULT_SETUP_KEY;
    private int eatKey   = DEFAULT_EAT_KEY;
    private int autoKey  = DEFAULT_AUTO_KEY;
    private final Set<Integer> held = new HashSet<>();
    private volatile boolean installed = false;
    private volatile OverlayMode overlayMode = OverlayMode.PK;

    public static HotkeyManager get() {
        if (instance == null) instance = new HotkeyManager();
        return instance;
    }

    public void init(CombatScript script) {
        this.script = script;
        load();
        if (installed) return;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::dispatch);
        // Canvas-focused clients often skip KeyboardFocusManager; this still sees Q.
        Toolkit.getDefaultToolkit().addAWTEventListener(ev -> {
            if (ev instanceof KeyEvent) dispatch((KeyEvent) ev);
        }, AWTEvent.KEY_EVENT_MASK);
        installed = true;
        logModeHint();
    }

    public void setSwapper(com.sun.java.fontmgr.swap.SwapManager manager,
                           com.sun.java.fontmgr.swap.SwapDispatcher dispatcher) {
        this.swapManager = manager;
        this.swapDispatcher = dispatcher;
    }

    public void setSwapFlush(Runnable flush) {
        this.swapFlush = flush;
    }

    public void setOverlayMode(OverlayMode mode) {
        if (mode == null) mode = OverlayMode.PK;
        if (this.overlayMode == mode) return;
        this.overlayMode = mode;
        logModeHint();
    }

    public OverlayMode getOverlayMode() {
        return overlayMode;
    }

    private void logModeHint() {
        if (overlayMode == OverlayMode.PK) {
            FontManager.debug("theme mode active");
        } else {
            FontManager.debug("cache mode active");
        }
    }

    private volatile java.util.function.Consumer<KeyEvent> captureSink;

    public void setCaptureSink(java.util.function.Consumer<KeyEvent> sink) {
        this.captureSink = sink;
    }

    public boolean isCapturingHotkey() {
        return captureSink != null;
    }

    public int getSpecKey() { return specKey; }
    public String specKeyName() { return KeyEvent.getKeyText(specKey); }

    private boolean dispatch(KeyEvent e) {
        if (script == null) return false;

        java.util.function.Consumer<KeyEvent> cap = captureSink;
        if (cap != null && e.getID() == KeyEvent.KEY_PRESSED) {
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_UNDEFINED) return true;
            captureSink = null;
            if (code != KeyEvent.VK_ESCAPE) {
                try { cap.accept(e); } catch (Exception ignored) {}
            } else {
                try { cap.accept(e); } catch (Exception ignored) {}
            }
            return true;
        }

        int code = e.getKeyCode();
        if (code == KeyEvent.VK_UNDEFINED) return false;

        if (e.getID() == KeyEvent.KEY_RELEASED) {
            held.remove(code);
            return false;
        }
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;
        if (!held.add(code)) {
            // AWT listener may have handled this already — still swallow dump keys
            // so the client does not also bind Q/W/E.
            return code == KeyEvent.VK_Q || code == KeyEvent.VK_W || code == KeyEvent.VK_E
                    || code == specKey || code == gmaulKey;
        }

        // Swap binds always fire (even if focus is in the swap editor).
        if (trySwapHotkey(e)) return true;

        // Protect Z/X/C always available (PK + Swap modes).
        if (code == KeyEvent.VK_Z) {
            script.triggerProtectMagic();
            return true;
        }
        if (code == KeyEvent.VK_X) {
            script.triggerProtectRange();
            return true;
        }
        if (code == KeyEvent.VK_C) {
            script.triggerProtectMelee();
            return true;
        }

        if (isTyping(e)) return false;

        if (overlayMode == OverlayMode.SWAP) {
            if (code == KeyEvent.VK_Q) {
                script.triggerClawsGmaulNow();
                return true;
            }
            return false;
        }
        // PK mode
        if (dispatchPk(code)) return true;
        return dispatchPkShared(code);
    }

    private boolean trySwapHotkey(KeyEvent e) {
        if (swapManager == null || swapDispatcher == null) return false;
        int mods = e.getModifiersEx()
                & (KeyEvent.SHIFT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
        com.sun.java.fontmgr.swap.Swap swap = swapManager.byHotkey(e.getKeyCode(), mods);
        if (swap == null) return false;
        swapDispatcher.run(swap);
        return true;
    }

    /** PK overlay: eats on 1–4, combat on Q/W/E. DH mode: 1–4 stay panic-eat only. */
    private boolean dispatchPk(int code) {
        if (code == KeyEvent.VK_1) {
            UiExecutor.exec(() -> script.executePkEatTier(1), "pk-eat-1");
            return true;
        }
        if (code == KeyEvent.VK_2) {
            UiExecutor.exec(() -> script.executePkEatTier(2), "pk-eat-2");
            return true;
        }
        if (code == KeyEvent.VK_3) {
            UiExecutor.exec(() -> script.executePkEatTier(3), "pk-eat-3");
            return true;
        }
        if (code == KeyEvent.VK_4) {
            UiExecutor.exec(() -> script.executePkEatTier(4), "pk-eat-4");
            return true;
        }
        if (code == KeyEvent.VK_Q) {
            script.triggerClawsGmaulNow();
            return true;
        }
        if (code == KeyEvent.VK_W) {
            UiExecutor.exec(script::triggerGmaulFollowNow, "pk-gmaul");
            return true;
        }
        if (code == KeyEvent.VK_E) {
            script.triggerVengNow();
            return true;
        }
        // NH brew-heavy eats (A/S/D → tiers 1–3) — documented in overlay hints.
        if (code == KeyEvent.VK_A) {
            UiExecutor.exec(() -> script.executeEatTier(1), "nh-eat-1");
            return true;
        }
        if (code == KeyEvent.VK_S) {
            UiExecutor.exec(() -> script.executeEatTier(2), "nh-eat-2");
            return true;
        }
        if (code == KeyEvent.VK_D) {
            UiExecutor.exec(() -> script.executeEatTier(3), "nh-eat-3");
            return true;
        }
        // NH tank switch (T).
        if (code == KeyEvent.VK_T) {
            script.nhSwitchTank();
            return true;
        }
        // NH manual ice barrage (Space).
        if (code == KeyEvent.VK_SPACE) {
            script.triggerBarrageNow();
            return true;
        }
        // Auto-pray toggle (Num9) — defensive prayer switching on/off.
        if (code == KeyEvent.VK_NUMPAD9) {
            script.defensivePrayersEnabled = !script.defensivePrayersEnabled;
            return true;
        }
        return false;
    }

    /** PK-only extras (setup / autospec / custom binds). Protect handled earlier as Z/X/C. */
    private boolean dispatchPkShared(int code) {
        if (code == setupKey || code == KeyEvent.VK_R) {
            script.toggleComboSetup();
            return true;
        }
        if (code == autoKey || code == KeyEvent.VK_NUMPAD0) {
            if (!script.dharokEnabled) {
                script.autoSpecEnabled = !script.autoSpecEnabled;
            }
            return true;
        }
        if (code == specKey) {
            script.triggerSpecNow();
            return true;
        }
        if (code == gmaulKey || code == KeyEvent.VK_G) {
            UiExecutor.exec(script::triggerGmaulFollowNow, "hotkey-gmaul-follow");
            return true;
        }
        if (code == vengKey || code == KeyEvent.VK_V) {
            script.triggerVengNow();
            return true;
        }
        return false;
    }

    private static boolean isTyping(KeyEvent e) {
        Object focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus == null) return false;
        String n = focus.getClass().getName();
        return n.contains("TextField") || n.contains("TextArea") || n.contains("Editor")
                || n.contains("ComboBox");
    }

    private void load() {
        Path p = Paths.get(System.getProperty("java.io.tmpdir"), ".cache", "settings.properties");
        try {
            if (!Files.exists(p)) return;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(p)) { props.load(in); }
            specKey  = parseKey(props.getProperty("hotkey.spec"),  DEFAULT_SPEC_KEY);
            gmaulKey = parseKey(props.getProperty("hotkey.gmaul"), DEFAULT_GMAUL_KEY);
            vengKey  = parseKey(props.getProperty("hotkey.veng"),  DEFAULT_VENG_KEY);
            setupKey = parseKey(props.getProperty("hotkey.setup"), DEFAULT_SETUP_KEY);
            eatKey   = parseKey(props.getProperty("hotkey.eat"),   DEFAULT_EAT_KEY);
            autoKey  = parseKey(props.getProperty("hotkey.auto"),  DEFAULT_AUTO_KEY);
            boolean migrated = false;
            // Z/X/C are protect prayers — migrate old default spec-on-C to F.
            if (specKey == KeyEvent.VK_C || specKey == KeyEvent.VK_F2
                    || specKey == KeyEvent.VK_NUMPAD1 || specKey == KeyEvent.VK_END) {
                specKey = DEFAULT_SPEC_KEY;
                migrated = true;
            }
            if (gmaulKey == KeyEvent.VK_NUMPAD2 || gmaulKey == KeyEvent.VK_DOWN) {
                gmaulKey = DEFAULT_GMAUL_KEY;
                migrated = true;
            }
            if (eatKey  == KeyEvent.VK_F3) { eatKey  = DEFAULT_EAT_KEY;  migrated = true; }
            if (autoKey == KeyEvent.VK_F1) { autoKey = DEFAULT_AUTO_KEY; migrated = true; }
            if (migrated) save();
        } catch (Exception ignored) {}
    }

    public void save() {
        Path p = Paths.get(System.getProperty("java.io.tmpdir"), ".cache", "settings.properties");
        try {
            Files.createDirectories(p.getParent());
            Properties props = new Properties();
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) { props.load(in); }
            }
            props.setProperty("hotkey.spec",  Integer.toString(specKey));
            props.setProperty("hotkey.gmaul", Integer.toString(gmaulKey));
            props.setProperty("hotkey.veng",  Integer.toString(vengKey));
            props.setProperty("hotkey.setup", Integer.toString(setupKey));
            props.setProperty("hotkey.eat",   Integer.toString(eatKey));
            props.setProperty("hotkey.auto",  Integer.toString(autoKey));
            try (OutputStream out = Files.newOutputStream(p)) {
                props.store(out, "cache");
            }
        } catch (Exception ignored) {}
    }

    private static int parseKey(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private HotkeyManager() {}
}
