package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class StorageTagReconciler {
    private static final int RECONCILE_INTERVAL_TICKS = 20;
    private static int ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;

    private StorageTagReconciler() {
    }

    public static void onClientTick(MinecraftClient client) {
        if (client == null || client.world == null) {
            ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;
            return;
        }
        if (--ticksUntilReconcile > 0) {
            return;
        }
        ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;

        World world = client.world;
        Map<ChestKey, String> tags = LatchLabelClientState.tagStore().snapshotTags();

        List<ChestKey> toClear = new ArrayList<>();
        List<Map.Entry<ChestKey, ChestKey>> toMigrate = new ArrayList<>();

        for (Map.Entry<ChestKey, String> entry : tags.entrySet()) {
            ChestKey key = entry.getKey();
            if (!key.dimensionId().equals(world.getRegistryKey().getValue())) {
                continue;
            }
            if (!world.isChunkLoaded(key.pos())) {
                continue;
            }

            Optional<ChestKey> resolved = StorageKeyResolver.resolveForWorld(world, key.pos());
            if (resolved.isPresent()) {
                if (!resolved.get().equals(key)) {
                    toMigrate.add(Map.entry(key, resolved.get()));
                }
                continue;
            }

            Optional<ChestKey> splitFallback = resolveSplitFallback(world, key);
            if (splitFallback.isPresent()) {
                toMigrate.add(Map.entry(key, splitFallback.get()));
                continue;
            }

            toClear.add(key);
        }

        for (Map.Entry<ChestKey, ChestKey> migration : toMigrate) {
            migrateTag(migration.getKey(), migration.getValue());
        }
        for (ChestKey key : toClear) {
            LatchLabelClientState.tagStore().clearTag(key);
        }
    }

    private static Optional<ChestKey> resolveSplitFallback(World world, ChestKey missingKey) {
        List<ChestKey> candidates = new ArrayList<>();

        Direction[] horizontal = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction direction : horizontal) {
            BlockPos neighborPos = missingKey.pos().offset(direction);
            if (!world.isChunkLoaded(neighborPos)) {
                continue;
            }

            BlockEntity blockEntity = world.getBlockEntity(neighborPos);
            if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                continue;
            }

            Optional<ChestKey> resolvedNeighbor = StorageKeyResolver.resolveForWorld(world, neighborPos);
            if (resolvedNeighbor.isEmpty()) {
                continue;
            }
            if (!StorageKeyResolver.isLikelyFormerChestCounterpart(world, resolvedNeighbor.get(), missingKey)) {
                continue;
            }

            candidates.add(resolvedNeighbor.get());
        }

        if (candidates.size() != 1) {
            return Optional.empty();
        }

        ChestKey candidate = candidates.get(0);
        if (LatchLabelClientState.tagStore().getTag(candidate).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static void migrateTag(ChestKey source, ChestKey destination) {
        if (source.equals(destination)) {
            return;
        }

        Optional<String> sourceCategory = LatchLabelClientState.tagStore().getTag(source);
        if (sourceCategory.isEmpty()) {
            return;
        }

        Optional<String> destinationCategory = LatchLabelClientState.tagStore().getTag(destination);
        if (destinationCategory.isEmpty()) {
            LatchLabelClientState.tagStore().setTag(destination, sourceCategory.get());
            LatchLabelClientState.tagStore().clearTag(source);
            return;
        }

        if (destinationCategory.get().equals(sourceCategory.get())) {
            LatchLabelClientState.tagStore().clearTag(source);
        }
    }
}
