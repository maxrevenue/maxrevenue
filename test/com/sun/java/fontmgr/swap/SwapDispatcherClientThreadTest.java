package com.sun.java.fontmgr.swap;

import com.sun.java.fontmgr.ClientThreadGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Would fail when {@code SwapDispatcher.run} compacted equipment and called
 * {@code sendGameMessage} on the AWT EDT / test thread instead of hopping
 * through {@link ClientThreadGuard#invokeLater}.
 */
public class SwapDispatcherClientThreadTest {

    @BeforeEach
    public void reset() {
        ClientThreadGuard.get().resetForTest();
    }

    @AfterEach
    public void cleanup() {
        ClientThreadGuard.get().resetForTest();
    }

    @Test
    public void runFromOffThreadDoesNotExecuteUntilPump() {
        SwapDispatcher d = new SwapDispatcher(null);
        d.run(new Swap("veng", "c:Ice Barrage"));
        assertFalse(ClientThreadGuard.get().hasClientThread());
        assertEquals(1, ClientThreadGuard.get().pendingCount());
        assertEquals(1, ClientThreadGuard.get().pump());
        assertEquals(0, ClientThreadGuard.get().pendingCount());
        ClientThreadGuard.get().assertClientThread();
    }

    @Test
    public void runOnClientThreadDoesNotRequeue() {
        ClientThreadGuard.get().pump();
        SwapDispatcher d = new SwapDispatcher(null);
        d.run(new Swap("veng", "c:Ice Barrage"));
        assertEquals(0, ClientThreadGuard.get().pendingCount(),
                "already on the client thread — must not invokeLater again");
    }

    @Test
    public void twoOffThreadRunsDrainInOnePumpAndDoNotTouchEdt() {
        SwapDispatcher d = new SwapDispatcher(null);
        d.run(new Swap("unequip", "r:helm"));
        d.run(new Swap("veng", "c:veng"));
        assertEquals(2, ClientThreadGuard.get().pendingCount());
        AtomicInteger seen = new AtomicInteger();
        // Drain the two run() hops. script is null so no click tasks enqueue.
        seen.set(ClientThreadGuard.get().pump());
        assertEquals(2, seen.get());
        assertEquals(0, ClientThreadGuard.get().pendingCount());
    }

    @Test
    public void emptyOrNullSwapIsANoopOnTheCaller() {
        SwapDispatcher d = new SwapDispatcher(null);
        d.run(null);
        d.run(new Swap("empty", null));
        assertEquals(0, ClientThreadGuard.get().pendingCount());
        assertFalse(ClientThreadGuard.get().hasClientThread());
    }
}
