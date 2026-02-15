package com.latchandlabel.client.config;

public final class InspectSettings {
    private static int inspectRange = 8;
    private static InspectActivationMode activationMode = InspectActivationMode.ALT_OR_SHIFT;

    private InspectSettings() {
    }

    public static int inspectRange() {
        return inspectRange;
    }

    public static void setInspectRange(int range) {
        inspectRange = Math.max(1, range);
    }

    public static InspectActivationMode activationMode() {
        return activationMode;
    }

    public static void setActivationMode(InspectActivationMode mode) {
        activationMode = mode == null ? InspectActivationMode.ALT_OR_SHIFT : mode;
    }

    public static boolean isInspectActive(boolean isAltDown, boolean isSneaking) {
        return switch (activationMode) {
            case ALT_ONLY -> isAltDown;
            case SHIFT_ONLY -> isSneaking;
            case ALT_OR_SHIFT -> isAltDown || isSneaking;
        };
    }
}
