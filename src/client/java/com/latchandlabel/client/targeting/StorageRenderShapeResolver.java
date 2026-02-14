package com.latchandlabel.client.targeting;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Optional;

public final class StorageRenderShapeResolver {
    private StorageRenderShapeResolver() {
    }

    public static Optional<Box> resolveBox(World world, ChestKey key) {
        if (world == null || key == null) {
            return Optional.empty();
        }
        if (!key.dimensionId().equals(world.getRegistryKey().getValue())) {
            return Optional.empty();
        }

        ChestKey normalized = StorageKeyResolver.normalizeForWorld(world, key);
        if (normalized == null || !world.isChunkLoaded(normalized.pos())) {
            return Optional.empty();
        }
        if (!TrackableStorage.isTrackableStorage(world.getBlockEntity(normalized.pos()))) {
            return Optional.empty();
        }

        BlockPos pos = normalized.pos();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Optional.of(new Box(pos));
        }

        Optional<BlockPos> partnerPos = resolveDoublePartnerPos(world, pos, state);
        if (partnerPos.isEmpty()) {
            return Optional.of(new Box(pos));
        }

        BlockPos partner = partnerPos.get();
        double minX = Math.min(pos.getX(), partner.getX());
        double minY = Math.min(pos.getY(), partner.getY());
        double minZ = Math.min(pos.getZ(), partner.getZ());
        double maxX = Math.max(pos.getX(), partner.getX()) + 1.0;
        double maxY = Math.max(pos.getY(), partner.getY()) + 1.0;
        double maxZ = Math.max(pos.getZ(), partner.getZ()) + 1.0;
        return Optional.of(new Box(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private static Optional<BlockPos> resolveDoublePartnerPos(World world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) {
            return Optional.empty();
        }

        Direction facing = state.get(ChestBlock.FACING);
        Direction partnerDirection = chestType == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
        BlockPos partnerPos = pos.offset(partnerDirection);
        if (!world.isChunkLoaded(partnerPos)) {
            return Optional.empty();
        }

        BlockState partnerState = world.getBlockState(partnerPos);
        if (!(partnerState.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }
        if (partnerState.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return Optional.empty();
        }
        if (partnerState.get(ChestBlock.FACING) != facing) {
            return Optional.empty();
        }

        return Optional.of(partnerPos.toImmutable());
    }
}
