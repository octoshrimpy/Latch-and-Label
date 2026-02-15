package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains a client-side bridge between placed shulker tags and shulker item stacks.
 */
public final class ShulkerItemCategoryBridge {
    private static final long PENDING_BREAK_TTL_MS = 3_000L;
    private static final long PENDING_PLACEMENT_TTL_MS = 1_500L;
    private static final int MAX_FINGERPRINT_MAPPINGS = 4_096;

    private static final Map<String, String> CATEGORY_BY_FINGERPRINT = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_FINGERPRINT_MAPPINGS;
        }
    };
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
            if (!world.isClient() || player == null || hitResult == null) {
                return ActionResult.PASS;
            }

            ItemStack handStack = player.getStackInHand(hand);
            if (!isShulkerItem(handStack)) {
                return ActionResult.PASS;
            }

            resolveCategoryIdForStack(handStack).ifPresent(categoryId -> {
                BlockPos placementPos = resolvePlacementPos(world, hitResult.getBlockPos(), hitResult.getSide());
                boolean wasShulkerAtTarget = world.getBlockState(placementPos).getBlock() instanceof ShulkerBoxBlock;
                PENDING_PLACEMENTS.addLast(new PendingPlacement(
                        world.getRegistryKey().getValue(),
                        placementPos.toImmutable(),
                        categoryId,
                        wasShulkerAtTarget,
                        System.currentTimeMillis()
                ));
            });

            return ActionResult.PASS;
        });
    }

    public static void onClientTick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
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

    private static void onShulkerBroken(World world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ShulkerBoxBlock)) {
            return;
        }

        ChestKey key = new ChestKey(world.getRegistryKey().getValue(), pos.toImmutable());
        Optional<String> categoryId = LatchLabelClientState.tagStore().getTag(key);
        if (categoryId.isEmpty()) {
            return;
        }
        LatchLabelClientState.tagStore().clearTag(key);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        PENDING_BREAKS.addLast(new PendingBreak(
                categoryId.get(),
                snapshotShulkerInventoryCounts(client),
                System.currentTimeMillis()
        ));
    }

    private static void processPendingBreaks(MinecraftClient client, long now) {
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

    private static void processPendingPlacements(MinecraftClient client, long now) {
        if (PENDING_PLACEMENTS.isEmpty() || client.world == null) {
            return;
        }

        while (!PENDING_PLACEMENTS.isEmpty()) {
            PendingPlacement pending = PENDING_PLACEMENTS.peekFirst();
            if ((now - pending.createdAtEpochMs()) > PENDING_PLACEMENT_TTL_MS) {
                PENDING_PLACEMENTS.removeFirst();
                continue;
            }

            if (!Objects.equals(pending.dimensionId(), client.world.getRegistryKey().getValue())) {
                PENDING_PLACEMENTS.removeFirst();
                continue;
            }

            boolean isShulkerNow = client.world.getBlockState(pending.pos()).getBlock() instanceof ShulkerBoxBlock;
            if (!isShulkerNow) {
                break;
            }
            if (pending.wasShulkerAtTarget()) {
                PENDING_PLACEMENTS.removeFirst();
                continue;
            }

            StorageKeyResolver.resolveForWorld(client.world, pending.pos())
                    .ifPresent(key -> LatchLabelClientState.tagStore().setTag(key, pending.categoryId()));
            PENDING_PLACEMENTS.removeFirst();
        }
    }

    private static BlockPos resolvePlacementPos(World world, BlockPos hitPos, net.minecraft.util.math.Direction side) {
        if (world == null || hitPos == null || side == null) {
            return hitPos;
        }

        if (world.getBlockState(hitPos).isReplaceable()) {
            return hitPos;
        }
        return hitPos.offset(side);
    }

    private static Map<String, Integer> snapshotShulkerInventoryCounts(MinecraftClient client) {
        Map<String, Integer> counts = new HashMap<>();
        if (client == null || client.player == null) {
            return counts;
        }

        var inventory = client.player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
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

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) {
            return null;
        }

        StringBuilder fingerprint = new StringBuilder(itemId.toString());
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            String joined = container.streamNonEmpty()
                    .map(ShulkerItemCategoryBridge::fingerprintContainedStack)
                    .sorted()
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            fingerprint.append("|c=").append(joined);
        }
        if (stack.get(DataComponentTypes.CUSTOM_NAME) != null) {
            fingerprint.append("|n=").append(stack.get(DataComponentTypes.CUSTOM_NAME).getString());
        }
        return fingerprint.toString();
    }

    private static String fingerprintContainedStack(ItemStack stack) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
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
