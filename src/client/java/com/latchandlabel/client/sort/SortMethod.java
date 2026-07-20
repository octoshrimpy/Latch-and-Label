package com.latchandlabel.client.sort;

/** Orders items within a sorted chest group (planner assignment order and in-chest slot order). */
public enum SortMethod {
    /** Registry id order (e.g. minecraft:oak_log) — stable, groups by mod namespace. */
    REGISTRY_ID("registry_id"),
    /** Display-name alphabetical order. */
    ALPHABETICAL("alphabetical"),
    /** Most numerous item types first. */
    ITEM_COUNT("item_count"),
    /** Creative inventory (search tab) order. */
    CREATIVE("creative");

    private final String configValue;

    SortMethod(String configValue) {
        this.configValue = configValue;
    }

    public String toConfigValue() {
        return configValue;
    }

    public static SortMethod fromConfigValue(String value) {
        for (SortMethod method : values()) {
            if (method.configValue.equals(value)) {
                return method;
            }
        }
        return REGISTRY_ID;
    }
}
