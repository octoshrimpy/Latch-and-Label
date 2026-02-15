package com.latchandlabel.client.config;

import java.util.Locale;

public enum InspectActivationMode {
    ALT_ONLY,
    SHIFT_ONLY,
    ALT_OR_SHIFT;

    public static InspectActivationMode fromConfigValue(String raw) {
        if (raw == null) {
            return ALT_OR_SHIFT;
        }

        try {
            return InspectActivationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ALT_OR_SHIFT;
        }
    }

    public String toConfigValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
