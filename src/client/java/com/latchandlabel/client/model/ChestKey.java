package com.latchandlabel.client.model;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;
import java.util.Objects;

public record ChestKey(Identifier dimensionId, BlockPos pos) {
    private static final String DELIMITER = "|";
    private static final String COORD_DELIMITER = ",";
    private static final String VANILLA_NAMESPACE = "minecraft";

    public ChestKey {
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(pos, "pos");
    }

    public String toStringKey() {
        return serializeDimensionId(dimensionId)
                + DELIMITER
                + pos.getX() + COORD_DELIMITER + pos.getY() + COORD_DELIMITER + pos.getZ();
    }

    public static ChestKey fromStringKey(String raw) {
        Objects.requireNonNull(raw, "raw");

        String[] keyParts = raw.split("\\" + DELIMITER, 2);
        if (keyParts.length != 2) {
            throw new IllegalArgumentException("Invalid chest key format: " + raw);
        }

        Identifier dimensionId = parseDimensionId(keyParts[0]);
        if (dimensionId == null) {
            throw new IllegalArgumentException("Invalid dimension identifier in chest key: " + raw);
        }

        String[] coords = keyParts[1].split(COORD_DELIMITER, 3);
        if (coords.length != 3) {
            throw new IllegalArgumentException("Invalid coordinates in chest key: " + raw);
        }

        try {
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int z = Integer.parseInt(coords[2]);
            return new ChestKey(dimensionId, new BlockPos(x, y, z));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid number in chest key: " + raw, ex);
        }
    }

    private static String serializeDimensionId(Identifier id) {
        return id.getNamespace() + ":" + id.getPath();
    }

    private static Identifier parseDimensionId(String rawDimensionId) {
        if (rawDimensionId == null) {
            return null;
        }

        String normalized = rawDimensionId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }

        Identifier parsed = Identifier.tryParse(normalized);
        if (parsed != null) {
            return parsed;
        }

        // Legacy support for older saves that stored only the dimension path.
        return Identifier.tryParse(VANILLA_NAMESPACE + ":" + normalized);
    }
}
