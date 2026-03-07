package com.latchandlabel.client.find;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Deque;

public final class NearbyChestScanner {
    private static final double REACH_DISTANCE_SQ = 4.5 * 4.5;
    private static final Deque<ChestKey> scanQueue = new ArrayDeque<>();
    private static boolean autoCloseNext = false;
    private static int cooldownTicks = 0;

    private NearbyChestScanner() {
    }

    public static void scheduleNearby(MinecraftClient client, int radius) {
        if (!FindSettings.autoRefreshContents()) {
            return;
        }
        if (client.world == null || client.player == null) {
            return;
        }

        scanQueue.clear();
        World world = client.world;
        PlayerEntity player = client.player;
        double maxDistanceSq = (double) radius * radius;

        int minChunkX = Math.floorDiv((int) Math.floor(player.getX() - radius), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(player.getX() + radius), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(player.getZ() - radius), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(player.getZ() + radius), 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                        continue;
                    }
                    BlockPos pos = blockEntity.getPos();
                    if (player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDistanceSq) {
                        continue;
                    }
                    StorageKeyResolver.resolveForWorld(world, pos).ifPresent(key -> {
                        if (!FindScanService.isRecentlyObserved(key)) {
                            scanQueue.add(key);
                        }
                    });
                }
            }
        }
    }

    public static void onClientTick(MinecraftClient client) {
        if (!FindSettings.autoRefreshContents()) {
            return;
        }
        if (client.player == null || client.world == null) {
            return;
        }

        if (autoCloseNext) {
            if (client.currentScreen instanceof HandledScreen<?>) {
                client.player.closeHandledScreen();
            }
            autoCloseNext = false;
            cooldownTicks = 2;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (scanQueue.isEmpty() || client.currentScreen != null) {
            return;
        }

        ChestKey next = null;
        while (!scanQueue.isEmpty()) {
            ChestKey candidate = scanQueue.poll();
            if (candidate == null) {
                continue;
            }
            if (!candidate.dimensionId().equals(client.world.getRegistryKey().getValue())) {
                continue;
            }
            if (isWithinReach(client.player, candidate)) {
                next = candidate;
                break;
            }
        }

        if (next == null) {
            return;
        }

        BlockPos pos = next.pos();
        BlockHitResult hitResult = new BlockHitResult(
                Vec3d.ofCenter(pos),
                Direction.UP,
                pos,
                false
        );
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        autoCloseNext = true;
    }

    private static boolean isWithinReach(PlayerEntity player, ChestKey key) {
        BlockPos pos = key.pos();
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= REACH_DISTANCE_SQ;
    }
}
