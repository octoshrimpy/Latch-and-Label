package com.latchandlabel.client.inspect;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

final class StorageFullness {
    private StorageFullness() {
    }

    static boolean isStorageFull(Level world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof Container inventory)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
            if (chestType != ChestType.SINGLE) {
                Direction facing = state.get(ChestBlock.FACING);
                Direction partnerDir = chestType == ChestType.LEFT
                        ? facing.rotateYClockwise()
                        : facing.rotateYCounterclockwise();
                BlockPos partnerPos = pos.offset(partnerDir);
                BlockEntity partnerEntity = world.getBlockEntity(partnerPos);
                if (partnerEntity instanceof Container partnerInv && !isInventoryFull(partnerInv)) {
                    return false;
                }
            }
        }

        return isInventoryFull(inventory);
    }

    private static boolean isInventoryFull(Container inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxCount()) {
                return false;
            }
        }
        return true;
    }
}
