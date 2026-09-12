# In-game overlays

Roatz can draw into the live game buffer (not just the Swing HUD) by wrapping
RuneLite's `Callbacks` field and painting before the original blit.

## Package

`com.sun.java.fontmgr.overlay`

| Class | Role |
|-------|------|
| `GameOverlay` | Interface — `name()` + `render(Graphics2D, OverlayContext)` |
| `OverlayContext` | Per-frame context (client, bridge, canvas size, logged-in) |
| `RuneLiteBridge` | Reflection-only view of `net.runelite.api` (no compile dep on game.jar) |
| `OverlayManager` | Registry + per-frame pump, per-overlay enable/disable, error isolation |
| `OverlayHook` | Proxies `Callbacks`, draws into the buffer image on `draw`, then delegates |
| `TileMarkerOverlay` | Player (green), cursor (yellow), combat target (red), plugin marks (cyan) |

Wired in `FontManager.bootstrap()` as step **6c** (after looter, before Swing HUD).

## Behaviour

- Drawing goes into the **buffer image**, so stretched/resized modes scale with the scene.
- The original Hooks is still called — RuneLite overlays / notifications keep working.
- If the RuneLite API is missing, the bridge logs one warning and disables overlays.
- Re-attach calls `OverlayHook.ensureInstalled` so a replaced callbacks field is re-wrapped.

## Plugin access

```java
Object client = FontManager.clientInstance();
TileMarkerOverlay tiles = FontManager.tileOverlay();
if (tiles != null) {
    tiles.mark(3200, 3200, 0);
}
OverlayManager mgr = FontManager.overlayManager();
if (mgr != null) {
    mgr.register(new GameOverlay() {
        public String name() { return "my-overlay"; }
        public void render(Graphics2D g, OverlayContext ctx) { /* ... */ }
    });
}
```

## Command socket (`-Dagent.cmd=true`)

| Command | Effect |
|---------|--------|
| `OVERLAY` / `OVERLAY\|LIST` | List registered overlays + on/off |
| `OVERLAY\|ON\|tiles` | Enable an overlay |
| `OVERLAY\|OFF\|tiles` | Disable an overlay |
| `OVERLAY\|MARK\|x\|y\|plane` | Cyan mark at world tile |
| `OVERLAY\|UNMARK\|x\|y\|plane` | Remove that mark |
| `OVERLAY\|CLEAR` | Clear all plugin marks |
| `OVERLAY\|STATUS` | Hook installed? active? list |

Example:

```text
OVERLAY|MARK|3200|3200|0
```

## Live check

1. Rebuild / Play / Attach.
2. Look for log lines: `[Overlay] RuneLite bridge ready` and
   `[Overlay] callbacks wrapped (original=...)`.
3. Expect green (you), yellow (hovered tile), red (combat target) outlines.
4. If tiles are missing, paste those `[Overlay]` lines — that separates hook
   failure from projection failure.
