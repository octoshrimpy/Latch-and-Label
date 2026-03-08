package com.latchandlabel.client.dump;

public final class DumpSettings {
    private static boolean queueMode = false;
    private static int dumpRange = 16;

    private DumpSettings() {
    }

    public static boolean queueMode() {
        return queueMode;
    }

    public static void setQueueMode(boolean enabled) {
        queueMode = enabled;
    }

    public static int dumpRange() {
        return dumpRange;
    }

    public static void setDumpRange(int range) {
        dumpRange = Math.max(1, range);
    }
}
