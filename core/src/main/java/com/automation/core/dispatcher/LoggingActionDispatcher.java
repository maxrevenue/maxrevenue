package com.automation.core.dispatcher;

import com.automation.core.model.ActionIntent;
import com.automation.core.model.AttackAction;
import com.automation.core.model.CastSpellAction;
import com.automation.core.model.EatAction;
import com.automation.core.model.EquipAction;
import com.automation.core.model.SpecAction;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * A no-op {@link ActionDispatcher} that logs the client interaction it
 * <em>would</em> perform for each intent, instead of touching a live client.
 *
 * <p>It doubles as a worked example of the ACT phase: the real dispatcher in the
 * agent module keeps the same {@code instanceof} pattern-matching shape but
 * replaces each log line with the corresponding reflection / packet call. Because
 * {@link ActionIntent} is {@code sealed}, this chain is exhaustive over every
 * permitted intent.
 */
public final class LoggingActionDispatcher implements ActionDispatcher {

    private static final Logger LOG = System.getLogger(LoggingActionDispatcher.class.getName());

    @Override
    public void dispatch(List<ActionIntent> actions) {
        for (ActionIntent action : actions) {
            LOG.log(Level.INFO, () -> "[dispatch p=" + action.priorityLevel() + "] " + describe(action));
        }
    }

    private static String describe(ActionIntent action) {
        if (action instanceof EatAction eat) {
            return "EAT inventory slot " + eat.inventorySlot();
        } else if (action instanceof EquipAction equip) {
            return "EQUIP item " + equip.itemId() + " (slot " + equip.inventorySlot() + ")";
        } else if (action instanceof CastSpellAction cast) {
            return "CAST " + cast.spellName() + " on target " + cast.targetIndex();
        } else if (action instanceof SpecAction spec) {
            return "SPEC " + spec.weaponLabel() + " (needs " + spec.minSpecPct() + "%)";
        } else if (action instanceof AttackAction attack) {
            return "ATTACK target " + attack.targetIndex();
        }
        return "UNKNOWN " + action;
    }
}
