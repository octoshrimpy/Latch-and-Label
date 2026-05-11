package com.latchandlabel.client.config;

/** Runtime settings for detecting a likely category from an opened storage's contents. */
public final class ContainerDetectionSettings {
    private static final int DEFAULT_DETECTED_CATEGORY_THRESHOLD_PERCENT = 60;
    private static int detectedCategoryThresholdPercent = DEFAULT_DETECTED_CATEGORY_THRESHOLD_PERCENT;

    private ContainerDetectionSettings() {
    }

    public static int detectedCategoryThresholdPercent() {
        return detectedCategoryThresholdPercent;
    }

    public static void setDetectedCategoryThresholdPercent(int thresholdPercent) {
        detectedCategoryThresholdPercent = Math.max(1, Math.min(100, thresholdPercent));
    }

    public static int defaultDetectedCategoryThresholdPercent() {
        return DEFAULT_DETECTED_CATEGORY_THRESHOLD_PERCENT;
    }
}
