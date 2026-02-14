package com.latchandlabel.client.tagging;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public final class ContainerInteractionTracker {
    private static final long INTERACTION_TTL_MS = 60_000L;

    private static ChestKey lastInteractedContainer;
    private static long lastInteractionEpochMs;

    private ContainerInteractionTracker() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                return ActionResult.PASS;
            }

            StorageKeyResolver.resolveForWorld(world, pos).ifPresent(resolved -> {
                lastInteractedContainer = resolved;
                lastInteractionEpochMs = System.currentTimeMillis();
            });
            return ActionResult.PASS;
        });
    }

    public static Optional<ChestKey> getRecent() {
        if (lastInteractedContainer == null) {
            return Optional.empty();
        }

        long ageMs = System.currentTimeMillis() - lastInteractionEpochMs;
        if (ageMs > INTERACTION_TTL_MS) {
            return Optional.empty();
        }

        return Optional.of(lastInteractedContainer);
    }
}
