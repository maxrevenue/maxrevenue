package com.bot.overlay;

import com.bot.core.StubCombatState;
import com.bot.core.bus.ActionKind;
import com.bot.core.bus.ActionPriority;
import com.bot.core.bus.IntentPool;
import com.bot.core.orchestrator.EliminationReason;
import com.bot.core.orchestrator.TickResolutionSnapshot;
import com.bot.core.telemetry.TickDispatchState;
import com.sun.java.fontmgr.overlay.OverlayContext;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless render of the HUD strip for walkthrough evidence (no live client required).
 */
class OverlayHudRenderTest {

    @Test
    void renderHudStripToArtifact() throws IOException {
        System.setProperty("roatz.overlay", "true");
        StubCombatState state = new StubCombatState(1205L);
        state.hp = 62;
        state.spec = 100;
        IntentPool pool = new IntentPool(2, 2);
        com.bot.core.bus.Intent spec = pool.obtain(ActionKind.SPECIAL, ActionPriority.OFFENSIVE, 0, 0, 0,
                1205L, 1, 0, 0);
        com.bot.core.bus.Intent eat = pool.obtain(ActionKind.EAT, ActionPriority.CRITICAL, 0, 0, 5,
                1205L, 1, 0, 0);
        com.bot.core.bus.Intent[] winners = new com.bot.core.bus.Intent[]{spec, eat, null};
        int[] dispatch = new int[]{
                TickDispatchState.WIRED.ordinal(),
                TickDispatchState.WIRED.ordinal(),
                TickDispatchState.NO_DISPATCHER.ordinal()
        };
        EliminationReason[] drops = new EliminationReason[]{
                EliminationReason.NONE,
                EliminationReason.NONE,
                EliminationReason.NONE
        };
        OverlayPublisher.publish(state, new TickResolutionSnapshot(winners, drops, dispatch), null, null);

        BufferedImage img = new BufferedImage(400, 140, BufferedImage.TYPE_INT_ARGB);
        TickBusHudOverlay overlay = new TickBusHudOverlay();
        overlay.render(img.createGraphics(), new OverlayContext(null, null, 0L, 400, 140, true));

        Path out = Path.of("/opt/cursor/artifacts/tickbus_hud_shadow_strip.png");
        Files.createDirectories(out.getParent());
        assertTrue(ImageIO.write(img, "png", out.toFile()));
    }
}
