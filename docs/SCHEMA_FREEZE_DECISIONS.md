# Schema freeze — DECISION log (PR #21 rev 2)

// DECISION: `targetNpcIndex` reserved in replay projection at -1 until CombatAdvisor transcribes interacting target (not read in current advisor code).
// DECISION: `eatsUsed` / `specsUsed` outcome counters use conservative projection-transition heuristics; refine post-first-capture without schema version bump only if fields already present.
// DECISION: Sidecar arrival lag histogram snapshots every 50 ticks (orchestrator) plus best-effort `flushPeriodicBestEffort` on shutdown (caller must invoke when wiring socket lifecycle).
// DECISION: WIRE_V1 frames reject with log + counter until body decoder lands (observe path still exercises reject metrics).
// DECISION: No invariant violations — wall-clock remains on writer/reader threads only; tick path uses `GameState.tickIndex` only.
