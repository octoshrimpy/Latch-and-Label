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
            ChestType chestType = state.getValue(ChestBlock.TYPE);
            if (chestType != ChestType.SINGLE) {
                Direction facing = state.getValue(ChestBlock.FACING);
                Direction partnerDir = chestType == ChestType.LEFT
                        ? facing.getClockWise()
                        : facing.getCounterClockWise();
                BlockPos partnerPos = pos.relative(partnerDir);
                BlockEntity partnerEntity = world.getBlockEntity(partnerPos);
                if (partnerEntity instanceof Container partnerInv && !isInventoryFull(partnerInv)) {
                    return false;
                }
            }
        }

        return isInventoryFull(inventory);
    }

    private static boolean isInventoryFull(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getItem().getDefaultMaxStackSize()) {
                return false;
            }
        }
        return true;
    }
}
