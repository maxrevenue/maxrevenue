package com.sun.java.fontmgr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a {@link TickRecorder} TSV (header + rows, {@code #} comments skipped)
 * into {@link CombatState} snapshots for {@link ReplayHarness}.
 */
public final class TickTsv {

    private TickTsv() {}

    public static List<CombatState> parse(String text) {
        List<CombatState> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] lines = text.split("\\R", -1);
        String[] header = null;
        long seqFallback = 0;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (header == null) {
                header = line.split("\t", -1);
                continue;
            }
            String[] cols = line.split("\t", -1);
            out.add(row(header, cols, ++seqFallback));
        }
        return out;
    }

    private static CombatState row(String[] header, String[] cols, long seqFallback) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < header.length && i < cols.length; i++) {
            m.put(header[i], cols[i]);
        }
        int tick = ival(m, "tick", -1);
        long seq = lval(m, "seq", seqFallback);
        int weaponId = ival(m, "owpn", 0);
        boolean dh = "1".equals(m.get("odh"));
        OpponentLoadout loadout = loadout(weaponId, m.get("ostyle"), dh);
        boolean inKill = "1".equals(m.get("okill"));
        return new CombatState.Builder(seq, tick)
                .targetName(sval(m, "tgt", ""))
                .targetHp(ival(m, "thp", -1))
                .targetMaxHp(ival(m, "tmax", -1))
                .specEnergy(ival(m, "spec", -1))
                .inKillRange(inKill)
                .opponentIsDh(dh)
                .opponentLoadout(loadout)
                .ourOverhead(sval(m, "oh", "NONE"))
                .localAnim(ival(m, "anim", -1))
                .lastTargetAnim(ival(m, "tanim", -1))
                .defPrayTrace(sval(m, "defpray", ""))
                .lastAction(sval(m, "action", "none"))
                .ourHp(ival(m, "ohp", -1))
                .ourMaxHp(ival(m, "omax", -1))
                .ourStr(ival(m, "ostr", -1))
                .estimatedOurMaxHit(ival(m, "ourhit", -1))
                .estimatedOppDhHit(ival(m, "opphit", -1))
                .inDhDanger("1".equals(m.get("dhdanger")))
                .nhPhase(sval(m, "nhphase", "IDLE"))
                .nhFreezeTicksLeft(ival(m, "freeze", 0))
                .nhV2Enabled("1".equals(m.get("nhv2")))
                .inActiveFight(inKill || ival(m, "thp", -1) > 0)
                .hitsplatChangeTick(ival(m, "splatTick", -1))
                .incomingChangeTick(ival(m, "inTick", -1))
                .agsSpecTick(ival(m, "agsTick", -99))
                .specSequenceBusy("1".equals(m.get("busy")))
                .mageStaffEquipped("1".equals(m.get("staff")))
                .hasSpecWeapon(!m.containsKey("specwpn") || "1".equals(m.get("specwpn")))
                .specWeaponEquipped("1".equals(m.get("specworn")))
                .build();
    }

    static OpponentLoadout loadout(int weaponId, String style, boolean dharok) {
        int[] ids = new int[14];
        if (weaponId > 0) ids[OpponentLoadout.SLOT_WEAPON] = weaponId;
        else if (dharok || (style != null && !style.isEmpty())) ids[OpponentLoadout.SLOT_WEAPON] = 1;
        final String name;
        if (dharok) name = "Dharok's greataxe";
        else if ("RANGED".equalsIgnoreCase(style)) name = "Toxic blowpipe";
        else if ("MAGIC".equalsIgnoreCase(style)) name = "Ancient staff";
        else name = "Abyssal whip";
        return OpponentLoadout.capture(ids, id -> name);
    }

    private static int ival(Map<String, String> m, String k, int dflt) {
        String v = m.get(k);
        if (v == null || v.isEmpty()) return dflt;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static long lval(Map<String, String> m, String k, long dflt) {
        String v = m.get(k);
        if (v == null || v.isEmpty()) return dflt;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    private static String sval(Map<String, String> m, String k, String dflt) {
        String v = m.get(k);
        return v == null ? dflt : v;
    }
}
