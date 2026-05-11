package com.latchandlabel.client.targeting;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.Optional;

/** Computes the world-space bounding box for a storage container, merging double-chest halves. */
public final class StorageRenderShapeResolver {
    private static final double CHEST_HORIZONTAL_INSET = 1.0 / 16.0;
    private static final double CHEST_MODEL_HEIGHT = 14.0 / 16.0;

    private StorageRenderShapeResolver() {
    }

    public static Optional<AABB> resolveBox(Level world, ChestKey key) {
        if (world == null || key == null) {
            return Optional.empty();
        }
        if (!key.dimensionId().equals(world.dimension().identifier())) {
            return Optional.empty();
        }

        ChestKey normalized = StorageKeyResolver.normalizeForWorld(world, key);
        if (normalized == null || !world.hasChunkAt(normalized.pos())) {
            return Optional.empty();
        }
        if (!TrackableStorage.isTrackableStorage(world.getBlockEntity(normalized.pos()))) {
            return Optional.empty();
        }

        BlockPos pos = normalized.pos();
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Optional.of(new AABB(pos));
        }

        Optional<BlockPos> partnerPos = resolveDoublePartnerPos(world, pos, state);
        if (partnerPos.isEmpty()) {
            return Optional.of(chestModelBox(pos));
        }

        BlockPos partner = partnerPos.get();
        double minX = Math.min(pos.getX(), partner.getX());
        double minY = Math.min(pos.getY(), partner.getY());
        double minZ = Math.min(pos.getZ(), partner.getZ());
        double maxX = Math.max(pos.getX(), partner.getX()) + 1.0;
        double maxY = Math.max(pos.getY(), partner.getY()) + CHEST_MODEL_HEIGHT;
        double maxZ = Math.max(pos.getZ(), partner.getZ()) + 1.0;
        return Optional.of(new AABB(
                minX + CHEST_HORIZONTAL_INSET,
                minY,
                minZ + CHEST_HORIZONTAL_INSET,
                maxX - CHEST_HORIZONTAL_INSET,
                maxY,
                maxZ - CHEST_HORIZONTAL_INSET
        ));
    }

    private static AABB chestModelBox(BlockPos pos) {
        return new AABB(
                pos.getX() + CHEST_HORIZONTAL_INSET,
                pos.getY(),
                pos.getZ() + CHEST_HORIZONTAL_INSET,
                pos.getX() + 1.0 - CHEST_HORIZONTAL_INSET,
                pos.getY() + CHEST_MODEL_HEIGHT,
                pos.getZ() + 1.0 - CHEST_HORIZONTAL_INSET
        );
    }

    private static Optional<BlockPos> resolveDoublePartnerPos(Level world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) {
            return Optional.empty();
        }

        Direction facing = state.getValue(ChestBlock.FACING);
        Direction partnerDirection = chestType == ChestType.LEFT
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        BlockPos partnerPos = pos.relative(partnerDirection);
        if (!world.hasChunkAt(partnerPos)) {
            return Optional.empty();
        }

        BlockState partnerState = world.getBlockState(partnerPos);
        if (!(partnerState.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }
        if (partnerState.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return Optional.empty();
        }
        if (partnerState.getValue(ChestBlock.FACING) != facing) {
            return Optional.empty();
        }

        return Optional.of(partnerPos.immutable());
    }
}
