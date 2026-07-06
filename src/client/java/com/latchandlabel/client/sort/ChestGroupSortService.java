package com.latchandlabel.client.sort;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.find.ContainerObserver;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.tagging.ShulkerItemCategoryBridge;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EXPERIMENTAL. Sorts a group of chests that share a category as one storage entity: reads every
 * chest, partitions the item types across chests by item id (so each chest holds a contiguous id
 * range, consolidating partial stacks), then routes items to their target chest over repeated
 * passes. Only category items are ever touched; every move is a container quick-move, so items
 * can't be destroyed — worst case is an incomplete sort. Client can only open one chest at a time,
 * so this is a sequential open/move/close bot: throttled and menu-sync-gated to avoid server kicks.
 */
public final class ChestGroupSortService {
    // Routing converges in a couple passes; this is only an anti-livelock safety cap.
    private static final int MAX_PASSES = 16;
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int OP_COOLDOWN_TICKS = 3;
    private static final double GROUP_RADIUS = 12.0;

    private enum Phase { READ, WRITE, SORT, DONE }

    private static boolean active = false;
    private static String categoryId;
    private static List<ChestKey> chests = List.of();
    private static final Map<ChestKey, Set<Item>> targetByChest = new HashMap<>();
    private static final Map<ChestKey, Set<Item>> heldByChest = new HashMap<>();
    private static final Map<Item, Integer> pool = new LinkedHashMap<>();
    private static final Map<ChestKey, Integer> capacityByChest = new HashMap<>();

    private static Phase phase = Phase.DONE;
    private static int cursor = 0;
    private static int passCount = 0;
    private static boolean movedThisPass = false;

    private static ChestKey openChest = null;
    private static long openRequestedMs = 0L;
    private static int openTimeoutTicks = 0;
    private static int cooldown = 0;
    private static int movedTotal = 0;

    private ChestGroupSortService() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Brush + right-click a tagged chest starts sorting that category's nearby group. */
    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            // Never cancel the bot's own useItemOn opens (they re-fire this callback) — let them through.
            if (active) {
                return InteractionResult.PASS;
            }
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND || player == null || hitResult == null) {
                return InteractionResult.PASS;
            }
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return InteractionResult.PASS;
            }
            if (!player.getItemInHand(hand).is(Items.BRUSH) || !isAltDown(client.getWindow())) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!TrackableStorage.isTrackableStorage(world.getBlockEntity(pos))) {
                return InteractionResult.PASS;
            }
            Optional<ChestKey> key = StorageKeyResolver.resolveForWorld(world, pos);
            if (key.isEmpty()) {
                return InteractionResult.PASS;
            }
            Optional<String> category = LatchLabelClientState.tagStore().getTag(key.get());
            if (category.isEmpty()) {
                return InteractionResult.PASS;
            }
            start(client, key.get(), category.get());
            return InteractionResult.FAIL;
        });
    }

    /** Begins sorting the category group that {@code clickedChest} belongs to. */
    public static void start(Minecraft client, ChestKey clickedChest, String categoryId) {
        if (active || client == null || client.player == null || client.level == null) {
            return;
        }

        List<ChestKey> group = new ArrayList<>();
        Vec3 origin = client.player.position();
        for (Map.Entry<ChestKey, String> entry : LatchLabelClientState.tagStore().snapshotTags().entrySet()) {
            if (!categoryId.equals(entry.getValue())) {
                continue;
            }
            ChestKey key = entry.getKey();
            if (!key.dimensionId().equals(clickedChest.dimensionId())) {
                continue;
            }
            if (key.pos().distToCenterSqr(origin) <= GROUP_RADIUS * GROUP_RADIUS) {
                group.add(key);
            }
        }
        if (group.isEmpty()) {
            return;
        }
        group.sort(Comparator
                .comparingInt((ChestKey k) -> k.pos().getX())
                .thenComparingInt(k -> k.pos().getZ())
                .thenComparingInt(k -> k.pos().getY()));

        ChestGroupSortService.categoryId = categoryId;
        chests = group;
        targetByChest.clear();
        heldByChest.clear();
        pool.clear();
        capacityByChest.clear();
        phase = Phase.READ;
        cursor = 0;
        passCount = 0;
        movedThisPass = false;
        openChest = null;
        cooldown = 0;
        movedTotal = 0;
        active = true;
        message(client, Component.translatable("latchlabel.sort.started", group.size()));
    }

    public static void onClientTick(Minecraft client) {
        if (!active) {
            return;
        }
        if (client == null || client.player == null || client.level == null) {
            abort(client);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        switch (phase) {
            case READ -> tickRead(client);
            case WRITE -> tickWrite(client);
            case SORT -> tickSort(client);
            case DONE -> finish(client);
        }
    }

    private static void tickRead(Minecraft client) {
        if (cursor >= chests.size()) {
            computePlan();
            phase = Phase.WRITE;
            cursor = 0;
            passCount = 0;
            movedThisPass = false;
            return;
        }

        ChestKey chest = chests.get(cursor);
        Ready ready = ensureOpen(client, chest);
        if (ready == Ready.WAITING) {
            return;
        }
        if (ready == Ready.READY) {
            readChest(client, chest);
        }
        // READY or FAILED (unreachable): move on
        client.player.closeContainer();
        openChest = null;
        cursor++;
        cooldown = OP_COOLDOWN_TICKS;
    }

    private static void tickWrite(Minecraft client) {
        if (cursor >= chests.size()) {
            if (!movedThisPass || passCount >= MAX_PASSES) {
                phase = Phase.SORT;
                cursor = 0;
            } else {
                passCount++;
                cursor = 0;
                movedThisPass = false;
            }
            return;
        }

        // Skip chests that already hold only their target items and have nothing inbound — no open needed.
        while (cursor < chests.size() && !needsVisit(client, chests.get(cursor))) {
            cursor++;
        }
        if (cursor >= chests.size()) {
            return;
        }

        ChestKey chest = chests.get(cursor);
        Ready ready = ensureOpen(client, chest);
        if (ready == Ready.WAITING) {
            return;
        }
        if (ready == Ready.READY && writeChest(client, chest)) {
            movedThisPass = true;
        }
        client.player.closeContainer();
        openChest = null;
        cursor++;
        cooldown = OP_COOLDOWN_TICKS;
    }

    /** True if this chest has surplus to pull out, or an item it wants is waiting in the inventory. */
    private static boolean needsVisit(Minecraft client, ChestKey chest) {
        Set<Item> target = targetByChest.getOrDefault(chest, Set.of());
        for (Item held : heldByChest.getOrDefault(chest, Set.of())) {
            if (!target.contains(held)) {
                return true; // surplus to remove
            }
        }
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && target.contains(stack.getItem()) && isTargetCategory(stack)) {
                return true; // inbound item waiting
            }
        }
        return false;
    }

    private enum Ready { WAITING, READY, FAILED }

    private static Ready ensureOpen(Minecraft client, ChestKey chest) {
        if (!chest.equals(openChest)) {
            openChest = chest;
            openRequestedMs = System.currentTimeMillis();
            openTimeoutTicks = OPEN_TIMEOUT_TICKS;
            BlockPos pos = chest.pos();
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
            return Ready.WAITING;
        }
        boolean synced = ContainerObserver.lastContentsSyncMs() > openRequestedMs
                && McCompat.getScreen(client) instanceof AbstractContainerScreen<?>;
        if (synced) {
            return Ready.READY;
        }
        openTimeoutTicks--;
        return openTimeoutTicks <= 0 ? Ready.FAILED : Ready.WAITING;
    }

    private static void readChest(Minecraft client, ChestKey chest) {
        AbstractContainerMenu menu = client.player.containerMenu;
        int capacity = 0;
        Set<Item> held = new HashSet<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            // A slot holding a non-category item isn't usable capacity — leave it in place.
            if (!stack.isEmpty() && !isTargetCategory(stack)) {
                continue;
            }
            capacity++;
            if (!stack.isEmpty()) {
                pool.merge(stack.getItem(), stack.getCount(), Integer::sum);
                held.add(stack.getItem());
            }
        }
        capacityByChest.put(chest, capacity);
        heldByChest.put(chest, held);
    }

    private static void refreshHeld(Minecraft client, ChestKey chest) {
        AbstractContainerMenu menu = client.player.containerMenu;
        Set<Item> held = new HashSet<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && isTargetCategory(stack)) {
                held.add(stack.getItem());
            }
        }
        heldByChest.put(chest, held);
    }

    /** Partition item types across chests by id, filling each chest's slot capacity in order. */
    private static void computePlan() {
        List<Item> items = new ArrayList<>(pool.keySet());
        items.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item)));

        int chestIndex = 0;
        int usedSlots = 0;
        for (Item item : items) {
            if (chestIndex >= chests.size()) {
                break; // out of capacity — leftovers stay wherever they are (safe)
            }
            int maxStack = item.getDefaultMaxStackSize();
            int slotsNeeded = Math.max(1, (int) Math.ceil(pool.get(item) / (double) maxStack));
            ChestKey chest = chests.get(chestIndex);
            int capacity = capacityByChest.getOrDefault(chest, 0);
            if (usedSlots > 0 && usedSlots + slotsNeeded > capacity) {
                chestIndex++;
                usedSlots = 0;
                if (chestIndex >= chests.size()) {
                    break;
                }
                chest = chests.get(chestIndex);
            }
            targetByChest.computeIfAbsent(chest, k -> new HashSet<>()).add(item);
            usedSlots += slotsNeeded;
        }
    }

    private static boolean writeChest(Minecraft client, ChestKey chest) {
        Set<Item> chestSet = targetByChest.getOrDefault(chest, Set.of());
        AbstractContainerMenu menu = client.player.containerMenu;
        boolean moved = false;

        // Deposit: player-inventory category items that belong in this chest.
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isTargetCategory(stack) || !chestSet.contains(stack.getItem())) {
                continue;
            }
            if (quickMove(client, menu, slot.index)) {
                moved = true;
            }
        }

        // Pickup: chest category items that belong elsewhere (surplus), if the inventory has room.
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isTargetCategory(stack) || chestSet.contains(stack.getItem())) {
                continue;
            }
            if (!hasInventorySpace(client)) {
                break;
            }
            if (quickMove(client, menu, slot.index)) {
                moved = true;
            }
        }

        refreshHeld(client, chest);
        return moved;
    }

    private static boolean quickMove(Minecraft client, AbstractContainerMenu menu, int slotId) {
        if (slotId < 0 || slotId >= menu.slots.size() || !menu.slots.get(slotId).hasItem()) {
            return false;
        }
        ItemStack before = menu.slots.get(slotId).getItem().copy();
        client.gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.QUICK_MOVE, client.player);
        boolean changed = !ItemStack.matches(before, menu.slots.get(slotId).getItem());
        if (changed) {
            movedTotal++;
        }
        return changed;
    }

    private static boolean hasInventorySpace(Minecraft client) {
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTargetCategory(ItemStack stack) {
        return ShulkerItemCategoryBridge.resolveCategoryIdForStack(stack)
                .or(() -> LatchLabelClientState.itemCategoryMappingService().categoryIdFor(stack))
                .filter(categoryId::equals)
                .isPresent();
    }

    private static boolean inventoryHasCategory(Minecraft client) {
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isTargetCategory(stack)) {
                return true;
            }
        }
        return false;
    }

    private static void tickSort(Minecraft client) {
        if (cursor >= chests.size()) {
            phase = Phase.DONE;
            return;
        }
        ChestKey chest = chests.get(cursor);
        Ready ready = ensureOpen(client, chest);
        if (ready == Ready.WAITING) {
            return;
        }
        if (ready == Ready.READY) {
            sortChestSlots(client);
        }
        client.player.closeContainer();
        openChest = null;
        cursor++;
        cooldown = OP_COOLDOWN_TICKS;
    }

    /** Selection-sorts the chest's own slots by item id (empties last), via pickup-swaps. */
    // ponytail: all swaps for one chest run in a single tick — a burst of container clicks.
    // If a strict server flags the burst, spread the swaps across ticks.
    private static void sortChestSlots(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        List<Integer> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                slots.add(slot.index);
            }
        }
        int n = slots.size();
        for (int a = 0; a < n; a++) {
            int best = a;
            for (int b = a + 1; b < n; b++) {
                if (slotRank(menu, slots.get(b)).compareTo(slotRank(menu, slots.get(best))) < 0) {
                    best = b;
                }
            }
            if (best != a) {
                int i = slots.get(a);
                int j = slots.get(best);
                pickup(client, menu, i);
                pickup(client, menu, j);
                pickup(client, menu, i);
            }
        }
    }

    /** Sort key for a slot: item id, with empty slots pushed to the end. */
    private static String slotRank(AbstractContainerMenu menu, int slotId) {
        ItemStack stack = menu.slots.get(slotId).getItem();
        if (stack.isEmpty()) {
            return "￿"; // sorts after any real item id
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void pickup(Minecraft client, AbstractContainerMenu menu, int slotId) {
        client.gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.PICKUP, client.player);
    }

    private static void finish(Minecraft client) {
        if (client.player != null) {
            client.player.closeContainer();
            Component msg = inventoryHasCategory(client)
                    ? Component.translatable("latchlabel.sort.full", chests.size())
                    : Component.translatable("latchlabel.sort.finished", chests.size(), movedTotal);
            message(client, msg);
        }
        reset();
    }

    private static void abort(Minecraft client) {
        if (client != null && client.player != null) {
            client.player.closeContainer();
        }
        reset();
    }

    private static void reset() {
        active = false;
        phase = Phase.DONE;
        openChest = null;
        chests = List.of();
        targetByChest.clear();
        heldByChest.clear();
        pool.clear();
        capacityByChest.clear();
    }

    private static void message(Minecraft client, Component text) {
        if (client.player != null) {
            client.player.sendOverlayMessage(text);
        }
    }

    private static boolean isAltDown(Window window) {
        return window != null && (
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)
        );
    }
}
