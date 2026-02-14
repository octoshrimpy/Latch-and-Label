package com.latchandlabel.client.model;

import net.minecraft.util.Identifier;

import java.util.Objects;

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
