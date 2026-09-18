package com.bot.core.bus;

/**
 * Channel membership without varargs (varargs would allocate on every call site).
 */
public final class Channel {

    // DECISION: static singleton channels; kind arrays allocated once at class init (cold path).
    public static final Channel OFFENSIVE = new Channel(
            new ActionKind[]{ActionKind.ATTACK, ActionKind.SPECIAL, ActionKind.MOVE});
    public static final Channel SUSTAIN = new Channel(
            new ActionKind[]{ActionKind.EAT, ActionKind.SIP});
    public static final Channel DEFENSIVE = new Channel(
            new ActionKind[]{ActionKind.PRAYER, ActionKind.EQUIP});

    private final ActionKind[] kinds;

    private Channel(ActionKind[] kinds) {
        this.kinds = kinds;
    }

    public boolean contains(ActionKind k) {
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == k) {
                return true;
            }
        }
        return false;
    }
}
