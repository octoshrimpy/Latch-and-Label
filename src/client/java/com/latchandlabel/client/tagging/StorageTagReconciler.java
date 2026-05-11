package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Periodically reconciles stored tags against the actual world state.
 * Migrates stale keys when a double chest is split or merged, and removes
 * tags for positions that no longer contain trackable storage.
 */
public final class StorageTagReconciler {
    private static final int RECONCILE_INTERVAL_TICKS = 20;

    private final TagStore tagStore;
    private int ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;
    private volatile boolean scopeReady = false;

    public StorageTagReconciler(TagStore tagStore) {
        this.tagStore = tagStore;
    }

    public void markScopeReady() {
        scopeReady = true;
    }

    public void markScopeNotReady() {
        scopeReady = false;
    }

    public void onClientTick(Minecraft client) {
        if (client == null || client.level == null) {
            ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;
            return;
        }
        if (--ticksUntilReconcile > 0) {
            return;
        }
        ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;

        Level world = client.level;
        var dimId = world.dimension().identifier();
        reconcileTagsInScope(world, key ->
                key.dimensionId().equals(dimId) && world.hasChunkAt(key.pos()), "tick", client.hasSingleplayerServer());
    }

    public void onChunkLoad(ClientLevel world, LevelChunk chunk) {
        if (!scopeReady) {
            return;
        }
        ChunkPos chunkPos = chunk.getPos();
        var dimId = world.dimension().identifier();
        reconcileTagsInScope(world, key ->
                key.dimensionId().equals(dimId) && ChunkPos.containing(key.pos()).equals(chunkPos),
                "chunkLoad",
                Minecraft.getInstance().hasSingleplayerServer());
    }

    private void reconcileTagsInScope(Level world, Predicate<ChestKey> keyFilter, String context, boolean removeMissingStorage) {
        Map<ChestKey, String> tags = tagStore.snapshotTags();

        List<Map.Entry<ChestKey, ChestKey>> toMigrate = new ArrayList<>();
        List<ChestKey> toRemove = new ArrayList<>();

        for (Map.Entry<ChestKey, String> entry : tags.entrySet()) {
            ChestKey key = entry.getKey();
            if (!keyFilter.test(key)) {
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
            } else if (removeMissingStorage) {
                toRemove.add(key);
            }
        }

        if (!toMigrate.isEmpty() || !toRemove.isEmpty()) {
            LatchLabel.LOGGER.debug("[Reconciler] {}: checked={} migrate={} remove={}",
                    context, tags.size(), toMigrate.size(), toRemove.size());
        }
        for (Map.Entry<ChestKey, ChestKey> migration : toMigrate) {
            migrateTag(migration.getKey(), migration.getValue());
        }
        for (ChestKey key : toRemove) {
            tagStore.clearTag(key);
        }
    }

    private Optional<ChestKey> resolveSplitFallback(Level world, ChestKey missingKey) {
        List<ChestKey> candidates = new ArrayList<>();

        Direction[] horizontal = new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction direction : horizontal) {
            BlockPos neighborPos = missingKey.pos().relative(direction);
            if (!world.hasChunkAt(neighborPos)) {
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
        if (tagStore.getTag(candidate).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private void migrateTag(ChestKey source, ChestKey destination) {
        if (source.equals(destination)) {
            return;
        }

        Optional<String> sourceCategory = tagStore.getTag(source);
        if (sourceCategory.isEmpty()) {
            return;
        }

        Optional<String> destinationCategory = tagStore.getTag(destination);
        if (destinationCategory.isEmpty()) {
            LatchLabel.LOGGER.debug("[Reconciler] migrateTag {} -> {} cat={}", source, destination, sourceCategory.get());
            tagStore.setTag(destination, sourceCategory.get());
            tagStore.clearTag(source);
            return;
        }

        if (destinationCategory.get().equals(sourceCategory.get())) {
            LatchLabel.LOGGER.debug("[Reconciler] migrateTag {} -> {} (same cat, clearing source)", source, destination);
            tagStore.clearTag(source);
        }
    }
}
