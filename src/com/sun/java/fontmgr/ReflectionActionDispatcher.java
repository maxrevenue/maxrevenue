package com.sun.java.fontmgr;

import com.automation.core.dispatcher.ActionDispatcher;
import com.automation.core.model.ActionIntent;
import com.automation.core.model.AttackAction;
import com.automation.core.model.CastSpellAction;
import com.automation.core.model.EatAction;
import com.automation.core.model.EquipAction;
import com.automation.core.model.SpecAction;

import java.util.List;
import java.util.Locale;

/**
 * ACT phase for the live client: routes {@link ActionIntent}s to existing
 * {@link CombatScript} packet / menu helpers (Java 11 {@code instanceof} chain).
 */
public final class ReflectionActionDispatcher implements ActionDispatcher {

    private final CombatScript script;

    public ReflectionActionDispatcher(CombatScript script) {
        this.script = script;
    }

    @Override
    public void dispatch(List<ActionIntent> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (ActionIntent action : actions) {
            try {
                dispatchOne(action);
            } catch (Throwable t) {
                FontManager.log("[v2] dispatch failed for " + action + ": " + t.getMessage());
            }
        }
    }

    private void dispatchOne(ActionIntent action) {
        if (action instanceof EatAction) {
            EatAction eat = (EatAction) action;
            script.eatFromSlot(eat.inventorySlot(), true);
            script.lastAction = "EAT:" + eat.inventorySlot();
            return;
        }
        if (action instanceof EquipAction) {
            EquipAction equip = (EquipAction) action;
            if (equip.inventorySlot() >= 0) {
                script.wieldItemPublic(equip.inventorySlot(), equip.itemId());
            } else {
                script.useItemById(equip.itemId());
            }
            script.lastAction = "EQUIP:" + equip.itemId();
            return;
        }
        if (action instanceof CastSpellAction) {
            CastSpellAction cast = (CastSpellAction) action;
            script.castSpellNamed(cast.spellName());
            script.lastAction = "CAST:" + cast.spellName();
            return;
        }
        if (action instanceof SpecAction) {
            SpecAction spec = (SpecAction) action;
            routeSpec(spec.weaponLabel());
            script.lastAction = "SPEC:" + spec.weaponLabel();
            return;
        }
        if (action instanceof AttackAction) {
            script.attackLastTarget();
            script.lastAction = "ATTACK";
        }
    }

    private void routeSpec(String weaponLabel) {
        String label = weaponLabel == null ? "" : weaponLabel.toUpperCase(Locale.ROOT);
        if (label.contains("VLS")) {
            script.selectedSpec = CombatScript.SpecWeapon.VLS;
        } else if (label.contains("VOID")) {
            script.selectedSpec = CombatScript.SpecWeapon.VOIDWAKER;
        } else if (label.contains("GMAUL")) {
            script.selectedSpec = CombatScript.SpecWeapon.GMAUL;
        } else {
            script.selectedSpec = CombatScript.SpecWeapon.AGS;
        }
        script.executeSpec();
    }
}
