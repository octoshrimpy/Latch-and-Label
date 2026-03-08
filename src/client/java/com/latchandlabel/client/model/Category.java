package com.latchandlabel.client.model;

import net.minecraft.util.Identifier;

import java.util.Objects;

/**
 * Immutable representation of a user-defined storage category (e.g. "Tools", "Food").
 * Validated on construction: id and name must be non-blank, iconItemId must be non-null.
 */
public record Category(
        String id,
        String name,
        int color,
        Identifier iconItemId,
        int order,
        boolean visible
) {
    public Category {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(iconItemId, "iconItemId");

        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
