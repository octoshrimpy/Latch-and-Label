package com.latchandlabel.client.find;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.tagging.ContainerInteractionTracker;
import com.latchandlabel.client.tagging.ContainerScreenContextResolver;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class FindScanService {
    private static final int MAX_SCANNED_CONTAINERS = 512;
    private static final long OBSERVED_CACHE_TTL_MS = 5 * 60_000L;
    private static final int OBSERVED_CACHE_MAX_ENTRIES = 1024;
    private static final java.util.Map<ChestKey, ObservedContainerContents> OBSERVED_CONTAINERS = new HashMap<>();

    public List<FindMatch> scan(MinecraftClient client, Item targetItem, Set<Item> matchSet, int radius) {
        if (client == null || client.world == null || client.player == null) {
            return List.of();
        }

        World world = client.world;
        PlayerEntity player = client.player;
        Identifier dimensionId = world.getRegistryKey().getValue();
        double maxDistanceSq = (double) radius * radius;
        Optional<ChestKey> currentScreenChestKey = resolveCurrentScreenChestKey(client);
        cacheOpenScreenContents(client, currentScreenChestKey);
        pruneObservedCache();

        List<FindMatch> matches = new ArrayList<>();
        Set<ChestKey> candidates = candidateContainers(client, radius, currentScreenChestKey);
        int scanned = 0;

        for (ChestKey chestKey : candidates) {
            if (scanned >= MAX_SCANNED_CONTAINERS) {
                break;
            }
            if (!isCandidateInScope(chestKey, dimensionId, player, maxDistanceSq, world)) {
                continue;
            }

            BlockPos pos = chestKey.pos();
            BlockEntity blockEntity = world.getBlockEntity(pos);
            MatchType matchType = resolveMatchType(client, currentScreenChestKey, chestKey, blockEntity, targetItem, matchSet);
            if (blockEntity instanceof Inventory inventory && TrackableStorage.isTrackableStorage(blockEntity)) {
                scanned++;
            }
            if (matchType == MatchType.NONE) {
                continue;
            }

            double distance = Math.sqrt(player.squaredDistanceTo(
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5
            ));

            matches.add(new FindMatch(chestKey, matchType, distance));
        }

        matches.sort(Comparator
                .comparing(FindMatch::matchType)
                .thenComparingDouble(FindMatch::distance));
        return List.copyOf(matches);
    }

    private static boolean isCandidateInScope(
            ChestKey chestKey,
            Identifier dimensionId,
            PlayerEntity player,
            double maxDistanceSq,
            World world
    ) {
        if (!chestKey.dimensionId().equals(dimensionId)) {
            return false;
        }
        BlockPos pos = chestKey.pos();
        return isWithinRange(player, pos, maxDistanceSq) && world.isChunkLoaded(pos);
    }

    private static MatchType resolveMatchType(
            MinecraftClient client,
            Optional<ChestKey> currentScreenChestKey,
            ChestKey chestKey,
            BlockEntity blockEntity,
            Item targetItem,
            Set<Item> matchSet
    ) {
        MatchType matchType = MatchType.NONE;
        if (blockEntity instanceof Inventory inventory && TrackableStorage.isTrackableStorage(blockEntity)) {
            matchType = detectMatchType(inventory, targetItem, matchSet);
        }
        if (matchType == MatchType.NONE) {
            matchType = detectMatchTypeFromOpenScreen(client, currentScreenChestKey, chestKey, targetItem, matchSet);
        }
        if (matchType == MatchType.NONE) {
            matchType = detectMatchTypeFromObservedCache(chestKey, targetItem, matchSet);
        }
        return matchType;
    }

    public static void onClientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }
        cacheOpenScreenContents(client, resolveCurrentScreenChestKey(client));
        pruneObservedCache();
    }

    private static Optional<ChestKey> resolveCurrentScreenChestKey(MinecraftClient client) {
        if (client.currentScreen == null) {
            return Optional.empty();
        }
        return ContainerScreenContextResolver.resolve(client, client.currentScreen);
    }

    private static void cacheOpenScreenContents(MinecraftClient client, Optional<ChestKey> currentScreenChestKey) {
        if (client.player == null) {
            return;
        }
        if (currentScreenChestKey.isEmpty()) {
            return;
        }

        Set<Item> observedItems = new LinkedHashSet<>();
        for (var slot : client.player.currentScreenHandler.slots) {
            if (slot.inventory instanceof PlayerInventory) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }

            observedItems.add(stack.getItem());
        }
        OBSERVED_CONTAINERS.put(
                currentScreenChestKey.get(),
                new ObservedContainerContents(Set.copyOf(observedItems), System.currentTimeMillis())
        );
    }

    private static void pruneObservedCache() {
        long now = System.currentTimeMillis();
        OBSERVED_CONTAINERS.entrySet().removeIf(entry -> (now - entry.getValue().observedAtEpochMs()) > OBSERVED_CACHE_TTL_MS);
        if (OBSERVED_CONTAINERS.size() <= OBSERVED_CACHE_MAX_ENTRIES) {
            return;
        }

        int removeCount = OBSERVED_CONTAINERS.size() - OBSERVED_CACHE_MAX_ENTRIES;
        List<ChestKey> oldestKeys = OBSERVED_CONTAINERS.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().observedAtEpochMs()))
                .limit(removeCount)
                .map(Map.Entry::getKey)
                .toList();
        oldestKeys.forEach(OBSERVED_CONTAINERS::remove);
    }

    private static Set<ChestKey> candidateContainers(MinecraftClient client, int radius, Optional<ChestKey> currentScreenChestKey) {
        if (client == null || client.world == null || client.player == null) {
            return Set.of();
        }

        Set<ChestKey> candidates = new LinkedHashSet<>(nearbyInventoryContainers(client.world, client.player, radius));
        candidates.addAll(OBSERVED_CONTAINERS.keySet());
        candidates.addAll(LatchLabelClientState.tagStore().snapshotTags().keySet());
        ContainerInteractionTracker.getRecent().ifPresent(candidates::add);
        currentScreenChestKey.ifPresent(candidates::add);
        return candidates;
    }

    private static Set<ChestKey> nearbyInventoryContainers(World world, PlayerEntity player, int searchRadius) {
        int minChunkX = Math.floorDiv((int) Math.floor(player.getX() - searchRadius), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(player.getX() + searchRadius), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(player.getZ() - searchRadius), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(player.getZ() + searchRadius), 16);
        double maxDistanceSq = (double) searchRadius * searchRadius;

        Set<ChestKey> discovered = new LinkedHashSet<>();
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
                    if (!isWithinRange(player, pos, maxDistanceSq)) {
                        continue;
                    }

                    StorageKeyResolver.resolveForWorld(world, pos).ifPresent(discovered::add);
                    if (discovered.size() >= MAX_SCANNED_CONTAINERS) {
                        return discovered;
                    }
                }
            }
        }

        return discovered;
    }

    private static boolean isWithinRange(PlayerEntity player, BlockPos pos, double maxDistanceSq) {
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= maxDistanceSq;
    }

    private static MatchType detectMatchType(Inventory inventory, Item targetItem, Set<Item> matchSet) {
        boolean variantFound = false;

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (matchesBaseItem(stack, targetItem)) {
                return MatchType.EXACT;
            }
            if (matchesVariantBaseItem(stack, matchSet)) {
                variantFound = true;
            }
        }

        return variantFound ? MatchType.VARIANT : MatchType.NONE;
    }

    private static MatchType detectMatchTypeFromOpenScreen(
            MinecraftClient client,
            Optional<ChestKey> currentScreenChestKey,
            ChestKey chestKey,
            Item targetItem,
            Set<Item> matchSet
    ) {
        if (client.player == null) {
            return MatchType.NONE;
        }
        if (currentScreenChestKey.filter(chestKey::equals).isEmpty()) {
            return MatchType.NONE;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        boolean variantFound = false;
        for (var slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (matchesBaseItem(stack, targetItem)) {
                return MatchType.EXACT;
            }
            if (matchesVariantBaseItem(stack, matchSet)) {
                variantFound = true;
            }
        }
        return variantFound ? MatchType.VARIANT : MatchType.NONE;
    }

    private static MatchType detectMatchTypeFromObservedCache(ChestKey chestKey, Item targetItem, Set<Item> matchSet) {
        ObservedContainerContents observed = OBSERVED_CONTAINERS.get(chestKey);
        if (observed == null) {
            return MatchType.NONE;
        }

        if (observed.items().contains(targetItem)) {
            return MatchType.EXACT;
        }
        for (Item item : observed.items()) {
            if (matchSet.contains(item)) {
                return MatchType.VARIANT;
            }
        }
        return MatchType.NONE;
    }

    private static boolean matchesBaseItem(ItemStack stack, Item targetItem) {
        return stack.getItem() == targetItem;
    }

    private static boolean matchesVariantBaseItem(ItemStack stack, Set<Item> matchSet) {
        return matchSet.contains(stack.getItem());
    }

    public enum MatchType {
        EXACT,
        VARIANT,
        NONE
    }

    public record FindMatch(ChestKey chestKey, MatchType matchType, double distance) {
    }

    private record ObservedContainerContents(Set<Item> items, long observedAtEpochMs) {
    }
}
