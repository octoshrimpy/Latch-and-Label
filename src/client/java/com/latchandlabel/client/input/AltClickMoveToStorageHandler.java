package com.latchandlabel.client.input;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.TransferSettings;
import com.latchandlabel.client.find.ContainerObserver;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.sort.ChestGroupSortService;
import com.latchandlabel.client.tagging.ShulkerItemCategoryBridge;
import com.latchandlabel.client.tagging.StorageTagResolver;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;

public final class AltClickMoveToStorageHandler {
    private static final int AUTO_MOVE_TIMEOUT_TICKS = 20;

    private static PendingAutoMove pendingAutoMove;
    private static Optional<String> copiedCategoryId = Optional.empty();

    private AltClickMoveToStorageHandler() {
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }

            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null || client.gameMode == null) {
                return InteractionResult.PASS;
            }
            if (!isAltDown(client.getWindow())) {
                return InteractionResult.PASS;
            }
            boolean pullNonMatching = isShiftDown(client.getWindow());
            if (pendingAutoMove != null) {
                return InteractionResult.FAIL;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                return InteractionResult.PASS;
            }

            Optional<ChestKey> resolved = StorageKeyResolver.resolveForWorld(world, pos);
            if (resolved.isEmpty()) {
                return InteractionResult.PASS;
            }
            Optional<String> categoryId = StorageTagResolver.resolveCategoryId(LatchLabelClientState.tagStore(), client, resolved.get());
            if (categoryId.isEmpty()) {
                return InteractionResult.PASS;
            }
            if (!pullNonMatching && !hasMatchingInventoryStacks(client.player.getInventory(), categoryId.get())) {
                sendActionBar(client, Component.translatable("latchlabel.drop.no_matching_blocks"));
                return InteractionResult.FAIL;
            }

            AutoMoveOperation operation = pullNonMatching
                    ? AutoMoveOperation.PULL_NON_MATCHING_FROM_STORAGE
                    : AutoMoveOperation.PUSH_MATCHING_TO_STORAGE;
            sendActionBar(client, Component.translatable(operation.startingTranslationKey()));

            // Both moves act on the *category group*, not just the punched chest, walking it in the
            // same fill order the sort uses: a push lands items where a sort would put them (a full
            // chest spills into the next), a pull evicts strays from every chest in the group.
            List<ChestKey> queue = ChestGroupSortService.groupInFillOrder(client, resolved.get(), categoryId.get());
            ChestKey target = queue.isEmpty() ? resolved.get() : queue.get(0);
            List<ChestKey> rest = queue.isEmpty() ? List.of() : List.copyOf(queue.subList(1, queue.size()));

            pendingAutoMove = new PendingAutoMove(target, rest, categoryId.get(), operation,
                    AUTO_MOVE_TIMEOUT_TICKS, System.currentTimeMillis(), 0, !pullNonMatching);
            try {
                openStorageForAutoMove(client, hitResultFor(target, direction), pullNonMatching);
            } catch (RuntimeException e) {
                pendingAutoMove = null;
                throw e;
            }
            return InteractionResult.FAIL;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND || player == null || hitResult == null) {
                return InteractionResult.PASS;
            }

            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null || !isAltDown(client.getWindow())) {
                return InteractionResult.PASS;
            }
            if (pendingAutoMove != null) {
                return InteractionResult.PASS;
            }
            // Brush + alt is the chest-group sort gesture — let that handler take it.
            if (client.player.getMainHandItem().is(Items.BRUSH)) {
                return InteractionResult.PASS;
            }

            BlockEntity blockEntity = world.getBlockEntity(hitResult.getBlockPos());
            if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                return InteractionResult.PASS;
            }

            Optional<ChestKey> resolved = StorageKeyResolver.resolveForWorld(world, hitResult.getBlockPos());
            if (resolved.isEmpty()) {
                return InteractionResult.PASS;
            }

            Optional<String> categoryId = StorageTagResolver.resolveCategoryId(LatchLabelClientState.tagStore(), client, resolved.get());
            if (categoryId.isPresent()) {
                copiedCategoryId = categoryId;
                sendActionBar(client, Component.translatable("latchlabel.tag_clipboard.copied", categoryName(categoryId.get())));
                return InteractionResult.FAIL;
            }

            if (copiedCategoryId.isEmpty()) {
                sendActionBar(client, Component.translatable("latchlabel.tag_clipboard.empty"));
                return InteractionResult.FAIL;
            }

            LatchLabelClientState.tagStore().setTag(resolved.get(), copiedCategoryId.get());
            sendActionBar(client, Component.translatable("latchlabel.tag_clipboard.pasted", categoryName(copiedCategoryId.get())));
            return InteractionResult.FAIL;
        });

        ClientTickEvents.END_CLIENT_TICK.register(AltClickMoveToStorageHandler::onEndTick);
    }

    /** Hit result for opening {@code chest}; {@code direction} may be null (the face doesn't matter). */
    private static BlockHitResult hitResultFor(ChestKey chest, Direction direction) {
        BlockPos pos = chest.pos();
        return new BlockHitResult(Vec3.atCenterOf(pos), direction == null ? Direction.UP : direction, pos, false);
    }

    private static void openStorageForAutoMove(Minecraft client, BlockHitResult hitResult, boolean wasShiftActivated) {
        if (!wasShiftActivated) {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
            return;
        }

        // Shift+alt-punch is a sneak-attack; temporarily unsneak so the container opens instead of nothing.
        LocalPlayer player = client.player;
        Input originalInput = player.getLastSentInput();
        Input unsneakingInput = withSneak(originalInput, false);
        boolean wasSneaking = player.isShiftKeyDown();

        player.connection.send(new ServerboundPlayerInputPacket(unsneakingInput));
        player.setShiftKeyDown(false);
        try {
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        } finally {
            player.setShiftKeyDown(wasSneaking);
            if (originalInput.shift()) {
                player.connection.send(new ServerboundPlayerInputPacket(originalInput));
            }
        }
    }

    private static Input withSneak(Input input, boolean shift) {
        return new Input(
                input.forward(),
                input.backward(),
                input.left(),
                input.right(),
                input.jump(),
                shift,
                input.sprint()
        );
    }

    private static boolean isShiftDown(Window window) {
        return window != null && (
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT)
        );
    }

    private static void onEndTick(Minecraft client) {
        if (client == null || client.getWindow() == null || !isAltDown(client.getWindow())) {
            copiedCategoryId = Optional.empty();
        }

        if (pendingAutoMove == null) {
            return;
        }
        if (client == null || client.player == null) {
            pendingAutoMove = null;
            return;
        }

        PendingAutoMove current = pendingAutoMove;
        if (current.remainingTicks() <= 0) {
            // The chest never opened — too far for the server's reach check, or a blocked shulker.
            // Skip it and try the next one in the group rather than aborting the whole move.
            if (!current.queue().isEmpty()) {
                pendingAutoMove = current.advanceToNextChest(current.movedStacks());
                openStorageForAutoMove(client, hitResultFor(pendingAutoMove.target(), null),
                        current.operation() == AutoMoveOperation.PULL_NON_MATCHING_FROM_STORAGE);
                return;
            }
            sendActionBar(client, Component.translatable("latchlabel.drop.failed"));
            pendingAutoMove = null;
            return;
        }

        // Wait for the freshly-opened menu to be synced from the server before clicking;
        // MC 26.x rejects container clicks carrying a stale stateId, which desyncs the move.
        boolean menuSynced = ContainerObserver.lastContentsSyncMs() > current.createdAtMs();
        if (menuSynced && McCompat.getScreen(client) instanceof AbstractContainerScreen<?> screen) {
            // Move by category against the open menu, not via the *ForCurrentScreen helpers: those
            // check the screen's chest against an expected key, and the screen's chest is resolved
            // from the crosshair — which still points at the punched chest while the group is walked.
            boolean pushing = current.operation() == AutoMoveOperation.PUSH_MATCHING_TO_STORAGE;
            int movedStacks = pushing
                    ? ContainerTagButtonManager.moveMatchingFromPlayerToStorage(client, screen.getMenu(), current.categoryId())
                    : ContainerTagButtonManager.moveNonMatchingFromStorageToPlayer(client, screen.getMenu(), current.categoryId());

            client.player.closeContainer();
            int movedTotal = current.movedStacks() + Math.max(0, movedStacks);
            // A push has more to do while it still holds matching items; a pull, while it still has
            // somewhere to put strays. Either way it stops when the group runs out.
            boolean moreToDo = pushing
                    ? hasMatchingInventoryStacks(client.player.getInventory(), current.categoryId())
                    : hasFreeInventorySlot(client.player.getInventory());
            if (moreToDo && !current.queue().isEmpty()) {
                pendingAutoMove = current.advanceToNextChest(movedTotal);
                openStorageForAutoMove(client, hitResultFor(pendingAutoMove.target(), null), !pushing);
                return;
            }
            boolean leftovers = pushing && moreToDo;
            sendActionBar(client, current.operation().resultMessage(movedTotal, current.hadMatchingStacks(), leftovers));
            pendingAutoMove = null;
            return;
        }

        pendingAutoMove = current.withRemainingTicks(current.remainingTicks() - 1);
    }

    private static boolean isAltDown(Window window) {
        return window != null && (
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT)
        );
    }

    private static String categoryName(String categoryId) {
        Optional<Category> category = LatchLabelClientState.categoryStore().getById(categoryId);
        if (category.isPresent()) {
            return category.get().name();
        }
        return categoryId;
    }

    private static boolean hasMatchingInventoryStacks(Container inventory, String categoryId) {
        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();

        for (int slotIndex = 0; slotIndex < 36; slotIndex++) {
            if (!includeHotbar && slotIndex < 9) {
                continue;
            }

            ItemStack stack = inventory.getItem(slotIndex);
            if (stack.isEmpty()) {
                continue;
            }
            boolean matches = resolveCategoryIdForStack(stack)
                    .map(categoryId::equals)
                    .orElse(false);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /** Room to pull more strays into. Partial stacks may also absorb items — an empty slot is the honest floor. */
    private static boolean hasFreeInventorySlot(Container inventory) {
        for (int slotIndex = 0; slotIndex < 36; slotIndex++) {
            if (inventory.getItem(slotIndex).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static Optional<String> resolveCategoryIdForStack(ItemStack stack) {
        return ShulkerItemCategoryBridge.resolveCategoryIdForStack(stack)
                .or(() -> LatchLabelClientState.itemCategoryMappingService().categoryIdFor(stack));
    }

    private static void sendActionBar(Minecraft client, Component text) {
        if (client != null && client.player != null) {
            client.player.sendOverlayMessage(text);
        }
    }

    private enum AutoMoveOperation {
        PUSH_MATCHING_TO_STORAGE(
                "latchlabel.drop.starting",
                "latchlabel.drop.finished",
                "latchlabel.drop.no_matching_blocks",
                "latchlabel.drop.storage_full"
        ),
        PULL_NON_MATCHING_FROM_STORAGE(
                "latchlabel.pull.starting",
                "latchlabel.pull.finished",
                "latchlabel.pull.no_non_matching_blocks",
                null
        );

        private final String startingTranslationKey;
        private final String finishedTranslationKey;
        private final String emptyTranslationKey;
        private final String blockedTranslationKey;

        AutoMoveOperation(
                String startingTranslationKey,
                String finishedTranslationKey,
                String emptyTranslationKey,
                String blockedTranslationKey
        ) {
            this.startingTranslationKey = startingTranslationKey;
            this.finishedTranslationKey = finishedTranslationKey;
            this.emptyTranslationKey = emptyTranslationKey;
            this.blockedTranslationKey = blockedTranslationKey;
        }

        private String startingTranslationKey() {
            return startingTranslationKey;
        }

        /** {@code leftovers}: matching items are still in the inventory and the group has no room left. */
        private Component resultMessage(int movedStacks, boolean hadEligibleItems, boolean leftovers) {
            if (leftovers && blockedTranslationKey != null) {
                return movedStacks > 0
                        ? Component.translatable("latchlabel.drop.group_full", movedStacks)
                        : Component.translatable(blockedTranslationKey);
            }
            if (movedStacks > 0) {
                return Component.translatable(finishedTranslationKey, movedStacks);
            }
            if (hadEligibleItems && blockedTranslationKey != null) {
                return Component.translatable(blockedTranslationKey);
            }
            return Component.translatable(emptyTranslationKey);
        }
    }

    /**
     * An in-flight alt-punch move. For a push, {@code queue} holds the rest of the category group in
     * fill order: when the open chest can't take everything, the move continues into the next one.
     */
    private record PendingAutoMove(
            ChestKey target,
            List<ChestKey> queue,
            String categoryId,
            AutoMoveOperation operation,
            int remainingTicks,
            long createdAtMs,
            int movedStacks,
            boolean hadMatchingStacks
    ) {
        private PendingAutoMove withRemainingTicks(int ticks) {
            return new PendingAutoMove(target, queue, categoryId, operation, ticks, createdAtMs,
                    movedStacks, hadMatchingStacks);
        }

        private PendingAutoMove advanceToNextChest(int movedSoFar) {
            return new PendingAutoMove(queue.get(0), queue.subList(1, queue.size()), categoryId, operation,
                    AUTO_MOVE_TIMEOUT_TICKS, System.currentTimeMillis(), movedSoFar, hadMatchingStacks);
        }
    }

}
