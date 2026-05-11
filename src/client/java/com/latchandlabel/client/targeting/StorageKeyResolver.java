package com.latchandlabel.client.targeting;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a block position to its canonical {@link ChestKey}, handling double-chest
 * normalization so that both halves share a single key (the one with the lower position).
 */
public final class StorageKeyResolver {
    private StorageKeyResolver() {
    }

    public static Optional<ChestKey> resolveForWorld(Level world, BlockPos pos) {
        if (world == null || pos == null) {
            return Optional.empty();
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!TrackableStorage.isTrackableStorage(blockEntity)) {
            return Optional.empty();
        }

        BlockPos resolvedPos = resolveCanonicalPos(world, pos).orElse(pos).immutable();
        return Optional.of(new ChestKey(world.dimension().identifier(), resolvedPos));
    }

    public static ChestKey normalizeForWorld(Level world, ChestKey key) {
        if (world == null || key == null) {
            return key;
        }
        if (!key.dimensionId().equals(world.dimension().identifier())) {
            return key;
        }

        return resolveForWorld(world, key.pos()).orElse(key);
    }

    public static Set<ChestKey> equivalentKeys(Level world, ChestKey key) {
        if (world == null || key == null) {
            return Set.of();
        }

        Identifier dimensionId = world.dimension().identifier();
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

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) {
            Direction facing = state.getValue(ChestBlock.FACING);
            addPotentialStaleCounterpart(world, dimensionId, keys, key.pos().relative(facing.getClockWise()));
            addPotentialStaleCounterpart(world, dimensionId, keys, key.pos().relative(facing.getCounterClockWise()));
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

    public static boolean isLikelyFormerChestCounterpart(Level world, ChestKey singleChestKey, ChestKey candidateAlias) {
        if (world == null || singleChestKey == null || candidateAlias == null) {
            return false;
        }
        return equivalentKeys(world, singleChestKey).contains(candidateAlias);
    }

    private static void addPotentialStaleCounterpart(Level world, Identifier dimensionId, Set<ChestKey> keys, BlockPos neighborPos) {
        if (TrackableStorage.isTrackableStorage(world.getBlockEntity(neighborPos))) {
            return;
        }
        keys.add(new ChestKey(dimensionId, neighborPos.immutable()));
    }

    private static Optional<BlockPos> resolveCanonicalPos(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) {
            return Optional.empty();
        }

        ChestType chestType = state.getValue(ChestBlock.TYPE);
        if (chestType == ChestType.SINGLE) {
            return Optional.of(pos);
        }

        return resolveDoublePartnerPos(world, pos, state).map(partnerPos -> lowerPos(pos, partnerPos));
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
