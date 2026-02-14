package com.latchandlabel.client.targeting;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.inventory.Inventory;

public final class TrackableStorage {
    private TrackableStorage() {
    }

    public static boolean isTrackableStorage(BlockEntity blockEntity) {
        return blockEntity instanceof Inventory
                || blockEntity instanceof EnderChestBlockEntity
                || blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof BarrelBlockEntity
                || blockEntity instanceof ShulkerBoxBlockEntity;
    }
}
