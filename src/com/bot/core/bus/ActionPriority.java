package com.bot.core.bus;

public enum ActionPriority {
    BACKGROUND(0),
    SUSTAIN(10),
    OFFENSIVE(20),
    CRITICAL(30);

    private final int weight;

    ActionPriority(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}
