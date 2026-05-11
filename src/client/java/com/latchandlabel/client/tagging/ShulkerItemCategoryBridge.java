package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains a client-side bridge between placed shulker box tags and shulker item stacks.
 * When a tagged shulker is broken, the bridge fingerprints its contents and remembers
 * the category so that when the item is picked up, the tag can be preserved.
 */
public final class ShulkerItemCategoryBridge {
    private static final long PENDING_BREAK_TTL_MS = 3_000L;
    private static final long PENDING_PLACEMENT_TTL_MS = 1_500L;
    private static final int MAX_FINGERPRINT_MAPPINGS = 4_096;
    private static final int MAX_PENDING = 256;
    private static final long CAP_WARN_INTERVAL_MS = 60_000L;
    private static long lastBreaksCapWarnMs = 0;
    private static long lastPlacementsCapWarnMs = 0;

    private static final Map<String, String> CATEGORY_BY_FINGERPRINT =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_FINGERPRINT_MAPPINGS;
                }
            });
    private static final ArrayDeque<PendingBreak> PENDING_BREAKS = new ArrayDeque<>();
    private static final ArrayDeque<PendingPlacement> PENDING_PLACEMENTS = new ArrayDeque<>();

    private ShulkerItemCategoryBridge() {
    }

    public static void register() {
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (world == null || player == null || state == null) {
                return;
            }
            onShulkerBroken(world, pos, state);
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || player == null || hitResult == null) {
                return InteractionResult.PASS;
            }

            ItemStack handStack = player.getItemInHand(hand);
            if (!isShulkerItem(handStack)) {
                return InteractionResult.PASS;
            }

            resolveCategoryIdForStack(handStack).ifPresent(categoryId -> {
                BlockPos placementPos = resolvePlacementPos(world, hitResult.getBlockPos(), hitResult.getDirection());
                boolean wasShulkerAtTarget = world.getBlockState(placementPos).getBlock() instanceof ShulkerBoxBlock;
                long nowMs = System.currentTimeMillis();
                if (PENDING_PLACEMENTS.size() >= MAX_PENDING) {
                    if (nowMs - lastPlacementsCapWarnMs > CAP_WARN_INTERVAL_MS) {
                        LatchLabel.LOGGER.warn("[ShulkerBridge] PENDING_PLACEMENTS overflow, dropping oldest entry");
                        lastPlacementsCapWarnMs = nowMs;
                    }
                    PENDING_PLACEMENTS.pollFirst();
                }
                PENDING_PLACEMENTS.addLast(new PendingPlacement(
                        world.dimension().identifier(),
                        placementPos.immutable(),
                        categoryId,
                        wasShulkerAtTarget,
                        nowMs
                ));
            });

            return InteractionResult.PASS;
        });
    }

    public static void onClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            PENDING_BREAKS.clear();
            PENDING_PLACEMENTS.clear();
            return;
        }

        long now = System.currentTimeMillis();
        processPendingBreaks(client, now);
        processPendingPlacements(client, now);
    }

    public static Optional<String> resolveCategoryIdForStack(ItemStack stack) {
        if (!isShulkerItem(stack)) {
            return Optional.empty();
        }

        String fingerprint = fingerprint(stack);
        if (fingerprint == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CATEGORY_BY_FINGERPRINT.get(fingerprint));
    }

    private static void onShulkerBroken(Level world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
            return;
        }

        ChestKey key = new ChestKey(world.dimension().identifier(), pos.immutable());
        Optional<String> categoryId = LatchLabelClientState.tagStore().getTag(key);
        if (categoryId.isEmpty()) {
            return;
        }
        LatchLabelClientState.tagStore().clearTag(key);

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (PENDING_BREAKS.size() >= MAX_PENDING) {
            if (nowMs - lastBreaksCapWarnMs > CAP_WARN_INTERVAL_MS) {
                LatchLabel.LOGGER.warn("[ShulkerBridge] PENDING_BREAKS overflow, dropping oldest entry");
                lastBreaksCapWarnMs = nowMs;
            }
            PENDING_BREAKS.pollFirst();
        }
        PENDING_BREAKS.addLast(new PendingBreak(
                categoryId.get(),
                snapshotShulkerInventoryCounts(client),
                nowMs
        ));
    }

    private static void processPendingBreaks(Minecraft client, long now) {
        if (PENDING_BREAKS.isEmpty()) {
            return;
        }

        Map<String, Integer> currentCounts = snapshotShulkerInventoryCounts(client);
        while (!PENDING_BREAKS.isEmpty()) {
            PendingBreak pending = PENDING_BREAKS.peekFirst();
            if ((now - pending.createdAtEpochMs()) > PENDING_BREAK_TTL_MS) {
                PENDING_BREAKS.removeFirst();
                continue;
            }

            String gainedFingerprint = findGainedFingerprint(currentCounts, pending.inventoryCountsBeforeBreak());
            if (gainedFingerprint == null) {
                break;
            }

            CATEGORY_BY_FINGERPRINT.put(gainedFingerprint, pending.categoryId());
            PENDING_BREAKS.removeFirst();
        }
    }

    private static void processPendingPlacements(Minecraft client, long now) {
        if (PENDING_PLACEMENTS.isEmpty() || client.level == null) {
            return;
        }

        while (!PENDING_PLACEMENTS.isEmpty()) {
            PendingPlacement pending = PENDING_PLACEMENTS.peekFirst();
            if ((now - pending.createdAtEpochMs()) > PENDING_PLACEMENT_TTL_MS) {
                PENDING_PLACEMENTS.removeFirst();
                continue;
            }

            if (!Objects.equals(pending.dimensionId(), client.level.dimension().identifier())) {
                PENDING_PLACEMENTS.removeFirst();
                continue;
            }

            boolean isShulkerNow = client.level.getBlockState(pending.pos()).getBlock() instanceof ShulkerBoxBlock;
            if (!isShulkerNow) {
                break;
            }
            if (pending.wasShulkerAtTarget()) {
                PENDING_PLACEMENTS.removeFirst();
                continue;
            }

            StorageKeyResolver.resolveForWorld(client.level, pending.pos())
                    .ifPresent(key -> LatchLabelClientState.tagStore().setTag(key, pending.categoryId()));
            PENDING_PLACEMENTS.removeFirst();
        }
    }

    private static BlockPos resolvePlacementPos(Level world, BlockPos hitPos, net.minecraft.core.Direction side) {
        if (world == null || hitPos == null || side == null) {
            return hitPos;
        }

        if (world.getBlockState(hitPos).canBeReplaced()) {
            return hitPos;
        }
        return hitPos.relative(side);
    }

    private static Map<String, Integer> snapshotShulkerInventoryCounts(Minecraft client) {
        Map<String, Integer> counts = new HashMap<>();
        if (client == null || client.player == null) {
            return counts;
        }

        var inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!isShulkerItem(stack) || stack.isEmpty()) {
                continue;
            }

            String fingerprint = fingerprint(stack);
            if (fingerprint == null) {
                continue;
            }
            counts.merge(fingerprint, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static String findGainedFingerprint(Map<String, Integer> currentCounts, Map<String, Integer> baseline) {
        String best = null;
        int bestGain = 0;
        for (Map.Entry<String, Integer> entry : currentCounts.entrySet()) {
            int before = baseline.getOrDefault(entry.getKey(), 0);
            int gain = entry.getValue() - before;
            if (gain > bestGain) {
                bestGain = gain;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static boolean isShulkerItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        return blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static String fingerprint(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return null;
        }

        StringBuilder fingerprint = new StringBuilder(itemId.toString());
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            String joined = container.nonEmptyItemCopyStream()
                    .map(ShulkerItemCategoryBridge::fingerprintContainedStack)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            fingerprint.append("|c=").append(joined);
        }
        if (stack.get(DataComponents.CUSTOM_NAME) != null) {
            fingerprint.append("|n=").append(stack.get(DataComponents.CUSTOM_NAME).getString());
        }
        return fingerprint.toString();
    }

    private static String fingerprintContainedStack(ItemStack stack) {
        Identifier itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) {
            return "unknown:0";
        }
        return itemId + "x" + stack.getCount();
    }

    private record PendingBreak(String categoryId, Map<String, Integer> inventoryCountsBeforeBreak, long createdAtEpochMs) {
    }

    private record PendingPlacement(
            Identifier dimensionId,
            BlockPos pos,
            String categoryId,
            boolean wasShulkerAtTarget,
            long createdAtEpochMs
    ) {
    }
}
