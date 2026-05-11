package com.latchandlabel.client.tagging;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the most recently right-clicked container block as a fallback for
 * resolving which container a screen belongs to when crosshair targeting fails.
 */
public final class ContainerInteractionTracker {
    private static final long INTERACTION_TTL_MS = 60_000L;

    private record Interaction(ChestKey container, long epochMs) {}
    private static final AtomicReference<Interaction> LAST_INTERACTION = new AtomicReference<>();

    private ContainerInteractionTracker() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide()) {
                return InteractionResult.PASS;
            }

            BlockPos pos = hitResult.getBlockPos();
            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                return InteractionResult.PASS;
            }

            StorageKeyResolver.resolveForWorld(world, pos).ifPresent(resolved ->
                    LAST_INTERACTION.set(new Interaction(resolved, System.currentTimeMillis()))
            );
            return InteractionResult.PASS;
        });
    }

    public static Optional<ChestKey> getRecent() {
        Interaction interaction = LAST_INTERACTION.get();
        if (interaction == null) {
            return Optional.empty();
        }

        long ageMs = System.currentTimeMillis() - interaction.epochMs();
        if (ageMs > INTERACTION_TTL_MS) {
            return Optional.empty();
        }

        return Optional.of(interaction.container());
    }
}
