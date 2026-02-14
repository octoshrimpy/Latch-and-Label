package com.latchandlabel.client.targeting;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class StorageKeyResolver {
    private StorageKeyResolver() {
    }

    public static Optional<ChestKey> resolveForWorld(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return Optional.empty();
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!TrackableStorage.isTrackableStorage(blockEntity)) {
            return Optional.empty();
        }

        BlockPos resolvedPos = resolveCanonicalPos(world, pos).orElse(pos).toImmutable();
        return Optional.of(new ChestKey(world.getRegistryKey().getValue(), resolvedPos));
    }

    public static ChestKey normalizeForWorld(World world, ChestKey key) {
        if (world == null || key == null) {
            return key;
        }
        if (!key.dimensionId().equals(world.getRegistryKey().getValue())) {
            return key;
        }

        return resolveForWorld(world, key.pos()).orElse(key);
    }

    public static Set<ChestKey> equivalentKeys(World world, ChestKey key) {
        if (world == null || key == null) {
            return Set.of();
        }

        Identifier dimensionId = world.getRegistryKey().getValue();
        if (!dimensionId.equals(key.dimensionId())) {
            return Set.of(key);
        }

        Set<ChestKey> keys = new LinkedHashSet<>();
        keys.add(key);

        BlockState state = world.getBlockState(key.pos());
        if (!(state.getBlock() instanceof ChestBlock)) {
            resolveForWorld(world, key.pos()).ifPresent(keys::add);
            return Set.copyOf(keys);
        }

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) {
            Direction facing = state.get(ChestBlock.FACING);
            addPotentialStaleCounterpart(world, dimensionId, keys, key.pos().offset(facing.rotateYClockwise()));
            addPotentialStaleCounterpart(world, dimensionId, keys, key.pos().offset(facing.rotateYCounterclockwise()));
            keys.add(normalizeForWorld(world, key));
            return Set.copyOf(keys);
        }

        resolveDoublePartnerPos(world, key.pos(), state).ifPresent(partnerPos -> {
            keys.add(new ChestKey(dimensionId, partnerPos));
            keys.add(new ChestKey(dimensionId, lowerPos(key.pos(), partnerPos)));
        });
        keys.add(normalizeForWorld(world, key));
        return Set.copyOf(keys);
    }

    public static boolean isLikelyFormerChestCounterpart(World world, ChestKey singleChestKey, ChestKey candidateAlias) {
        if (world == null || singleChestKey == null || candidateAlias == null) {
            return false;
        }
        return equivalentKeys(world, singleChestKey).contains(candidateAlias);
    }

    private static void addPotentialStaleCounterpart(World world, Identifier dimensionId, Set<ChestKey> keys, BlockPos neighborPos) {
        if (TrackableStorage.isTrackableStorage(world.getBlockEntity(neighborPos))) {
            return;
        }
        keys.add(new ChestKey(dimensionId, neighborPos.toImmutable()));
    }

    private static Optional<BlockPos> resolveCanonicalPos(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }

        ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) {
            return Optional.of(pos);
        }

        return resolveDoublePartnerPos(world, pos, state).map(partnerPos -> lowerPos(pos, partnerPos));
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

    private static BlockPos lowerPos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return a.getX() <= b.getX() ? a : b;
        }
        if (a.getY() != b.getY()) {
            return a.getY() <= b.getY() ? a : b;
        }
        return a.getZ() <= b.getZ() ? a : b;
    }
}
