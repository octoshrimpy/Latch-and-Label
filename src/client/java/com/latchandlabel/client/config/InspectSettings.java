package com.latchandlabel.client.config;

/** Runtime settings for the container inspection overlay. Activation is Alt-only. */
public final class InspectSettings {
    private static int inspectRange = 8;
    private static boolean bordersAlwaysVisible = false;
    private static boolean labelsOnLook = false;

    private InspectSettings() {
    }

    public static boolean bordersAlwaysVisible() {
        return bordersAlwaysVisible;
    }

    public static void setBordersAlwaysVisible(boolean value) {
        bordersAlwaysVisible = value;
    }

    /** When on (and borders always shown), tag labels appear on the looked-at chest without holding Alt. */
    public static boolean labelsOnLook() {
        return labelsOnLook;
    }

    public static void setLabelsOnLook(boolean value) {
        labelsOnLook = value;
    }

    public static int inspectRange() {
        return inspectRange;
    }

    public static void setInspectRange(int range) {
        inspectRange = Math.max(1, range);
    }

    public static boolean isInspectActive(boolean isAltDown) {
        return isAltDown;
    }
}
