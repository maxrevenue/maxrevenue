package com.bot.core.telemetry;

/** Feasibility of a tickbus dispatch path (shadow golden diff ignores {@link #LABEL_ONLY}). */
public enum TickDispatchState {
    WIRED,
    LABEL_ONLY,
    NO_DISPATCHER
}
