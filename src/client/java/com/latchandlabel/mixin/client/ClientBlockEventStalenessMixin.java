package com.latchandlabel.mixin.client;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks a known container's stored contents as possibly-stale when a nearby player opens it — i.e.
 * someone touched it since we last saw inside. Contents might still be accurate, so we keep them for
 * {@code /find} but flag the uncertainty.
 *
 * <p>Two open signals, because vanilla animates them differently:
 * <ul>
 *   <li>Chests / shulker boxes → block event id 1 (the container viewer-count lid animation).</li>
 *   <li>Barrels → no block event; they toggle the {@code OPEN} blockstate, arriving as a block update.</li>
 * </ul>
 * ponytail: batched section updates ({@code ClientboundSectionBlocksUpdatePacket}) aren't hooked; a lone
 * barrel toggle sends a single block update, so this covers the normal case.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientBlockEventStalenessMixin {

    @Inject(method = "handleBlockEvent", at = @At("HEAD"))
    private void latchlabel$onBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        if (packet.getB0() == 1) {
            latchlabel$markStaleAt(packet.getPos());
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void latchlabel$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        BlockState state = packet.getBlockState();
        if (state.getBlock() instanceof BarrelBlock && state.getValue(BarrelBlock.OPEN)) {
            latchlabel$markStaleAt(packet.getPos());
        }
    }

    private void latchlabel$markStaleAt(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        // HEAD runs before vanilla's ensureRunningOnSameThread reschedule, so the first pass is on the
        // netty thread — bail there and act only on the main-thread re-invocation.
        if (client == null || client.player == null || client.level == null || !client.isSameThread()) {
            return;
        }
        // Skip while we have a container open: our own opens re-observe fresh contents and shouldn't self-flag.
        if (McCompat.getScreen(client) instanceof AbstractContainerScreen) {
            return;
        }
        StorageKeyResolver.resolveForWorld(client.level, pos)
                .ifPresent(key -> LatchLabelClientState.observedIndexStore().markStale(key));
    }
}
