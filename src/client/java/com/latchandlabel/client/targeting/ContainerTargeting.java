package com.latchandlabel.client.targeting;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Optional;

public final class ContainerTargeting {
    private ContainerTargeting() {
    }

    public static Optional<ChestKey> getTargetedContainer(MinecraftClient client) {
        if (client == null || client.world == null) {
            return Optional.empty();
        }

        HitResult hitResult = client.crosshairTarget;
        if (!(hitResult instanceof BlockHitResult blockHitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }

        World world = client.world;
        BlockPos pos = blockHitResult.getBlockPos();
        BlockEntity blockEntity = world.getBlockEntity(pos);

        if (!TrackableStorage.isTrackableStorage(blockEntity)) {
            return Optional.empty();
        }

        return StorageKeyResolver.resolveForWorld(world, pos);
    }
}
