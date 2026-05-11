package com.latchandlabel.client.find;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.tagging.ContainerInteractionTracker;
import com.latchandlabel.client.tagging.ContainerScreenContextResolver;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class FindScanService {
    private static final int MAX_SCANNED_CONTAINERS = 512;
    private static final long OBSERVED_CACHE_TTL_MS = 5 * 60_000L;
    private static final int OBSERVED_CACHE_MAX_ENTRIES = 1024;
    private static final Map<ChestKey, ObservedContainerContents> OBSERVED_CONTAINERS = new ConcurrentHashMap<>();

    public List<FindMatch> scan(Minecraft client, Item targetItem, Set<Item> matchSet, int radius) {
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }

        Level world = client.level;
        Player player = client.player;
        ResourceLocation dimensionId = world.dimension().location();
        double maxDistanceSq = (double) radius * radius;
        String targetCategoryId = LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(BuiltInRegistries.ITEM.getKey(targetItem).location())
                .orElse(null);
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
            MatchType matchType = resolveMatchType(client, currentScreenChestKey, chestKey, blockEntity, targetItem, matchSet, targetCategoryId);
            if (blockEntity instanceof Container inventory && TrackableStorage.isTrackableStorage(blockEntity)) {
                scanned++;
            }
            if (matchType == MatchType.NONE) {
                continue;
            }

            double distance = Math.sqrt(player.distanceToSqr(
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
            ResourceLocation dimensionId,
            Player player,
            double maxDistanceSq,
            Level world
    ) {
        if (!chestKey.dimensionId().equals(dimensionId)) {
            return false;
        }
        BlockPos pos = chestKey.pos();
        return isWithinRange(player, pos, maxDistanceSq) && world.hasChunkAt(pos);
    }

    private static MatchType resolveMatchType(
            Minecraft client,
            Optional<ChestKey> currentScreenChestKey,
            ChestKey chestKey,
            BlockEntity blockEntity,
            Item targetItem,
            Set<Item> matchSet,
            String targetCategoryId
    ) {
        MatchType matchType = MatchType.NONE;
        if (blockEntity instanceof Container inventory && TrackableStorage.isTrackableStorage(blockEntity)) {
            matchType = detectMatchType(inventory, targetItem, matchSet);
        }
        if (matchType == MatchType.NONE) {
            matchType = detectMatchTypeFromOpenScreen(client, currentScreenChestKey, chestKey, targetItem, matchSet);
        }
        if (matchType == MatchType.NONE) {
            matchType = detectMatchTypeFromObservedCache(chestKey, targetItem, matchSet);
        }
        if (matchType == MatchType.NONE) {
            matchType = detectCategoryPossibleMatch(chestKey, targetCategoryId, blockEntity);
        }
        return matchType;
    }

    public List<FindMatch> scanByTag(Minecraft client, String categoryId, int radius) {
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }

        Level world = client.level;
        Player player = client.player;
        ResourceLocation dimensionId = world.dimension().location();
        double maxDistanceSq = (double) radius * radius;
        Optional<ChestKey> currentScreenChestKey = resolveCurrentScreenChestKey(client);
        cacheOpenScreenContents(client, currentScreenChestKey);
        pruneObservedCache();

        List<FindMatch> matches = new ArrayList<>();
        Set<ChestKey> candidates = candidateContainers(client, radius, currentScreenChestKey);

        for (ChestKey chestKey : candidates) {
            if (!isCandidateInScope(chestKey, dimensionId, player, maxDistanceSq, world)) {
                continue;
            }
            boolean isTagged = LatchLabelClientState.tagStore()
                    .getTag(chestKey)
                    .filter(categoryId::equals)
                    .isPresent();
            if (!isTagged) {
                continue;
            }
            BlockPos pos = chestKey.pos();
            double distance = Math.sqrt(player.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            matches.add(new FindMatch(chestKey, MatchType.POSSIBLE, distance));
        }

        matches.sort(Comparator.comparingDouble(FindMatch::distance));
        return List.copyOf(matches);
    }

    public static boolean isRecentlyObserved(ChestKey key) {
        ObservedContainerContents entry = OBSERVED_CONTAINERS.get(key);
        if (entry == null) {
            return false;
        }
        return (System.currentTimeMillis() - entry.observedAtEpochMs()) <= OBSERVED_CACHE_TTL_MS;
    }

    public static void onClientTick(Minecraft client) {
        if (client == null) {
            return;
        }
        cacheOpenScreenContents(client, resolveCurrentScreenChestKey(client));
        pruneObservedCache();
    }

    private static Optional<ChestKey> resolveCurrentScreenChestKey(Minecraft client) {
        if (client.currentScreen == null) {
            return Optional.empty();
        }
        return ContainerScreenContextResolver.resolve(client, client.currentScreen);
    }

    private static void cacheOpenScreenContents(Minecraft client, Optional<ChestKey> currentScreenChestKey) {
        if (client.player == null) {
            return;
        }
        if (currentScreenChestKey.isEmpty()) {
            return;
        }

        Set<Item> observedItems = new LinkedHashSet<>();
        for (var slot : client.player.containerMenu.slots) {
            if (slot.inventory instanceof Container) {
                continue;
            }

            ItemStack stack = slot.getItem();
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

    private static Set<ChestKey> candidateContainers(Minecraft client, int radius, Optional<ChestKey> currentScreenChestKey) {
        if (client == null || client.level == null || client.player == null) {
            return Set.of();
        }

        Set<ChestKey> candidates = new LinkedHashSet<>(nearbyInventoryContainers(client.level, client.player, radius));
        candidates.addAll(OBSERVED_CONTAINERS.keySet());
        candidates.addAll(LatchLabelClientState.tagStore().snapshotTags().keySet());
        ContainerInteractionTracker.getRecent().ifPresent(candidates::add);
        currentScreenChestKey.ifPresent(candidates::add);
        return candidates;
    }

    private static Set<ChestKey> nearbyInventoryContainers(Level world, Player player, int searchRadius) {
        int minChunkX = Math.floorDiv((int) Math.floor(player.getX() - searchRadius), 16);
        int maxChunkX = Math.floorDiv((int) Math.floor(player.getX() + searchRadius), 16);
        int minChunkZ = Math.floorDiv((int) Math.floor(player.getZ() - searchRadius), 16);
        int maxChunkZ = Math.floorDiv((int) Math.floor(player.getZ() + searchRadius), 16);
        double maxDistanceSq = (double) searchRadius * searchRadius;

        Set<ChestKey> discovered = new LinkedHashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.hasChunkAt(chunkX, chunkZ)) {
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

    private static boolean isWithinRange(Player player, BlockPos pos, double maxDistanceSq) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= maxDistanceSq;
    }

    private static MatchType detectMatchType(Container inventory, Item targetItem, Set<Item> matchSet) {
        boolean variantFound = false;

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getItem(slot);
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
            Minecraft client,
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

        AbstractContainerMenu handler = client.player.containerMenu;
        boolean variantFound = false;
        for (var slot : handler.slots) {
            if (slot.inventory instanceof Container) {
                continue;
            }

            ItemStack stack = slot.getItem();
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

    private static MatchType detectCategoryPossibleMatch(ChestKey chestKey, String targetCategoryId, BlockEntity blockEntity) {
        if (targetCategoryId == null || targetCategoryId.isBlank()) {
            return MatchType.NONE;
        }
        if (!TrackableStorage.isTrackableStorage(blockEntity)) {
            return MatchType.NONE;
        }
        return LatchLabelClientState.tagStore()
                .getTag(chestKey)
                .filter(targetCategoryId::equals)
                .map(unused -> MatchType.POSSIBLE)
                .orElse(MatchType.NONE);
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
        POSSIBLE,
        NONE
    }

    public record FindMatch(ChestKey chestKey, MatchType matchType, double distance) {
    }

    private record ObservedContainerContents(Set<Item> items, long observedAtEpochMs) {
    }
}
