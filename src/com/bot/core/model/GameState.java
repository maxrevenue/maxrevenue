package com.bot.core.model;

/**
 * Immutable tick snapshot consumed by advisors and the orchestrator.
 * Fingerprint layout is a cross-JVM contract with the EchoForge sidecar; document
 * bit assignments in {@code FingerprintLayout} when sidecar work lands.
 */
public interface GameState {

    /** Monotonic server tick index; sole time base for TTLs and suppression. */
    long tickIndex();

    /** Bitwise combat fingerprint for masked sidecar preconditions. */
    int getFingerprint();
}
