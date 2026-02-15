package com.latchandlabel.client.config;

public final class TransferSettings {
    private static MoveSourceMode moveSourceMode = MoveSourceMode.INVENTORY;

    private TransferSettings() {
    }

    public static MoveSourceMode moveSourceMode() {
        return moveSourceMode;
    }

    public static void setMoveSourceMode(MoveSourceMode mode) {
        moveSourceMode = mode == null ? MoveSourceMode.INVENTORY : mode;
    }
}
