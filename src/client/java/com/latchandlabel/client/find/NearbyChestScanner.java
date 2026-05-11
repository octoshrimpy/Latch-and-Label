package com.latchandlabel.client.find;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

public final class NearbyChestScanner {
    private static final double REACH_DISTANCE_SQ = 4.5 * 4.5;
    private static final Deque<ChestKey> scanQueue = new ArrayDeque<>();
    private static PendingOpen pendingOpen;
    private static int cooldownTicks = 0;
    private static Query query = Query.none();

    private NearbyChestScanner() {
    }

    public static void scheduleNearby(Minecraft client, int radius) {
        scheduleNearby(client, radius, Query.none());
    }

    public static void scheduleNearby(Minecraft client, int radius, Item exactItem, Set<Item> variantItems) {
        scheduleNearby(client, radius, Query.items(exactItem, variantItems));
    }

    public static void scheduleNearbyByCategory(Minecraft client, int radius, String categoryId) {
        scheduleNearby(client, radius, Query.category(categoryId));
    }

    private static void scheduleNearby(Minecraft client, int radius, Query nextQuery) {
        if (!FindSettings.autoRefreshContents()) {
            return;
        }
        if (client.level == null || client.player == null) {
            return;
        }

        scanQueue.clear();
        pendingOpen = null;
        query = nextQuery == null ? Query.none() : nextQuery;
        Level world = client.level;
        Player player = client.player;
        double maxDistanceSq = (double) radius * radius;

        int minChunkX = Math.floorDiv((int) Math.floor(player.getX() - radius), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(player.getX() + radius), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(player.getZ() - radius), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(player.getZ() + radius), 16);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                LevelChunk chunk = world.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                        continue;
                    }
                    BlockPos pos = blockEntity.getBlockPos();
                    if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > maxDistanceSq) {
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

    public static void onClientTick(Minecraft client) {
        if (!FindSettings.autoRefreshContents()) {
            return;
        }
        if (client.player == null || client.level == null) {
            return;
        }

        if (pendingOpen != null) {
            if (client.gui.screen() instanceof AbstractContainerScreen<?>) {
                if (openedScreenMatches(client, pendingOpen.target())) {
                    boolean matched = screenContainsQuery(client);
                    if (matched) {
                        pendingOpen = null;
                        scanQueue.clear();
                        cooldownTicks = 0;
                        return;
                    }

                    client.player.closeContainer();
                    pendingOpen = null;
                    cooldownTicks = 2;
                    return;
                }
            }
            if (pendingOpen.remainingTicks() <= 0) {
                pendingOpen = null;
                cooldownTicks = 2;
                return;
            }
            pendingOpen = pendingOpen.withRemainingTicks(pendingOpen.remainingTicks() - 1);
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (scanQueue.isEmpty() || client.gui.screen() != null) {
            return;
        }

        ChestKey next = null;
        while (!scanQueue.isEmpty()) {
            ChestKey candidate = scanQueue.poll();
            if (candidate == null) {
                continue;
            }
            if (!candidate.dimensionId().equals(client.level.dimension().identifier())) {
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
                Vec3.atCenterOf(pos),
                Direction.UP,
                pos,
                false
        );
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        pendingOpen = new PendingOpen(next, 20);
    }

    private static boolean isWithinReach(Player player, ChestKey key) {
        BlockPos pos = key.pos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= REACH_DISTANCE_SQ;
    }

    private static boolean openedScreenMatches(Minecraft client, ChestKey expected) {
        return com.latchandlabel.client.tagging.ContainerScreenContextResolver.resolve(client, client.gui.screen())
                .filter(expected::equals)
                .isPresent();
    }

    private static boolean screenContainsQuery(Minecraft client) {
        if (query.isNone()) {
            return false;
        }

        AbstractContainerMenu handler = client.player.containerMenu;
        for (var slot : handler.slots) {
            if (slot.container instanceof Container) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (query.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    private record PendingOpen(ChestKey target, int remainingTicks) {
        private PendingOpen withRemainingTicks(int ticks) {
            return new PendingOpen(target, ticks);
        }
    }

    private record Query(Item exactItem, Set<Item> variantItems, String categoryId) {
        private static Query none() {
            return new Query(null, Set.of(), null);
        }

        private static Query items(Item exactItem, Set<Item> variantItems) {
            return new Query(exactItem, variantItems == null ? Set.of() : Set.copyOf(variantItems), null);
        }

        private static Query category(String categoryId) {
            return new Query(null, Set.of(), categoryId);
        }

        private boolean isNone() {
            return exactItem == null && variantItems.isEmpty() && (categoryId == null || categoryId.isBlank());
        }

        private boolean matches(ItemStack stack) {
            if (exactItem != null && stack.getItem() == exactItem) {
                return true;
            }
            if (variantItems.contains(stack.getItem())) {
                return true;
            }
            return categoryId != null && com.latchandlabel.client.LatchLabelClientState.itemCategoryMappingService()
                    .categoryIdFor(stack)
                    .filter(categoryId::equals)
                    .isPresent();
        }
    }
}
