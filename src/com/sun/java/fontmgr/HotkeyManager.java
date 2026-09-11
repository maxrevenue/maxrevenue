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
    public static final int DEFAULT_AUTO_EAT_KEY = KeyEvent.VK_NUMPAD5;

    private static final class Holder {
        static final HotkeyManager INSTANCE = new HotkeyManager();
    }

    private CombatScript script;
    private com.sun.java.fontmgr.swap.SwapManager swapManager;
    private com.sun.java.fontmgr.swap.SwapDispatcher swapDispatcher;
    private volatile java.util.function.Consumer<com.sun.java.fontmgr.swap.Swap> swapFlush;
    private int specKey  = DEFAULT_SPEC_KEY;
    private int gmaulKey = DEFAULT_GMAUL_KEY;
    private int vengKey  = DEFAULT_VENG_KEY;
    private int setupKey = DEFAULT_SETUP_KEY;
    private int eatKey   = DEFAULT_EAT_KEY;
    private int autoKey  = DEFAULT_AUTO_KEY;
    private int autoEatKey = DEFAULT_AUTO_EAT_KEY;
    private final Set<Integer> held = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean installed = false;
    private volatile OverlayMode overlayMode = OverlayMode.PK;

    public static HotkeyManager get() {
        return Holder.INSTANCE;
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

    public void setSwapFlush(java.util.function.Consumer<com.sun.java.fontmgr.swap.Swap> flush) {
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

    private volatile java.util.function.Consumer<Boolean> autoEatListener;

    /** Notified whenever the auto-eat master switch changes, so the HUD can sync + persist. */
    public void setAutoEatListener(java.util.function.Consumer<Boolean> listener) {
        this.autoEatListener = listener;
    }

    private boolean dispatch(KeyEvent e) {
        if (script == null) return false;

        java.util.function.Consumer<KeyEvent> cap = captureSink;
        if (cap != null && e.getID() == KeyEvent.KEY_PRESSED) {
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_UNDEFINED) return true;
            captureSink = null;
            // Escape clears the binding; the sink itself decides. Both paths are
            // the same call, so there is no need to branch here.
            try { cap.accept(e); } catch (Exception ignored) {}
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
            return code == KeyEvent.VK_Q || code == gmaulKey;
        }

        // Swap binds always fire (even if focus is in the swap editor).
        if (trySwapHotkey(e)) return true;

        // Typing must beat everything else below: Z/X/C and the combat keys
        // used to be handled first, so typing "c" in the swap editor or an HP
        // field fired protect-melee and swallowed the character.
        if (isTyping(e)) return false;

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

        // Combat keys (eats 1-4, A/S/D NH eats, specs, T/Space/Num9) work on
        // EVERY tab, including the Swapper page — otherwise you die if you eat
        // while sitting on the Swapper tab. Swap hotkeys still take priority
        // (they run earlier in this handler); typing in the editor is protected
        // by isTyping() above.
        if (dispatchPk(code)) return true;
        return dispatchPkShared(code);
    }

    private boolean trySwapHotkey(KeyEvent e) {
        if (swapManager == null || swapDispatcher == null) return false;
        int mods = e.getModifiersEx()
                & (KeyEvent.SHIFT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
        com.sun.java.fontmgr.swap.Swap swap = swapManager.byHotkey(e.getKeyCode(), mods);
        if (swap == null) return false;
        flushSwapEditor(swap);
        swapDispatcher.run(swap);
        return true;
    }

    /** Persist unsaved editor text when the hotkey matches the open swap. */
    private void flushSwapEditor(com.sun.java.fontmgr.swap.Swap swap) {
        java.util.function.Consumer<com.sun.java.fontmgr.swap.Swap> flush = swapFlush;
        if (flush == null) return;
        Runnable r = () -> flush.accept(swap);
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                javax.swing.SwingUtilities.invokeAndWait(r);
            }
        } catch (Exception ignored) {}
    }

    /** PK overlay: eats on 1–4, combat on Q/W/E. DH mode: 1–4 stay panic-eat only. */
    private boolean dispatchPk(int code) {
        if (code == KeyEvent.VK_1) {
            UiExecutor.exec(() -> script.executeEatKey(1), "eat-1");
            return true;
        }
        if (code == KeyEvent.VK_2) {
            UiExecutor.exec(() -> script.executeEatKey(2), "eat-2");
            return true;
        }
        if (code == KeyEvent.VK_3) {
            UiExecutor.exec(() -> script.executeEatKey(3), "eat-3");
            return true;
        }
        if (code == KeyEvent.VK_4) {
            UiExecutor.exec(() -> script.executeEatKey(4), "eat-4");
            return true;
        }
        // Spec combo: Q, plus the configurable spec bind (hotkey.spec, default F).
        // A Swapper hotkey on the same key still wins - trySwapHotkey runs first.
        // W/E/T/A/S/D stay free for your own Swapper hotkeys and typing.
        if (code == KeyEvent.VK_Q || code == specKey) {
            script.triggerSpecNow();
            return true;
        }
        // NH manual ice barrage (Space).
        if (code == KeyEvent.VK_SPACE) {
            script.triggerBarrageNow();
            return true;
        }
        // Auto-pray toggle (Num9) — defensive prayer switching on/off.
        if (code == KeyEvent.VK_NUMPAD9) {
            script.actions().toggleDefensivePrayers();
            return true;
        }
        return false;
    }

    /** PK-only extras (setup / autospec / auto-eat / custom binds). Protect handled earlier as Z/X/C. */
    private boolean dispatchPkShared(int code) {
        if (code == autoEatKey || code == KeyEvent.VK_NUMPAD5) {
            boolean on = !script.actions().autoEatEnabled();
            java.util.function.Consumer<Boolean> l = autoEatListener;
            if (l != null) { try { l.accept(on); } catch (Exception ignored) {} }
            else script.actions().setAutoEat(on);
            return true;
        }
        if (code == autoKey || code == KeyEvent.VK_NUMPAD0) {
            script.actions().toggleAutoSpec();
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
        // Configured binds that were parsed and persisted but never dispatched:
        // setup cycles the spec setup (same as the HUD button), eat does one
        // manual eat through the tier-1 path the "1" key uses.
        if (code == setupKey || code == KeyEvent.VK_R) {
            script.actions().toggleComboSetup();
            return true;
        }
        if (code == eatKey || code == KeyEvent.VK_NUMPAD3) {
            UiExecutor.exec(() -> script.executeEatKey(1), "hotkey-eat");
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
            autoEatKey = parseKey(props.getProperty("hotkey.autoeat"), DEFAULT_AUTO_EAT_KEY);
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
            props.setProperty("hotkey.autoeat", Integer.toString(autoEatKey));
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
