package com.latchandlabel.client.dump;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DumpService {

    private static final Deque<ChestKey> dumpQueue = new ArrayDeque<>();
    private static boolean active = false;
    private static boolean autoCloseNext = false;
    private static boolean awaitingScreen = false;
    private static int cooldownTicks = 0;
    private static int containersVisited = 0;
    private static ChestKey currentTarget = null;

    private DumpService() {
    }

    public static void start(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }

        // Collect categories present in player inventory
        Set<String> inventoryCategories = collectInventoryCategories(client.player);
        if (inventoryCategories.isEmpty()) {
            if (client.inGameHud != null) {
                client.inGameHud.setOverlayMessage(
                        Text.translatable("latchlabel.dump.no_matching_items"),
                        false
                );
            }
            return;
        }

        // Collect tagged containers whose category matches inventory categories
        Map<ChestKey, String> allTags = LatchLabelClientState.tagStore().snapshotTags();
        PlayerEntity player = client.player;

        dumpQueue.clear();
        active = false;
        autoCloseNext = false;
        awaitingScreen = false;
        cooldownTicks = 0;
        containersVisited = 0;
        currentTarget = null;

        // Filter and sort by distance
        allTags.entrySet().stream()
                .filter(entry -> entry.getKey().dimensionId().equals(client.world.getRegistryKey().getValue()))
                .filter(entry -> inventoryCategories.contains(entry.getValue()))
                .filter(entry -> DumpSettings.queueMode() || isWithinRange(player, entry.getKey()))
                .sorted(Comparator.comparingDouble(entry -> distanceSq(player, entry.getKey())))
                .forEach(entry -> dumpQueue.add(entry.getKey()));

        if (dumpQueue.isEmpty()) {
            if (client.inGameHud != null) {
                client.inGameHud.setOverlayMessage(
                        Text.translatable("latchlabel.dump.no_containers_in_range"),
                        false
                );
            }
            return;
        }

        active = true;
        if (client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(
                    Text.translatable("latchlabel.dump.starting", dumpQueue.size()),
                    false
            );
        }
    }

    public static void onClientTick(MinecraftClient client) {
        if (!active) {
            return;
        }
        if (client.player == null || client.world == null) {
            stop();
            return;
        }

        if (autoCloseNext) {
            if (client.currentScreen instanceof HandledScreen<?>) {
                client.player.closeHandledScreen();
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
        if (awaitingScreen && client.currentScreen != null) {
            ContainerTagButtonManager.triggerMoveToStorageForCurrentScreen(client);
            containersVisited++;
            autoCloseNext = true;
            awaitingScreen = false;
            return;
        }

        // Don't process while a screen is open
        if (client.currentScreen != null) {
            return;
        }

        if (dumpQueue.isEmpty()) {
            if (client.inGameHud != null) {
                client.inGameHud.setOverlayMessage(
                        Text.translatable("latchlabel.dump.done", containersVisited),
                        false
                );
            }
            stop();
            return;
        }

        PlayerEntity player = client.player;

        if (DumpSettings.queueMode()) {
            // In queue mode: look for the first container in reach; skip out-of-reach ones back to end
            int queueSize = dumpQueue.size();
            ChestKey next = null;
            int checked = 0;
            while (!dumpQueue.isEmpty() && checked < queueSize) {
                ChestKey candidate = dumpQueue.poll();
                checked++;
                if (!candidate.dimensionId().equals(client.world.getRegistryKey().getValue())) {
                    continue; // discard dimension mismatches
                }
                if (isWithinRange(player, candidate)) {
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
            ChestKey next = null;
            while (!dumpQueue.isEmpty()) {
                ChestKey candidate = dumpQueue.poll();
                if (!candidate.dimensionId().equals(client.world.getRegistryKey().getValue())) {
                    continue;
                }
                if (isWithinRange(player, candidate)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                if (client.inGameHud != null) {
                    client.inGameHud.setOverlayMessage(
                            Text.translatable("latchlabel.dump.done", containersVisited),
                            false
                    );
                }
                stop();
                return;
            }
            openContainer(client, next);
        }
    }

    private static void openContainer(MinecraftClient client, ChestKey key) {
        currentTarget = key;
        BlockPos pos = key.pos();
        BlockHitResult hitResult = new BlockHitResult(
                Vec3d.ofCenter(pos),
                Direction.UP,
                pos,
                false
        );
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
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

    private static Set<String> collectInventoryCategories(PlayerEntity player) {
        Set<String> categories = new HashSet<>();
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            LatchLabelClientState.itemCategoryMappingService()
                    .categoryIdFor(stack)
                    .ifPresent(categories::add);
        }
        return categories;
    }

    private static boolean isWithinRange(PlayerEntity player, ChestKey key) {
        BlockPos pos = key.pos();
        double range = DumpSettings.dumpRange();
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= range * range;
    }

    private static double distanceSq(PlayerEntity player, ChestKey key) {
        BlockPos pos = key.pos();
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
