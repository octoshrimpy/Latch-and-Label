package com.latchandlabel.client.config;

public final class InspectSettings {
    private static int inspectRange = 8;

    private InspectSettings() {
    }

    public static int inspectRange() {
        return inspectRange;
    }

    public static void setInspectRange(int range) {
        inspectRange = Math.max(1, range);
    }
}
