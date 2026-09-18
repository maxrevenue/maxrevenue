package com.bot.core.orchestrator;

import com.bot.core.bus.ActionKind;
import com.bot.core.bus.Intent;
import com.bot.core.model.CombatTickState;

/**
 * Game-legal exclusivity after channel resolution. No lambdas, no allocation.
 */
public final class ChannelRules {

    private ChannelRules() {
    }

    /**
     * Mutates {@code winners} in place. {@code eliminationReasons} parallel array indexed by
     * 0=offensive, 1=sustain, 2=defensive when a winner is cleared.
     */
    public static void applyExclusiveRules(CombatTickState state,
                                           Intent[] winners,
                                           EliminationReason[] eliminationReasons) {
        Intent sustain = winners[1];
        if (sustain != null) {
            ActionKind sk = sustain.kind();
            if (sk == ActionKind.EAT || sk == ActionKind.SIP) {
                if (winners[0] != null) {
                    eliminationReasons[0] = EliminationReason.SUPPRESSED_BY_RULE;
                    winners[0] = null;
                }
            }
        }
        Intent defensive = winners[2];
        if (defensive != null && defensive.kind() == ActionKind.EQUIP) {
            if (state.isItemEquipped(defensive.itemId())) {
                eliminationReasons[2] = EliminationReason.NOOP;
                winners[2] = null;
            }
        }
        Intent offensive = winners[0];
        if (offensive != null && offensive.kind() == ActionKind.SPECIAL) {
            int spec = state.specEnergyPercent();
            if (spec >= 0 && spec < 50) {
                eliminationReasons[0] = EliminationReason.NOOP;
                winners[0] = null;
            }
        }
    }
}
