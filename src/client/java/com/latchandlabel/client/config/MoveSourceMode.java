package com.latchandlabel.client.config;

/** Controls whether alt-click item transfers pull from inventory only or inventory + hotbar. */
public enum MoveSourceMode {
    INVENTORY("inventory"),
    INVENTORY_AND_HOTBAR("inventory_hotbar");

    private final String configValue;

    MoveSourceMode(String configValue) {
        this.configValue = configValue;
    }

    public String toConfigValue() {
        return configValue;
    }

    public boolean includesHotbar() {
        return this == INVENTORY_AND_HOTBAR;
    }

    public static MoveSourceMode fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return INVENTORY;
        }

        for (MoveSourceMode mode : values()) {
            if (mode.configValue.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        return INVENTORY;
    }
}
