package com.latchandlabel.client.data;

import java.util.Locale;

/**
 * Shared utilities for normalizing scope identifiers used by {@code TagStore}
 * and {@code ClientDataManager}. Scope IDs are lowercased and stripped to
 * alphanumeric characters, dots, hyphens, and underscores.
 */
public final class ScopeUtil {
    private ScopeUtil() {
    }

    /**
     * Normalizes a raw scope ID to a filesystem/map-safe string.
     *
     * @param scopeId  the raw scope ID (may be null or blank)
     * @param fallback the value to return when the input is null, blank, or normalizes to empty
     * @return the normalized scope ID, or {@code fallback} if the input is unusable
     */
    public static String normalizeScopeId(String scopeId, String fallback) {
        if (scopeId == null || scopeId.isBlank()) {
            return fallback;
        }
        String trimmed = scopeId.trim().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9') || current == '.' || current == '-' || current == '_') {
                normalized.append(current);
            } else {
                normalized.append('_');
            }
        }
        if (normalized.length() == 0) {
            return fallback;
        }
        return normalized.toString();
    }
}
