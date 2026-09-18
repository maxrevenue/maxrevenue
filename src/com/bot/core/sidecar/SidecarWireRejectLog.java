package com.bot.core.sidecar;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wire reject logging (separate from watchdog healthy/degraded policy).
 */
public final class SidecarWireRejectLog {

    private static final Logger LOG = Logger.getLogger("com.bot.core.sidecar.wire");

    private SidecarWireRejectLog() {
    }

    public static void reject(SidecarFrameCodec.RejectReason reason, int frameLength, byte versionByte) {
        if (reason == null) {
            return;
        }
        LOG.log(Level.WARNING, "sidecar wire reject: {0} len={1} version={2}",
                new Object[]{reason.name(), frameLength, versionByte & 0xFF});
    }
}
