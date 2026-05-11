package com.latchandlabel.client.tagging;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the category ID assigned to a storage container, accounting for
 * double-chest normalization so both halves share the same tag.
 */
public final class StorageTagResolver {
    private StorageTagResolver() {
    }

    public static Optional<String> resolveCategoryId(TagStore tagStore, Minecraft client, ChestKey key) {
        Objects.requireNonNull(tagStore, "tagStore");
        if (key == null) {
            return Optional.empty();
        }
        if (client == null || client.level == null) {
            return tagStore.getTag(key);
        }

        Level world = client.level;
        if (!key.dimensionId().equals(world.dimension().identifier())) {
            return tagStore.getTag(key);
        }

        ChestKey preferredKey = StorageKeyResolver.normalizeForWorld(world, key);
        List<ChestKey> lookupOrder = buildLookupOrder(world, preferredKey);

        for (ChestKey candidate : lookupOrder) {
            Optional<String> categoryId = tagStore.getTag(candidate);
            if (categoryId.isEmpty()) {
                continue;
            }

            migrateAliasIfNeeded(tagStore, candidate, preferredKey, categoryId.get());
            return Optional.of(categoryId.get());
        }

        return Optional.empty();
    }

    private static List<ChestKey> buildLookupOrder(Level world, ChestKey preferredKey) {
        List<ChestKey> ordered = new ArrayList<>();
        ordered.add(preferredKey);
        for (ChestKey key : StorageKeyResolver.equivalentKeys(world, preferredKey)) {
            if (!ordered.contains(key)) {
                ordered.add(key);
            }
        }
        return List.copyOf(ordered);
    }

    private static void migrateAliasIfNeeded(TagStore tagStore, ChestKey sourceKey, ChestKey preferredKey, String categoryId) {
        if (sourceKey.equals(preferredKey)) {
            return;
        }

        Optional<String> existingPreferred = tagStore.getTag(preferredKey);
        if (existingPreferred.isEmpty()) {
            tagStore.setTag(preferredKey, categoryId);
        }
        if (existingPreferred.isEmpty() || Objects.equals(existingPreferred.get(), categoryId)) {
            tagStore.clearTag(sourceKey);
        }
    }
}
