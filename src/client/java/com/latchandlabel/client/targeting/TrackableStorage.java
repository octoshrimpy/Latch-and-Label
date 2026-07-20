package com.latchandlabel.client.targeting;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.Container;

/**
 * Identifies which block entity types the mod considers taggable storage containers.
 * Ender chests are excluded: their contents are per-player, not per-block, so tagging one is meaningless.
 */
public final class TrackableStorage {
    private TrackableStorage() {
    }

    public static boolean isTrackableStorage(BlockEntity blockEntity) {
        return blockEntity instanceof Container
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }
}
