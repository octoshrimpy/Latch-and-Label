package com.latchandlabel.client.config;

/** Runtime settings for item transfer behavior (move-to-storage source slots). */
public final class TransferSettings {
    private static MoveSourceMode moveSourceMode = MoveSourceMode.INVENTORY;
    private static boolean pullDropsOnGround = false;

    private TransferSettings() {
    }

    public static MoveSourceMode moveSourceMode() {
        return moveSourceMode;
    }

    public static void setMoveSourceMode(MoveSourceMode mode) {
        moveSourceMode = mode == null ? MoveSourceMode.INVENTORY : mode;
    }

    /** When pulling non-matching items out: true drops them on the ground, false places them in the inventory. */
    public static boolean pullDropsOnGround() {
        return pullDropsOnGround;
    }

    public static void setPullDropsOnGround(boolean value) {
        pullDropsOnGround = value;
    }
}
