package com.latchandlabel.client.dump;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DumpService {

    private record DumpTarget(ChestKey key, String categoryId) {}

    private static final Deque<DumpTarget> dumpQueue = new ArrayDeque<>();
    private static boolean active = false;
    private static boolean autoCloseNext = false;
    private static boolean awaitingScreen = false;
    private static int cooldownTicks = 0;
    private static int containersVisited = 0;
    private static DumpTarget currentTarget = null;

    private DumpService() {
    }

    public static void start(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }

        // Collect categories present in player inventory
        Set<String> inventoryCategories = collectInventoryCategories(client.player);
        if (inventoryCategories.isEmpty()) {
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable("latchlabel.dump.no_matching_items"));
            }
            return;
        }

        // Collect tagged containers whose category matches inventory categories
        Map<ChestKey, String> allTags = LatchLabelClientState.tagStore().snapshotTags();
        Player player = client.player;

        dumpQueue.clear();
        active = false;
        autoCloseNext = false;
        awaitingScreen = false;
        cooldownTicks = 0;
        containersVisited = 0;
        currentTarget = null;

        // Filter and sort by distance
        allTags.entrySet().stream()
                .filter(entry -> entry.getKey().dimensionId().equals(client.level.dimension().identifier()))
                .filter(entry -> inventoryCategories.contains(entry.getValue()))
                .filter(entry -> DumpSettings.queueMode() || isWithinRange(player, entry.getKey()))
                .sorted(Comparator.comparingDouble(entry -> distanceSq(player, entry.getKey())))
                .forEach(entry -> dumpQueue.add(new DumpTarget(entry.getKey(), entry.getValue())));

        if (dumpQueue.isEmpty()) {
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable("latchlabel.dump.no_containers_in_range"));
            }
            return;
        }

        active = true;
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.translatable("latchlabel.dump.starting", dumpQueue.size()));
        }
    }

    public static void onClientTick(Minecraft client) {
        if (!active) {
            return;
        }
        if (client.player == null || client.level == null) {
            stop();
            return;
        }

        if (autoCloseNext) {
            if (client.gui.screen() instanceof AbstractContainerScreen<?>) {
                client.player.closeContainer();
            }
            autoCloseNext = false;
            awaitingScreen = false;
            currentTarget = null;
            cooldownTicks = 2;
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        // If a screen just opened after we interacted
        if (awaitingScreen && client.gui.screen() instanceof AbstractContainerScreen<?> handledScreen) {
            int moved = ContainerTagButtonManager.moveMatchingFromPlayerToStorage(
                    client, handledScreen.getMenu(), currentTarget.categoryId());
            if (moved > 0) {
                containersVisited++;
            }
            autoCloseNext = true;
            awaitingScreen = false;
            return;
        }

        // Don't process while a screen is open
        if (client.gui.screen() != null) {
            return;
        }

        if (dumpQueue.isEmpty()) {
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable("latchlabel.dump.done", containersVisited));
            }
            stop();
            return;
        }

        Player player = client.player;

        if (DumpSettings.queueMode()) {
            // In queue mode: look for the first container in reach; skip out-of-reach ones back to end
            int queueSize = dumpQueue.size();
            DumpTarget next = null;
            int checked = 0;
            while (!dumpQueue.isEmpty() && checked < queueSize) {
                DumpTarget candidate = dumpQueue.poll();
                checked++;
                if (!candidate.key().dimensionId().equals(client.level.dimension().identifier())) {
                    continue; // discard dimension mismatches
                }
                if (isWithinRange(player, candidate.key())) {
                    next = candidate;
                    break;
                }
                dumpQueue.add(candidate); // put back at end
            }
            if (next == null) {
                // Nothing in reach yet; show waiting message every so often
                return;
            }
            openContainer(client, next);
        } else {
            // Reach-only mode: poll and discard anything not in reach
            DumpTarget next = null;
            while (!dumpQueue.isEmpty()) {
                DumpTarget candidate = dumpQueue.poll();
                if (!candidate.key().dimensionId().equals(client.level.dimension().identifier())) {
                    continue;
                }
                if (isWithinRange(player, candidate.key())) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                if (client.player != null) {
                    client.player.sendOverlayMessage(Component.translatable("latchlabel.dump.done", containersVisited));
                }
                stop();
                return;
            }
            openContainer(client, next);
        }
    }

    private static void openContainer(Minecraft client, DumpTarget target) {
        currentTarget = target;
        BlockPos pos = target.key().pos();
        BlockHitResult hitResult = new BlockHitResult(
                Vec3.atCenterOf(pos),
                Direction.UP,
                pos,
                false
        );
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        awaitingScreen = true;
        cooldownTicks = 2;
    }

    private static void stop() {
        active = false;
        dumpQueue.clear();
        autoCloseNext = false;
        awaitingScreen = false;
        cooldownTicks = 0;
        containersVisited = 0;
        currentTarget = null;
    }

    private static Set<String> collectInventoryCategories(Player player) {
        Set<String> categories = new HashSet<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            LatchLabelClientState.itemCategoryMappingService()
                    .categoryIdFor(stack)
                    .ifPresent(categories::add);
        }
        return categories;
    }

    private static boolean isWithinRange(Player player, ChestKey key) {
        BlockPos pos = key.pos();
        double range = DumpSettings.dumpRange();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= range * range;
    }

    private static double distanceSq(Player player, ChestKey key) {
        BlockPos pos = key.pos();
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
