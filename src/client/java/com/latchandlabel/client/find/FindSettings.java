package com.latchandlabel.client.find;

public final class FindSettings {
    private static int defaultFindRadius = 24;
    private static boolean variantMatchingEnabled = true;
    private static boolean enableFindOverlayList;
    private static boolean allowSlashFCommand = true;
    private static boolean allowFindKeybind = true;

    private FindSettings() {
    }

    public static int defaultFindRadius() {
        return defaultFindRadius;
    }

    public static boolean variantMatchingEnabled() {
        return variantMatchingEnabled;
    }

    public static boolean enableFindOverlayList() {
        return enableFindOverlayList;
    }

    public static boolean allowSlashFCommand() {
        return allowSlashFCommand;
    }

    public static boolean allowFindKeybind() {
        return allowFindKeybind;
    }

    public static void setDefaultFindRadius(int radius) {
        defaultFindRadius = Math.max(1, radius);
    }

    public static void setVariantMatchingEnabled(boolean enabled) {
        variantMatchingEnabled = enabled;
    }

    public static void setEnableFindOverlayList(boolean enabled) {
        enableFindOverlayList = enabled;
    }

    public static void setAllowSlashFCommand(boolean enabled) {
        allowSlashFCommand = enabled;
    }

    public static void setAllowFindKeybind(boolean enabled) {
        allowFindKeybind = enabled;
    }
}
