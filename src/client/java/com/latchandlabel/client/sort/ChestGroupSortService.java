package com.latchandlabel.client.sort;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.SortSettings;
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
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EXPERIMENTAL. Sorts a group of chests that share a category as one storage entity.
 *
 * <p>Compile/execute split: READ opens each chest once and snapshots its contents, then
 * {@link SortPlanner} (pure, unit-tested) turns the snapshot into an explicit visit script —
 * which chests to open, what to deposit and pull at each. The executor plays the script back,
 * verifying each chest still matches the snapshot when opened; any surprise (desync, another
 * player) triggers a bounded re-read + replan instead of ad-hoc recovery passes.
 *
 * <p>Only sortable (category/group) items are ever touched; every move is a container click, so
 * items can't be destroyed — worst case is an incomplete sort. The client can only open one chest
 * at a time, so execution is throttled and menu-sync-gated to avoid server kicks.
 */
public final class ChestGroupSortService {
    private static final int OPEN_TIMEOUT_TICKS = 40;
    private static final int OP_COOLDOWN_TICKS = 3;
    private static final double GROUP_RADIUS = 12.0;
    // Anti-runaway cap for flood-filling a wall of untagged shulkers.
    private static final int MAX_ORGANIZE_GROUP = 64;
    // Surprise budget: how many times a verify mismatch or failed open may trigger re-read+replan.
    private static final int MAX_REPLANS = 2;
    // How long to idle for thrown overflow to hop back into the inventory before depositing anyway.
    private static final int GROUND_WAIT_TICKS = 100;

    private enum Phase { READ, EXECUTE, ARRANGE, DONE }

    private static boolean active = false;
    private static String categoryId;
    // Organize mode: category-less sort of a physically-adjacent group of untagged shulkers.
    // Only redistributes items already in the group (never vacuums the player inventory).
    private static boolean organizeMode = false;
    private static boolean organizeFrozen = false;
    private static List<ChestKey> chests = List.of();

    // Hand-picked chests (shift + alt + right-click), sorted as their own group once alt is released.
    // Survives reset() — it's gesture state, not run state.
    private static final List<ChestKey> selection = new ArrayList<>();
    private static String selectionCategory = null;

    // Snapshot state, rebuilt on every READ.
    private static final Map<String, ItemStack> prototypes = new LinkedHashMap<>();
    private static final Map<String, Integer> variantTotals = new LinkedHashMap<>();
    private static final List<SortPlanner.ChestSnapshot> snapshots = new ArrayList<>();
    private static Map<String, SortPlanner.Variant> variants = Map.of();

    // Plan/execute state.
    private static SortPlanner.Plan plan;
    private static int visitIndex = 0;
    private static int replans = 0;
    // Chest indices already sorted on their final EXECUTE visit; the ARRANGE pass skips these and
    // only opens chests the move script never touched.
    private static final java.util.Set<Integer> arrangedByExecute = new HashSet<>();
    // One-shot guard for the single-player server-read fast path (re-armed each READ pass).
    private static boolean serverReadAttempted = false;
    // Everything the bot has pulled out of group chests (carried in the inventory or thrown on the
    // ground) and still owes back to some chest. Decremented on deposit; seeds inventoryExtra on a
    // replan so mid-flight items still reach their home chest.
    private static final Map<String, Integer> ferried = new HashMap<>();
    // True once something was thrown at the player's feet this run — deposits then wait for pickup.
    private static boolean groundPending = false;
    private static int groundWait = 0;

    private static Phase phase = Phase.DONE;
    private static int cursor = 0;
    private static ChestKey openChest = null;
    private static long openRequestedMs = 0L;
    private static int openTimeoutTicks = 0;
    private static int cooldown = 0;
    private static int failedOpens = 0;
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
            if (category.isPresent()) {
                // Shift picks chests out of the group instead of sorting: click as many as you like,
                // release alt, and only those are sorted (as their own group).
                if (isShiftDown(client.getWindow())) {
                    toggleSelection(client, key.get(), category.get());
                } else {
                    start(client, key.get(), category.get());
                }
                return InteractionResult.FAIL;
            }
            // Untagged shulker: organize it with every untagged shulker of the same color it touches (transitively).
            if (!(world.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock shulker)) {
                return InteractionResult.PASS;
            }
            List<ChestKey> group = collectUntaggedShulkerGroup(world, pos, shulker.getColor());
            if (group.isEmpty()) {
                return InteractionResult.PASS;
            }
            startOrganize(client, group);
            return InteractionResult.FAIL;
        });
    }

    /**
     * Flood-fills face-adjacent untagged shulker boxes of the same color as the clicked one,
     * starting at {@code startPos}. Uncolored (null) is its own color group.
     */
    private static List<ChestKey> collectUntaggedShulkerGroup(Level world, BlockPos startPos, DyeColor color) {
        List<ChestKey> group = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPos.immutable());
        visited.add(startPos.immutable());
        while (!queue.isEmpty() && group.size() < MAX_ORGANIZE_GROUP) {
            BlockPos pos = queue.poll();
            if (!(world.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock shulker)
                    || !java.util.Objects.equals(shulker.getColor(), color)) {
                continue;
            }
            Optional<ChestKey> key = StorageKeyResolver.resolveForWorld(world, pos);
            if (key.isEmpty() || LatchLabelClientState.tagStore().getTag(key.get()).isPresent()) {
                continue;
            }
            group.add(key.get());
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir).immutable();
                if (visited.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return group;
    }

    /**
     * Adds (or removes, on a second click) a chest from the hand-picked selection. All picks must
     * share the first one's category — a sort is defined by one category's items.
     */
    private static void toggleSelection(Minecraft client, ChestKey chest, String category) {
        if (selection.isEmpty()) {
            selectionCategory = category;
        } else if (!selectionCategory.equals(category)) {
            message(client, Component.translatable("latchlabel.sort.selection_mismatch"));
            return;
        }
        if (!selection.remove(chest)) {
            selection.add(chest);
        }
        if (selection.isEmpty()) {
            selectionCategory = null;
        }
        message(client, Component.translatable("latchlabel.sort.selected", selection.size()));
    }

    /** Alt released with chests picked → sort exactly those, as their own group. */
    private static void startSelectionIfReleased(Minecraft client) {
        if (isAltDown(client.getWindow())) {
            return;
        }
        List<ChestKey> group = new ArrayList<>(selection);
        String category = selectionCategory;
        selection.clear();
        selectionCategory = null;
        begin(client, category, group, false);
    }

    /**
     * The chests of {@code categoryId} near the player and in {@code clickedChest}'s dimension, in
     * fill order (index 0 fills first). Shared with the alt-punch deposit so both agree on which
     * chest a category's items belong in.
     */
    public static List<ChestKey> groupInFillOrder(Minecraft client, ChestKey clickedChest, String categoryId) {
        if (client == null || client.player == null || client.level == null) {
            return List.of();
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
        sortFillOrder(group);
        return group;
    }

    /** Fill highest Z first, then highest X, then highest Y. */
    private static void sortFillOrder(List<ChestKey> group) {
        group.sort(Comparator
                .comparingInt((ChestKey k) -> -k.pos().getZ())
                .thenComparingInt(k -> -k.pos().getX())
                .thenComparingInt(k -> -k.pos().getY()));
    }

    /** Begins sorting the category group that {@code clickedChest} belongs to. */
    public static void start(Minecraft client, ChestKey clickedChest, String categoryId) {
        if (active || client == null || client.player == null || client.level == null) {
            return;
        }
        List<ChestKey> group = groupInFillOrder(client, clickedChest, categoryId);
        if (group.isEmpty()) {
            return;
        }
        begin(client, categoryId, group, false);
    }

    /** Begins organizing an explicit group of untagged shulkers (no category, no player-inventory vacuum). */
    public static void startOrganize(Minecraft client, List<ChestKey> group) {
        if (active || client == null || client.player == null || client.level == null || group.isEmpty()) {
            return;
        }
        begin(client, null, new ArrayList<>(group), true);
    }

    private static void begin(Minecraft client, String category, List<ChestKey> group, boolean organize) {
        sortFillOrder(group);
        reset();
        categoryId = category;
        organizeMode = organize;
        chests = group;
        phase = Phase.READ;
        active = true;
        message(client, Component.translatable("latchlabel.sort.started", group.size(), "?"));
    }

    public static void onClientTick(Minecraft client) {
        if (!active && !selection.isEmpty()) {
            if (client == null || client.player == null || client.level == null) {
                selection.clear();
                selectionCategory = null;
                return;
            }
            startSelectionIfReleased(client);
            return;
        }
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
            case EXECUTE -> tickExecute(client);
            case ARRANGE -> tickArrange(client);
            case DONE -> finish(client);
        }
    }

    // ---------------------------------------------------------------- READ

    private static void tickRead(Minecraft client) {
        if (!serverReadAttempted) {
            serverReadAttempted = true;
            if (trySnapshotAllFromServer(client)) {
                finishRead(client);
                return;
            }
        }
        if (cursor >= chests.size()) {
            finishRead(client);
            return;
        }

        ChestKey chest = chests.get(cursor);
        Ready ready = ensureOpen(client, chest);
        if (ready == Ready.WAITING) {
            return;
        }
        if (ready == Ready.FAILED) {
            // Chest unreachable (e.g. shulker blocked from opening) — drop it so its
            // capacity doesn't distort the plan, and tell the user it was skipped.
            client.player.closeContainer();
            chests.remove(cursor);
            failedOpens++;
            openChest = null;
            cooldown = OP_COOLDOWN_TICKS;
            return;
        }
        readChest(client);
        client.player.closeContainer();
        openChest = null;
        cursor++;
        cooldown = OP_COOLDOWN_TICKS;
    }

    private static void readChest(Minecraft client) {
        AbstractContainerMenu menu = client.player.containerMenu;
        List<ItemStack> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                slots.add(slot.getItem());
            }
        }
        snapshots.add(snapshotFromStacks(slots));
    }

    /**
     * Builds a chest snapshot from its container slots, registering variants and accumulating totals.
     * Fed by both the open-menu read and the single-player server read so they stay identical.
     */
    private static SortPlanner.ChestSnapshot snapshotFromStacks(List<ItemStack> slots) {
        int capacity = 0;
        Map<String, Integer> contents = new HashMap<>();
        for (ItemStack stack : slots) {
            if (stack.isEmpty()) {
                capacity++;
                continue;
            }
            String key = sortableKey(stack, true);
            // A slot holding an unsortable item isn't usable capacity — leave it in place.
            if (key == null) {
                continue;
            }
            capacity++;
            contents.merge(key, stack.getCount(), Integer::sum);
            variantTotals.merge(key, stack.getCount(), Integer::sum);
        }
        return new SortPlanner.ChestSnapshot(capacity, contents);
    }

    /**
     * Single-player fast path: snapshot every group chest straight from the integrated server's
     * containers, with no opening. All-or-nothing — if any chest can't be read (multiplayer, unloaded,
     * blocked double chest), it clears what it registered and returns false so READ opens them the
     * normal way. Reading is authoritative; a mid-run change is still caught by EXECUTE's verify.
     */
    private static boolean trySnapshotAllFromServer(Minecraft client) {
        net.minecraft.server.MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.level == null) {
            return false;
        }
        net.minecraft.server.level.ServerLevel level = server.getLevel(client.level.dimension());
        if (level == null) {
            return false;
        }
        List<SortPlanner.ChestSnapshot> local = new ArrayList<>();
        for (ChestKey chest : chests) {
            List<ItemStack> slots = serverChestSlots(level, chest.pos());
            if (slots == null) {
                snapshots.clear();
                variantTotals.clear();
                prototypes.clear();
                return false;
            }
            local.add(snapshotFromStacks(slots));
        }
        snapshots.addAll(local);
        return true;
    }

    /** Reads a container's slots (combined for a double chest) from the server level, or null if unreadable. */
    private static List<ItemStack> serverChestSlots(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        net.minecraft.world.Container container;
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chestBlock) {
            container = net.minecraft.world.level.block.ChestBlock.getContainer(chestBlock, state, level, pos, false);
        } else if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
            container = c;
        } else {
            container = null;
        }
        if (container == null) {
            return null;
        }
        List<ItemStack> slots = new ArrayList<>(container.getContainerSize());
        for (int i = 0; i < container.getContainerSize(); i++) {
            slots.add(container.getItem(i));
        }
        return slots;
    }

    private static void finishRead(Minecraft client) {
        organizeFrozen = true;

        // Sort is a pure rearrange: only items already in the group ever move. The player's own
        // inventory is never vacuumed in, so used-slot count is monotonic non-increasing and the
        // group's capacity always fits — leftovers are structurally impossible. The only items
        // seeded here are ones the bot itself ferried/threw out of chests mid-run (a replan then
        // re-reads the emptied chests, so those in-flight items must go back in).
        Map<String, Integer> inventoryExtra = new HashMap<>(ferried);
        inventoryExtra.forEach((k, v) -> variantTotals.merge(k, v, Integer::sum));

        variants = buildVariants();
        int freeInvSlots = emptyInventorySlots(client) + SortPlanner.slotsUsed(inventoryExtra, variants);
        plan = SortPlanner.plan(snapshots, variants, inventoryExtra, freeInvSlots,
                SortSettings.dropOverflowAtFeet());

        if (plan.needsBuffer()) {
            message(client, Component.translatable("latchlabel.sort.no_buffer"));
            reset();
            return;
        }
        visitIndex = 0;
        groundWait = 0;
        phase = Phase.EXECUTE;
        message(client, Component.translatable("latchlabel.sort.started", chests.size(), plan.visits().size()));
    }

    /** Builds the planner variants, deriving each sort key from the configured sort method. */
    private static Map<String, SortPlanner.Variant> buildVariants() {
        Map<String, SortPlanner.Variant> out = new HashMap<>();
        for (Map.Entry<String, ItemStack> entry : prototypes.entrySet()) {
            String key = entry.getKey();
            ItemStack stack = entry.getValue();
            String registryId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String sortKey = switch (SortSettings.sortMethod()) {
                case REGISTRY_ID -> registryId;
                case ALPHABETICAL -> stack.getHoverName().getString().toLowerCase(Locale.ROOT) + "|" + registryId;
                case ITEM_COUNT -> String.format("%010d",
                        Integer.MAX_VALUE - variantTotals.getOrDefault(key, 0)) + "|" + registryId;
                case CREATIVE -> String.format("%08d",
                        creativeOrder().getOrDefault(stack.getItem(), Integer.MAX_VALUE)) + "|" + registryId;
            };
            out.put(key, new SortPlanner.Variant(key, sortKey, stack.getItem().getDefaultMaxStackSize()));
        }
        return out;
    }

    // Item → position in the creative search tab. Built once per run; left unbuilt (retried) if the
    // creative tabs aren't populated yet, so an early call can't cache an empty order.
    private static Map<Item, Integer> creativeOrder = null;

    private static Map<Item, Integer> creativeOrder() {
        if (creativeOrder != null) {
            return creativeOrder;
        }
        Map<Item, Integer> order = new HashMap<>();
        int i = 0;
        for (ItemStack stack : CreativeModeTabs.searchTab().getDisplayItems()) {
            if (!stack.isEmpty()) {
                order.putIfAbsent(stack.getItem(), i++);
            }
        }
        if (!order.isEmpty()) {
            creativeOrder = order;
        }
        return order;
    }

    // ------------------------------------------------------------- EXECUTE

    private static void tickExecute(Minecraft client) {
        if (plan == null || visitIndex >= plan.visits().size()) {
            phase = Phase.ARRANGE;
            cursor = 0;
            return;
        }
        SortPlanner.Visit visit = plan.visits().get(visitIndex);
        if (shouldWaitForGround(client, visit)) {
            groundWait++;
            return;
        }

        ChestKey chest = chests.get(visit.chestIndex());
        Ready ready = ensureOpen(client, chest);
        if (ready == Ready.WAITING) {
            return;
        }
        if (ready == Ready.FAILED) {
            // A chest stopped opening mid-run — re-read from scratch (READ drops the dead chest and
            // the plan is recomputed against reality) rather than corrupting the current visit indices.
            client.player.closeContainer();
            failedOpens++;
            openChest = null;
            requestReplan(client);
            return;
        }

        AbstractContainerMenu menu = client.player.containerMenu;
        if (!verifyContents(menu, visit)) {
            client.player.closeContainer();
            openChest = null;
            requestReplan(client);
            return;
        }

        // Replay the visit's moves in order, compacting before each so a fragment left by a partial
        // move never wastes a slot the next move needs (keeps real slot use matching the plan model).
        for (SortPlanner.Move move : visit.moves()) {
            compactContainer(client, menu);
            if (move.pull()) {
                pullExact(client, menu, move.variantKey(), move.count());
            } else {
                depositExact(client, menu, move.variantKey(), move.count());
            }
        }
        compactContainer(client, menu);
        // The plan flags the last visit to each chest, where it's at its final counts — sort it now
        // so no separate pass has to reopen it.
        if (visit.arrange()) {
            arrangeChest(client, menu);
            arrangedByExecute.add(visit.chestIndex());
        }

        client.player.closeContainer();
        openChest = null;
        visitIndex++;
        groundWait = 0;
        cooldown = OP_COOLDOWN_TICKS;
        message(client, Component.translatable("latchlabel.sort.progress", visitIndex, plan.visits().size()));
    }

    /**
     * Residual arrange: chests the move script never touched (already at their target counts) were
     * not arranged during EXECUTE, so open each of those once to sort its slots. Chests the sort
     * touched were already arranged on their final visit.
     */
    private static void tickArrange(Minecraft client) {
        while (cursor < chests.size() && arrangedByExecute.contains(cursor)) {
            cursor++;
        }
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
            AbstractContainerMenu menu = client.player.containerMenu;
            compactContainer(client, menu);
            arrangeChest(client, menu);
        }
        client.player.closeContainer();
        openChest = null;
        cursor++;
        cooldown = OP_COOLDOWN_TICKS;
    }

    /** Idle (bounded) when this visit's deposits are still lying on the ground waiting for pickup. */
    private static boolean shouldWaitForGround(Minecraft client, SortPlanner.Visit visit) {
        if (!groundPending || groundWait >= GROUND_WAIT_TICKS) {
            return false;
        }
        for (SortPlanner.Move move : visit.moves()) {
            if (!move.pull() && countInInventory(client, move.variantKey()) < move.count()) {
                return true;
            }
        }
        return false;
    }

    /** Chest must still hold exactly what the plan predicted; anything else means replan. */
    private static boolean verifyContents(AbstractContainerMenu menu, SortPlanner.Visit visit) {
        Map<String, Integer> found = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String key = sortableKey(stack, false);
            if (key != null) {
                found.merge(key, stack.getCount(), Integer::sum);
            }
        }
        return found.equals(visit.expectedContents());
    }

    /**
     * Pulls exactly {@code count} items of the variant out of the open chest and into the player
     * inventory, throwing any that don't fit at the player's feet when drop-at-feet is enabled.
     * Whole stacks shift-click across; the boundary remainder is peeled off one slot at a time.
     */
    private static void pullExact(Minecraft client, AbstractContainerMenu menu, String variantKey, int count) {
        ItemStack prototype = prototypes.get(variantKey);
        boolean dropAtFeet = SortSettings.dropOverflowAtFeet();
        int remaining = count;
        for (Slot slot : menu.slots) {
            if (remaining <= 0) {
                break;
            }
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(prototype, stack)) {
                continue;
            }
            int before = stack.getCount();
            if (before <= remaining) {
                // Take the whole stack: shift-click into inventory, or throw it if the inventory is full.
                if (quickMove(client, menu, slot.index)) {
                    int movedToInv = before - countInSlot(menu, slot.index, variantKey);
                    remaining -= movedToInv;
                    noteFerried(variantKey, movedToInv);
                }
                int stillHere = countInSlot(menu, slot.index, variantKey);
                if (stillHere > 0 && dropAtFeet) {
                    client.gameMode.handleContainerInput(menu.containerId, slot.index, 1, ContainerInput.THROW, client.player);
                    noteFerried(variantKey, stillHere);
                    groundPending = true;
                    remaining -= stillHere;
                    movedTotal++;
                }
            } else if (pullPartial(client, menu, slot.index, remaining)) {
                remaining = 0;
            }
        }
    }

    /**
     * Peels up to {@code count} items off a chest slot into the player inventory, filling existing
     * same-item partial stacks first (so it works even when every inventory slot is occupied but a
     * matching partial has room), then empty slots. Returns the remainder to the chest slot.
     */
    private static boolean pullPartial(Minecraft client, AbstractContainerMenu menu, int chestSlotId, int count) {
        ItemStack ref = menu.slots.get(chestSlotId).getItem().copy();
        if (ref.isEmpty()) {
            return false;
        }
        String variantKey = sortableKey(ref, false);
        pickup(client, menu, chestSlotId);
        int[] toPlace = { count };
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && canStackOnto(slot.getItem(), ref)) {
                placeSome(client, menu, slot.index, toPlace);
            }
        }
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.getItem().isEmpty()) {
                placeSome(client, menu, slot.index, toPlace);
            }
        }
        if (!menu.getCarried().isEmpty()) {
            pickup(client, menu, chestSlotId);
        }
        if (variantKey != null) {
            noteFerried(variantKey, count - toPlace[0]);
        }
        movedTotal++;
        return true;
    }

    /** Places single items from the cursor onto {@code slotId} until it's full or {@code toPlace} runs out. */
    private static void placeSome(Minecraft client, AbstractContainerMenu menu, int slotId, int[] toPlace) {
        while (toPlace[0] > 0 && !menu.getCarried().isEmpty()) {
            ItemStack dst = menu.slots.get(slotId).getItem();
            if (!dst.isEmpty() && (dst.getCount() >= dst.getMaxStackSize()
                    || !ItemStack.isSameItemSameComponents(dst, menu.getCarried()))) {
                break;
            }
            client.gameMode.handleContainerInput(menu.containerId, slotId, 1, ContainerInput.PICKUP, client.player);
            toPlace[0]--;
        }
    }

    private static boolean canStackOnto(ItemStack dst, ItemStack moving) {
        return !dst.isEmpty() && dst.getCount() < dst.getMaxStackSize()
                && ItemStack.isSameItemSameComponents(dst, moving);
    }

    /**
     * Deposits items of the variant from the player inventory, capped at what the bot currently
     * owes back to the group ({@code ferried}). This is the hard guarantee that the player's own
     * stock is never stored: the bot only ever puts back what it itself pulled out, so if you hold
     * 3 iron while sorting an iron chest, those 3 stay put — that slot is never used by the sort.
     */
    private static void depositExact(Minecraft client, AbstractContainerMenu menu, String variantKey, int count) {
        ItemStack prototype = prototypes.get(variantKey);
        int remaining = Math.min(count, ferried.getOrDefault(variantKey, 0));
        for (Slot slot : menu.slots) {
            if (remaining <= 0) {
                break;
            }
            if (!(slot.container instanceof Inventory)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(prototype, stack)) {
                continue;
            }
            int before = stack.getCount();
            if (before <= remaining) {
                if (quickMove(client, menu, slot.index)) {
                    int moved = before - countInSlot(menu, slot.index, variantKey);
                    remaining -= moved;
                    noteFerried(variantKey, -moved);
                }
            } else if (depositPartial(client, menu, slot.index, remaining)) {
                int moved = before - countInSlot(menu, slot.index, variantKey);
                remaining -= moved;
                noteFerried(variantKey, -moved);
            }
        }
    }

    /**
     * Moves up to {@code count} items from a player slot into the open container, filling existing
     * same-item partial stacks first (so it can top up a chest that has no empty slot but whose
     * matching stack has room) and then empty slots, returning the remainder to the player slot.
     */
    private static boolean depositPartial(Minecraft client, AbstractContainerMenu menu, int playerSlotId, int count) {
        ItemStack ref = menu.slots.get(playerSlotId).getItem().copy();
        if (ref.isEmpty()) {
            return false;
        }
        pickup(client, menu, playerSlotId);
        int[] toPlace = { count };
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) && canStackOnto(slot.getItem(), ref)) {
                placeSome(client, menu, slot.index, toPlace);
            }
        }
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) && slot.getItem().isEmpty()) {
                placeSome(client, menu, slot.index, toPlace);
            }
        }
        if (!menu.getCarried().isEmpty()) {
            pickup(client, menu, playerSlotId);
        }
        movedTotal++;
        return true;
    }

    /**
     * Merges same-item partial stacks within the open container's own slots (not the player inventory),
     * packing them to max stack size. Vanilla shift-click only merges an incoming stack into one
     * existing partial, so stacks native to a container never combine without this.
     */
    private static boolean compactContainer(Minecraft client, AbstractContainerMenu menu) {
        List<Integer> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) {
                slots.add(slot.index);
            }
        }
        boolean moved = false;
        for (int a = 0; a < slots.size(); a++) {
            int i = slots.get(a);
            ItemStack target = menu.slots.get(i).getItem();
            if (target.isEmpty() || target.getCount() >= target.getMaxStackSize()) {
                continue;
            }
            for (int b = a + 1; b < slots.size(); b++) {
                int j = slots.get(b);
                ItemStack src = menu.slots.get(j).getItem();
                if (src.isEmpty() || !ItemStack.isSameItemSameComponents(target, src)) {
                    continue;
                }
                // pickup src → cursor; place onto target (fills to max, remainder on cursor);
                // if remainder, drop it back into now-empty src.
                pickup(client, menu, j);
                pickup(client, menu, i);
                if (!menu.getCarried().isEmpty()) {
                    pickup(client, menu, j);
                }
                movedTotal++;
                moved = true;
                target = menu.slots.get(i).getItem();
                if (target.isEmpty() || target.getCount() >= target.getMaxStackSize()) {
                    break;
                }
            }
        }
        return moved;
    }

    /** Selection-sorts the chest's sortable slots by sort key (empties last), via pickup-swaps. */
    // ponytail: all swaps for one chest run in a single tick — a burst of container clicks.
    // If a strict server flags the burst, spread the swaps across ticks.
    private static void arrangeChest(Minecraft client, AbstractContainerMenu menu) {
        List<Integer> slots = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            // Unsortable stacks are pinned in place — only empty and sortable slots take part.
            if (stack.isEmpty() || sortableKey(stack, false) != null) {
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

    /** Sort rank for a slot: the variant's sort key, with empty slots pushed to the end. */
    private static String slotRank(AbstractContainerMenu menu, int slotId) {
        ItemStack stack = menu.slots.get(slotId).getItem();
        if (stack.isEmpty()) {
            return "￿"; // sorts after any real sort key
        }
        String key = sortableKey(stack, false);
        SortPlanner.Variant variant = key == null ? null : variants.get(key);
        return variant == null ? "￾" : variant.sortKey();
    }

    // ------------------------------------------------------------- REPLAN

    /** Any surprise (verify mismatch, chest gone mid-run) → re-read + replan, a bounded number of times. */
    private static void requestReplan(Minecraft client) {
        cooldown = OP_COOLDOWN_TICKS;
        if (replans >= MAX_REPLANS || chests.isEmpty()) {
            phase = Phase.DONE;
            return;
        }
        replans++;
        plan = null;
        snapshots.clear();
        variantTotals.clear();
        arrangedByExecute.clear();
        serverReadAttempted = false;
        cursor = 0;
        phase = Phase.READ;
    }

    // ------------------------------------------------------------- SHARED

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

    /**
     * The stack's variant key, or null if the stack isn't sortable. {@code register} allows
     * creating a new variant (READ only); afterwards the variant set is frozen so mid-run
     * strangers are ignored. Keys are session-local: registry id plus a discriminator per
     * distinct component set, matched via {@link ItemStack#isSameItemSameComponents}.
     */
    private static String sortableKey(ItemStack stack, boolean register) {
        for (Map.Entry<String, ItemStack> entry : prototypes.entrySet()) {
            if (ItemStack.isSameItemSameComponents(entry.getValue(), stack)) {
                return entry.getKey();
            }
        }
        if (!register || (organizeMode && organizeFrozen)) {
            return null;
        }
        if (!organizeMode && !isTargetCategory(stack)) {
            return null;
        }
        String key = BuiltInRegistries.ITEM.getKey(stack.getItem()) + "#" + prototypes.size();
        prototypes.put(key, stack.copyWithCount(1));
        return key;
    }

    private static boolean isTargetCategory(ItemStack stack) {
        return ShulkerItemCategoryBridge.resolveCategoryIdForStack(stack)
                .or(() -> LatchLabelClientState.itemCategoryMappingService().categoryIdFor(stack))
                .filter(categoryId::equals)
                .isPresent();
    }

    /** Adjusts the ferry ledger: positive on pickup, negative on deposit (used to seed replans). */
    private static void noteFerried(String key, int delta) {
        if (delta == 0) {
            return;
        }
        int next = Math.max(0, ferried.getOrDefault(key, 0) + delta);
        if (next == 0) {
            ferried.remove(key);
        } else {
            ferried.put(key, next);
        }
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

    /** Count of the variant now in {@code slotId}, 0 if empty or a different variant. */
    private static int countInSlot(AbstractContainerMenu menu, int slotId, String variantKey) {
        ItemStack now = menu.slots.get(slotId).getItem();
        if (now.isEmpty()) {
            return 0;
        }
        ItemStack prototype = prototypes.get(variantKey);
        return prototype != null && ItemStack.isSameItemSameComponents(prototype, now) ? now.getCount() : 0;
    }

    private static int countInInventory(Minecraft client, String variantKey) {
        ItemStack prototype = prototypes.get(variantKey);
        if (prototype == null) {
            return 0;
        }
        Inventory inv = client.player.getInventory();
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(prototype, stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int emptyInventorySlots(Minecraft client) {
        Inventory inv = client.player.getInventory();
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    private static void pickup(Minecraft client, AbstractContainerMenu menu, int slotId) {
        client.gameMode.handleContainerInput(menu.containerId, slotId, 0, ContainerInput.PICKUP, client.player);
    }

    private static void finish(Minecraft client) {
        if (client.player != null) {
            client.player.closeContainer();
            boolean leftovers = (plan != null && !plan.complete()) || !ferried.isEmpty();
            Component msg = leftovers
                    ? Component.translatable("latchlabel.sort.full", chests.size())
                    : Component.translatable("latchlabel.sort.finished", chests.size(), movedTotal);
            message(client, msg);
            if (failedOpens > 0) {
                client.player.sendSystemMessage(Component.translatable("latchlabel.sort.skipped", failedOpens));
            }
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
        organizeMode = false;
        organizeFrozen = false;
        phase = Phase.DONE;
        openChest = null;
        chests = List.of();
        prototypes.clear();
        variantTotals.clear();
        snapshots.clear();
        variants = Map.of();
        plan = null;
        visitIndex = 0;
        replans = 0;
        arrangedByExecute.clear();
        serverReadAttempted = false;
        ferried.clear();
        groundPending = false;
        groundWait = 0;
        cursor = 0;
        cooldown = 0;
        failedOpens = 0;
        movedTotal = 0;
    }

    private static void message(Minecraft client, Component text) {
        if (client.player != null) {
            client.player.sendOverlayMessage(text);
        }
    }

    private static boolean isShiftDown(Window window) {
        return window != null && (
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)
        );
    }

    private static boolean isAltDown(Window window) {
        return window != null && (
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)
        );
    }
}
