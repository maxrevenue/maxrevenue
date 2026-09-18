# Fingerprint field registry

Any sidecar intent or advisor decision consulting a new state field must add it to `FingerprintRegistry` (or explicitly waive it with a documented reason). Unregistered-but-consulted fields are a bug.

| Field | Bit range | Source | Consulted by |
|---|---|---|---|
| Local HP | 0–7 | `CombatTickState.localHp()` | SustainAdvisor, eat preconds |
| Food present | 8 | food slot ≥ 0 | SustainAdvisor |
| Protect prayer active | 9 | `activeProtectPrayer()` | Suppression early-release |
| Spec energy | 16–23 | `specEnergyPercent()` | CombatAdvisor |

Implementation: `com.bot.core.orchestrator.FingerprintRegistry`.

Tests: `FingerprintCoverageTest` (behavioral), `FingerprintLayoutTest` (structural non-overlap).
