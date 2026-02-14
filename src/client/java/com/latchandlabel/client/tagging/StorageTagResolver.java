package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class StorageTagResolver {
    private StorageTagResolver() {
    }

    public static Optional<String> resolveCategoryId(MinecraftClient client, ChestKey key) {
        if (key == null) {
            return Optional.empty();
        }
        if (client == null || client.world == null) {
            return LatchLabelClientState.tagStore().getTag(key);
        }

        World world = client.world;
        if (!key.dimensionId().equals(world.getRegistryKey().getValue())) {
            return LatchLabelClientState.tagStore().getTag(key);
        }

        ChestKey preferredKey = StorageKeyResolver.normalizeForWorld(world, key);
        List<ChestKey> lookupOrder = buildLookupOrder(world, preferredKey);

        for (ChestKey candidate : lookupOrder) {
            Optional<String> categoryId = LatchLabelClientState.tagStore().getTag(candidate);
            if (categoryId.isEmpty()) {
                continue;
            }

            migrateAliasIfNeeded(candidate, preferredKey, categoryId.get());
            return Optional.of(categoryId.get());
        }

        return Optional.empty();
    }

    private static List<ChestKey> buildLookupOrder(World world, ChestKey preferredKey) {
        List<ChestKey> ordered = new ArrayList<>();
        ordered.add(preferredKey);
        for (ChestKey key : StorageKeyResolver.equivalentKeys(world, preferredKey)) {
            if (!ordered.contains(key)) {
                ordered.add(key);
            }
        }
        return List.copyOf(ordered);
    }

    private static void migrateAliasIfNeeded(ChestKey sourceKey, ChestKey preferredKey, String categoryId) {
        if (sourceKey.equals(preferredKey)) {
            return;
        }

        Optional<String> existingPreferred = LatchLabelClientState.tagStore().getTag(preferredKey);
        if (existingPreferred.isEmpty()) {
            LatchLabelClientState.tagStore().setTag(preferredKey, categoryId);
        }
        if (existingPreferred.isEmpty() || Objects.equals(existingPreferred.get(), categoryId)) {
            LatchLabelClientState.tagStore().clearTag(sourceKey);
        }
    }
}
