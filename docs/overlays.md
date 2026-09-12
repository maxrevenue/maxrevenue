# In-game overlays & plugins

The agent can draw on the game's own frame — tile markers, clickboxes, labels —
the same way a RuneLite plugin does. This is the foundation for interacting with
the world (tiles, objects, actors) rather than only reading state.

## How it hooks in

The Roat PKz client is a **full RuneLite client**: it ships `net.runelite.api.*`
(and `net.runelite.rs.api.*`) and blits every frame through

```
ProducingGraphicsBuffer.drawFull0(Graphics, int, int)
        └─ Client.getCallbacks().draw(bufferProvider, graphics, x, y)   // net.runelite.client.callback.Hooks
```

`Hooks.draw(...)` renders RuneLite's own overlay layers into the buffer image and
then blits it to the canvas. The client's `callbacks` field is Guice-injected
(`@Inject Callbacks callbacks`) and has no setter on this build.

`OverlayHook` therefore:

1. reads the `callbacks` field reflectively,
2. wraps it in a `java.lang.reflect.Proxy` of `net.runelite.api.hooks.Callbacks`
   that **delegates every call** to the original `Hooks`,
3. draws the registered overlays into the buffer image inside the proxied
   `draw(...)` — before delegating, so the original blit includes them,
4. writes the proxy back, and re-asserts it once per game tick.

Delegation is deliberate: RuneLite's own overlays, notifications and screenshots
keep working. Drawing into the buffer image (not the canvas) means our polygons
scale correctly in stretched/resized modes.

```
agent bootstrap
  └─ RuneLiteBridge          # reflection view of net.runelite.api
  └─ OverlayManager.init     # registry
  └─ TileMarkerOverlay       # built-in overlay
  └─ OverlayHook.install     # wrap Callbacks  →  per-frame renderAll(Graphics2D)
```

If the RuneLite API is absent the bridge reports `unavailable`, a single warning
is logged, and the rest of the agent is unaffected.

## Writing an overlay

```java
package myplugin;

import com.sun.java.fontmgr.overlay.GameOverlay;
import com.sun.java.fontmgr.overlay.OverlayContext;
import java.awt.*;

public final class NpcTileOverlay implements GameOverlay {

    @Override
    public String name() {
        return "NPC Tiles";
    }

    @Override
    public void render(Graphics2D g, OverlayContext ctx) {
        // ctx.client() is the RuneLiteBridge (tiles, projection, game state)
        Polygon poly = ctx.client().selectedTilePoly();
        if (poly == null) return;
        g.setColor(new Color(255, 0, 255, 60));
        g.fillPolygon(poly);
        g.setColor(new Color(255, 0, 255, 230));
        g.drawPolygon(poly);
    }
}
```

`render` runs on the client's render thread once per frame. Keep it cheap and
never block. Exceptions are caught per overlay.

## Registering from a plugin JAR

The agent already loads plugin JARs (`plugins=...` agent arg /
`-Dfontmgr.plugins=`, see `FontManager.loadPluginPackages`) and calls an optional
bootstrap. Define the bootstrap in your plugin JAR:

```java
package com.sun.java.fontmgr.plugin; // this exact class name is discovered

import com.sun.java.fontmgr.overlay.OverlayManager;
import java.lang.instrument.Instrumentation;

public final class PluginBootstrap {
    public static void onAttach(Instrumentation inst) {
        OverlayManager.get().register(new myplugin.NpcTileOverlay());
    }
}
```

`OverlayManager` is a process-wide singleton, so a plugin can register at attach
time and does not need a handle to the client. Use
`FontManager.clientInstance()` if you need the raw client object, or
`OverlayManager.get().bridge()` for the resolved RuneLite bridge.

> Do **not** put `com.sun.java.fontmgr.plugin.PluginBootstrap` in the agent JAR —
> the agent looks it up on the system class loader, and shipping it would shadow
> every plugin's bootstrap.

## Built-in overlay: Tile Markers

`TileMarkerOverlay` is registered on startup and draws, per frame:

| Tile | Colour |
|------|--------|
| Local player | green |
| Under cursor | yellow |
| Combat target | red |
| Explicit marks | cyan |

Marking API (thread-safe, callable from any thread):

```java
TileMarkerOverlay tiles = FontManager.tileOverlay();
tiles.mark(3200, 3200, 0);      // world x, world y, plane
tiles.toggle(3200, 3201, 0);
tiles.unmark(3200, 3200, 0);
tiles.clearMarks();
tiles.setShowHoveredTile(false); // individual layers
```

Marks are stored per world tile + plane and only drawn while that plane is
active.

## Command socket (opt-in)

Start the agent with `-Dagent.cmd=true` to control overlays over
`127.0.0.1:9998`:

| Command | Effect |
|---------|--------|
| `OVERLAY\|LIST` | list overlays and their on/off state |
| `OVERLAY\|ON[|name]` | enable one overlay, or all when unnamed |
| `OVERLAY\|OFF[|name]` | disable one overlay, or all |
| `OVERLAY\|MARK\|x\|y\|plane` | add a tile marker |
| `OVERLAY\|UNMARK\|x\|y\|plane` | remove a tile marker |
| `OVERLAY\|CLEAR` | clear all tile markers |

## Caveats

* Overlays draw **below** RuneLite's own overlay layers (they are composited
  first). Move the overlay ordering into `Hooks.draw` later if that matters.
* The bridge uses reflection on purpose — the agent never compiles against the
  game JAR (it is git-ignored and versioned with the client). If a client update
  renames a RuneLite member, the bridge logs one warning and disables overlays
  instead of failing the attach.
* `getImage().getGraphics()` is called once per frame; it is disposed
  immediately. No long-lived `Graphics2D` is retained.
