package com.latchandlabel.client.find;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Answers {@code /find} queries from what the client actually knows: the persistent
 * {@link com.latchandlabel.client.store.ObservedIndexStore observed index} (contents of chests
 * the player has opened) and category tags. Two honest tiers:
 * <ul>
 *   <li>{@link MatchType#KNOWN} — the item was seen inside this chest (or, for tag search, the
 *       chest carries that tag).</li>
 *   <li>{@link MatchType#LIKELY} — the chest is tagged with the item's category but its contents
 *       are unknown, so it's a guess.</li>
 * </ul>
 * No block-entity scanning: the client cannot read the inventory of a chest it hasn't opened.
 */
public final class FindScanService {

    public List<FindMatch> scan(Minecraft client, Item targetItem, Set<Item> matchSet, int radius) {
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }

        Level world = client.level;
        Player player = client.player;
        Identifier dimensionId = McCompat.dimensionId(world);
        double maxDistanceSq = (double) radius * radius;
        String targetCategoryId = LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(BuiltInRegistries.ITEM.getKey(targetItem))
                .orElse(null);

        // Single-player: the integrated server holds real contents, so read every loaded container
        // directly — no need to have opened it. Multiplayer can't see chunk contents, so it falls
        // back to what we've personally observed (fresh/stale) plus tagged guesses.
        if (client.hasSingleplayerServer()) {
            List<FindMatch> omniscient = scanSingleplayer(
                    client, world, player, dimensionId, targetItem, matchSet, targetCategoryId, radius, maxDistanceSq);
            if (omniscient != null) {
                return omniscient;
            }
        }
        return scanObserved(world, player, dimensionId, targetItem, matchSet, targetCategoryId, maxDistanceSq);
    }

    /** Multiplayer / fallback tiers: observed contents (KNOWN or KNOWN_STALE) + tagged guesses (LIKELY). */
    private List<FindMatch> scanObserved(Level world, Player player, Identifier dimensionId,
            Item targetItem, Set<Item> matchSet, String targetCategoryId, double maxDistanceSq) {
        List<FindMatch> matches = new ArrayList<>();
        for (ChestKey key : candidateKeys()) {
            if (!inScope(key, dimensionId, player, maxDistanceSq)) {
                continue;
            }

            MatchType matchType = MatchType.NONE;
            Optional<Set<Item>> observed = LatchLabelClientState.observedIndexStore().itemsFor(key);
            if (observed.isPresent() && containsAny(observed.get(), targetItem, matchSet)) {
                matchType = LatchLabelClientState.observedIndexStore().isStale(key)
                        ? MatchType.KNOWN_STALE : MatchType.KNOWN;
            } else if (targetCategoryId != null && isTaggedWith(key, targetCategoryId)) {
                matchType = MatchType.LIKELY;
            }
            if (matchType == MatchType.NONE) {
                continue;
            }
            matches.add(new FindMatch(key, matchType, distance(player, key)));
        }

        matches.sort(Comparator.comparing(FindMatch::matchType).thenComparingDouble(FindMatch::distance));
        return List.copyOf(matches);
    }

    /**
     * Single-player scan: reads authoritative contents of every loaded container in range from the
     * integrated server. Matches are always {@link MatchType#KNOWN} (server truth). Tagged chests in
     * unloaded chunks — which we can't read — are added as {@link MatchType#LIKELY}. Returns
     * {@code null} if the server level is unavailable so the caller can fall back to observed tiers.
     */
    private List<FindMatch> scanSingleplayer(Minecraft client, Level world, Player player, Identifier dimensionId,
            Item targetItem, Set<Item> matchSet, String targetCategoryId, int radius, double maxDistanceSq) {
        MinecraftServer server = client.getSingleplayerServer();
        ServerLevel serverLevel = server == null ? null : server.getLevel(world.dimension());
        if (serverLevel == null) {
            return null;
        }

        Map<ChestKey, Set<Item>> contentsByKey = new LinkedHashMap<>();
        Map<ChestKey, Double> distByKey = new HashMap<>();

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
                    double distanceSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    if (distanceSq > maxDistanceSq) {
                        continue;
                    }
                    ChestKey key = StorageKeyResolver.resolveForWorld(world, pos).orElse(null);
                    if (key == null) {
                        continue;
                    }
                    // Merge both halves of a double chest under the one canonical key.
                    Set<Item> items = contentsByKey.computeIfAbsent(key, k -> new LinkedHashSet<>());
                    readServerContents(serverLevel, pos, items);
                    distByKey.merge(key, Math.sqrt(distanceSq), Math::min);
                }
            }
        }

        List<FindMatch> matches = new ArrayList<>();
        for (Map.Entry<ChestKey, Set<Item>> entry : contentsByKey.entrySet()) {
            if (containsAny(entry.getValue(), targetItem, matchSet)) {
                matches.add(new FindMatch(entry.getKey(), MatchType.KNOWN, distByKey.getOrDefault(entry.getKey(), 0.0)));
            }
        }
        // Tagged chests we couldn't read (unloaded) stay honest guesses.
        if (targetCategoryId != null) {
            for (var entry : LatchLabelClientState.tagStore().snapshotTags().entrySet()) {
                if (!targetCategoryId.equals(entry.getValue())) {
                    continue;
                }
                ChestKey key = entry.getKey();
                if (contentsByKey.containsKey(key) || !inScope(key, dimensionId, player, maxDistanceSq)) {
                    continue;
                }
                matches.add(new FindMatch(key, MatchType.LIKELY, distance(player, key)));
            }
        }

        matches.sort(Comparator.comparing(FindMatch::matchType).thenComparingDouble(FindMatch::distance));
        return List.copyOf(matches);
    }

    /**
     * Reads an integrated-server container's item types into {@code out}.
     * ponytail: cross-thread read of the integrated server from the client thread; /find is
     * user-triggered and infrequent, so a rare concurrent-modification race is tolerable — on
     * throw we just drop this one container rather than failing the whole scan.
     */
    private static void readServerContents(ServerLevel serverLevel, BlockPos pos, Set<Item> out) {
        try {
            BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
            if (blockEntity instanceof Container container) {
                int size = container.getContainerSize();
                for (int i = 0; i < size; i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        out.add(stack.getItem());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // tolerate the rare race; the container just won't contribute this scan
        }
    }

    public List<FindMatch> scanByTag(Minecraft client, String categoryId, int radius) {
        if (client == null || client.level == null || client.player == null) {
            return List.of();
        }

        Level world = client.level;
        Player player = client.player;
        Identifier dimensionId = McCompat.dimensionId(world);
        double maxDistanceSq = (double) radius * radius;

        List<FindMatch> matches = new ArrayList<>();
        for (var entry : LatchLabelClientState.tagStore().snapshotTags().entrySet()) {
            if (!categoryId.equals(entry.getValue())) {
                continue;
            }
            ChestKey key = entry.getKey();
            if (!inScope(key, dimensionId, player, maxDistanceSq)) {
                continue;
            }
            matches.add(new FindMatch(key, MatchType.KNOWN, distance(player, key)));
        }

        matches.sort(Comparator.comparingDouble(FindMatch::distance));
        return List.copyOf(matches);
    }

    private static Set<ChestKey> candidateKeys() {
        Set<ChestKey> keys = new LinkedHashSet<>(LatchLabelClientState.observedIndexStore().keys());
        keys.addAll(LatchLabelClientState.tagStore().snapshotTags().keySet());
        return keys;
    }

    private static boolean inScope(ChestKey key, Identifier dimensionId, Player player, double maxDistanceSq) {
        return key.dimensionId().equals(dimensionId)
                && player.distanceToSqr(key.pos().getX() + 0.5, key.pos().getY() + 0.5, key.pos().getZ() + 0.5) <= maxDistanceSq;
    }

    private static boolean containsAny(Set<Item> items, Item targetItem, Set<Item> matchSet) {
        if (items.contains(targetItem)) {
            return true;
        }
        for (Item item : items) {
            if (matchSet.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTaggedWith(ChestKey key, String categoryId) {
        return LatchLabelClientState.tagStore().getTag(key).filter(categoryId::equals).isPresent();
    }

    private static double distance(Player player, ChestKey key) {
        BlockPos pos = key.pos();
        return Math.sqrt(player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    /** Ordinal order is the render/sort priority: KNOWN (fresh) before KNOWN_STALE before LIKELY. */
    public enum MatchType {
        KNOWN,
        KNOWN_STALE,
        LIKELY,
        NONE
    }

    public record FindMatch(ChestKey chestKey, MatchType matchType, double distance) {
    }
}
