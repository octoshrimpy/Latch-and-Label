package com.latchandlabel.client.targeting;

import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.Container;

/** Identifies which block entity types the mod considers taggable storage containers. */
public final class TrackableStorage {
    private TrackableStorage() {
    }

    public static boolean isTrackableStorage(BlockEntity blockEntity) {
        return blockEntity instanceof Container
                || blockEntity instanceof EnderChestBlockEntity
                || blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }
}
