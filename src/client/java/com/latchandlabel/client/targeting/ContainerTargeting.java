package com.latchandlabel.client.targeting;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Optional;

/** Resolves the storage container the player's crosshair is currently pointing at. */
public final class ContainerTargeting {
    private ContainerTargeting() {
    }

    public static Optional<ChestKey> getTargetedContainer(Minecraft client) {
        if (client == null || client.level == null) {
            return Optional.empty();
        }

        HitResult hitResult = client.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }

        Level world = client.level;
        BlockPos pos = blockHitResult.getBlockPos();
        BlockEntity blockEntity = world.getBlockEntity(pos);

        if (!TrackableStorage.isTrackableStorage(blockEntity)) {
            return Optional.empty();
        }

        return StorageKeyResolver.resolveForWorld(world, pos);
    }
}
