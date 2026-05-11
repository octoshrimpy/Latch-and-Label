package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.ContainerDetectionSettings;
import com.latchandlabel.client.config.MoveSourceMode;
import com.latchandlabel.client.config.TransferSettings;
import com.latchandlabel.client.tagging.StorageTagResolver;
import com.latchandlabel.client.tagging.ContainerScreenContextResolver;
import com.latchandlabel.client.tagging.TaggingController;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

public final class ContainerTagButtonManager {
    private static final int BUTTON_WIDTH = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 2;
    private static final WeakHashMap<Screen, ButtonRegistration> BUTTONS = new WeakHashMap<>();

    private ContainerTagButtonManager() {
    }

    public static void addIfSupported(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!ContainerScreenContextResolver.isSupportedScreen(screen)) {
            return;
        }

        Optional<ChestKey> resolvedTarget = ContainerScreenContextResolver.resolve(client, screen);
        int backgroundWidth = 176;
        int backgroundHeight = 166;

        if (screen instanceof ContainerScreen genericScreen) {
            ChestMenu handler = genericScreen.getMenu();
            backgroundHeight = 114 + handler.getRowCount() * 18;
        } else if (screen instanceof ShulkerBoxScreen) {
            backgroundHeight = 166;
        }

        int x = Math.max(4 + BUTTON_WIDTH + BUTTON_GAP, (scaledWidth - backgroundWidth) / 2 - (BUTTON_WIDTH + 4));
        int detectedX = x - BUTTON_WIDTH - BUTTON_GAP;
        int y = Math.max(4, (scaledHeight - backgroundHeight) / 2 + 4);
        int moveDownY = y + BUTTON_HEIGHT + BUTTON_GAP;
        int moveUpY = moveDownY + BUTTON_HEIGHT + BUTTON_GAP;

        Button detectedButton = Button.builder(Component.empty(), clicked -> {
                    Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
                    reg.ifPresent(registration -> detectCategoryFromStorage(screen)
                            .ifPresent(category -> registration.target()
                                    .ifPresent(chestKey -> TaggingController.applyTag(client, chestKey, category.id()))));
                })
                .pos(detectedX, y).size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        detectedButton.setTooltip(Tooltip.create(Component.empty()));
        detectedButton.visible = false;
        detectedButton.active = false;

        Button button = Button.builder(buttonLabel(resolvedTarget, resolveCategory(client, resolvedTarget)), clicked -> {
                    Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
                    reg.flatMap(ButtonRegistration::target).ifPresent(chestKey -> TaggingController.openPicker(client, chestKey));
                })
                .pos(x, y).size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        button.setTooltip(buttonTooltip(resolvedTarget, resolveCategory(client, resolvedTarget)));

        Button moveDownButton = Button.builder(Component.literal("↓"), clicked -> {
                    Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
                    reg.ifPresent(registration -> moveNonMatchingFromStorageToPlayer(client, screen, registration));
                })
                .pos(x, moveDownY).size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        moveDownButton.setTooltip(Tooltip.create(Component.translatable("latchlabel.move_button.down")));

        Button moveUpButton = Button.builder(Component.literal("↑"), clicked -> {
                    Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
                    reg.ifPresent(registration -> moveMatchingFromPlayerToStorage(client, screen, registration));
                })
                .pos(x, moveUpY).size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        moveUpButton.setTooltip(moveUpButtonTooltip(resolveCategory(client, resolvedTarget)));

        button.active = resolvedTarget.isPresent();
        moveDownButton.active = false;
        moveUpButton.active = false;
        Screens.getWidgets(screen).add(detectedButton);
        Screens.getWidgets(screen).add(button);
        Screens.getWidgets(screen).add(moveDownButton);
        Screens.getWidgets(screen).add(moveUpButton);

        ButtonRegistration registration = new ButtonRegistration(button, detectedButton, moveDownButton, moveUpButton, resolvedTarget);
        BUTTONS.put(screen, registration);

        ScreenMouseEvents.afterMouseClick(screen).register((currentScreen, clickContext, consumed) -> {
            if (consumed || clickContext.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                return consumed;
            }

            ButtonRegistration reg = BUTTONS.get(currentScreen);
            if (reg == null || reg.target().isEmpty() || !isWithinButton(clickContext.x(), clickContext.y(), reg.button())) {
                return consumed;
            }

            TaggingController.clearTag(client, reg.target().get());
            return true;
        });

        ScreenEvents.beforeExtract(screen).register((currentScreen, drawContext, mouseX, mouseY, tickDelta) -> {
            ButtonRegistration reg = BUTTONS.get(currentScreen);
            if (reg == null) {
                return;
            }
            button.active = reg.target().isPresent();
            Optional<Category> currentCategory = resolveCategory(client, reg.target());
            Optional<Category> detectedCategory = currentCategory.isEmpty()
                    ? detectCategoryFromStorage(screen)
                    : Optional.empty();
            boolean moveButtonsActive = reg.target().isPresent() && currentCategory.isPresent();
            reg.setCurrentCategory(currentCategory);
            reg.setDetectedCategory(detectedCategory);
            reg.detectedButton().visible = reg.target().isPresent() && currentCategory.isEmpty() && detectedCategory.isPresent();
            reg.detectedButton().active = reg.detectedButton().visible;
            reg.detectedButton().setTooltip(detectedButtonTooltip(detectedCategory));
            reg.moveDownButton().active = moveButtonsActive;
            reg.moveUpButton().active = moveButtonsActive;
            button.setMessage(buttonLabel(reg.target(), currentCategory));
            button.setTooltip(buttonTooltip(reg.target(), currentCategory));
            reg.moveUpButton().setTooltip(moveUpButtonTooltip(currentCategory));
        });

        ScreenEvents.afterExtract(screen).register((currentScreen, drawContext, mouseX, mouseY, tickDelta) -> {
            ButtonRegistration reg = BUTTONS.get(currentScreen);
            if (reg == null) {
                return;
            }

            renderButtonDecoration(drawContext, reg, reg.currentCategory(), reg.detectedCategory());
        });
    }

    public static Optional<Category> activeCategoryFor(Screen screen) {
        ButtonRegistration registration = BUTTONS.get(screen);
        if (registration == null) {
            return Optional.empty();
        }
        Minecraft client = Minecraft.getInstance();
        return resolveCategory(client, registration.target());
    }

    public static boolean triggerMoveToStorageForCurrentScreen(Minecraft client) {
        return triggerMoveToStorageForCurrentScreen(client, null);
    }

    public static boolean triggerMoveToStorageForCurrentScreen(Minecraft client, ChestKey expectedTarget) {
        return moveMatchingFromPlayerToStorageForCurrentScreen(client, expectedTarget) >= 0;
    }

    public static int moveMatchingFromPlayerToStorageForCurrentScreen(Minecraft client, ChestKey expectedTarget) {
        if (client == null || client.gui.screen() == null) {
            return -1;
        }

        Screen screen = client.gui.screen();
        ButtonRegistration registration = BUTTONS.get(screen);
        if (registration == null) {
            return -1;
        }
        if (expectedTarget != null) {
            Optional<ChestKey> actualTarget = registration.target();
            if (actualTarget.isEmpty() || !expectedTarget.equals(actualTarget.get())) {
                return -1;
            }
        }
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return -1;
        }
        Optional<Category> category = resolveCategory(client, registration.target());
        if (category.isEmpty()) {
            return -1;
        }

        return moveMatchingFromPlayerToStorage(client, handledScreen.getMenu(), category.get().id());
    }

    public static boolean hasMatchingPlayerStacksForCurrentScreen(Minecraft client, ChestKey expectedTarget) {
        if (client == null || client.gui.screen() == null) {
            return false;
        }

        Screen screen = client.gui.screen();
        ButtonRegistration registration = BUTTONS.get(screen);
        if (registration == null) {
            return false;
        }
        if (expectedTarget != null) {
            Optional<ChestKey> actualTarget = registration.target();
            if (actualTarget.isEmpty() || !expectedTarget.equals(actualTarget.get())) {
                return false;
            }
        }
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return false;
        }
        Optional<Category> category = resolveCategory(client, registration.target());
        return category
                .map(value -> hasMatchingPlayerStacks(handledScreen.getMenu(), value.id()))
                .orElse(false);
    }

    public static int moveNonMatchingFromStorageToPlayerForCurrentScreen(Minecraft client, ChestKey expectedTarget) {
        if (client == null || client.gui.screen() == null) {
            return -1;
        }

        Screen screen = client.gui.screen();
        ButtonRegistration registration = BUTTONS.get(screen);
        if (registration == null) {
            return -1;
        }
        if (expectedTarget != null) {
            Optional<ChestKey> actualTarget = registration.target();
            if (actualTarget.isEmpty() || !expectedTarget.equals(actualTarget.get())) {
                return -1;
            }
        }
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return -1;
        }
        Optional<Category> category = resolveCategory(client, registration.target());
        if (category.isEmpty()) {
            return -1;
        }

        return moveNonMatchingFromStorageToPlayer(client, handledScreen.getMenu(), category.get().id());
    }

    private static Component buttonLabel(Optional<ChestKey> target, Optional<Category> category) {
        if (target.isEmpty()) {
            return Component.literal("?");
        }

        if (category.isEmpty()) {
            return Component.literal("+");
        }

        return Component.empty();
    }

    private static Tooltip buttonTooltip(Optional<ChestKey> target, Optional<Category> category) {
        if (target.isEmpty()) {
            return Tooltip.create(Component.translatable("latchlabel.tag_button.unresolved"));
        }

        if (category.isPresent()) {
            return Tooltip.create(Component.translatable("latchlabel.tag_button.current", category.get().name()));
        }

        return Tooltip.create(Component.translatable("latchlabel.tag_button.none"));
    }

    private static Tooltip detectedButtonTooltip(Optional<Category> category) {
        return category
                .map(value -> Tooltip.create(Component.translatable("latchlabel.tag_button.detected", value.name())))
                .orElseGet(() -> Tooltip.create(Component.empty()));
    }

    private static Optional<Category> resolveCategory(Minecraft client, Optional<ChestKey> target) {
        return target.flatMap(chestKey -> StorageTagResolver.resolveCategoryId(LatchLabelClientState.tagStore(), client, chestKey)
                .flatMap(categoryId -> LatchLabelClientState.categoryStore().getById(categoryId)));
    }

    private static boolean isWithinButton(double mouseX, double mouseY, Button button) {
        return mouseX >= button.getX()
                && mouseX <= button.getX() + button.getWidth()
                && mouseY >= button.getY()
                && mouseY <= button.getY() + button.getHeight();
    }

    private static void renderButtonDecoration(
            GuiGraphicsExtractor context,
            ButtonRegistration registration,
            Optional<Category> currentCategory,
            Optional<Category> detectedCategory
    ) {
        renderButtonBorderAndIcon(context, registration.button(), currentCategory, true);
        if (registration.detectedButton().visible) {
            renderButtonBorderAndIcon(context, registration.detectedButton(), detectedCategory, true);
        }
        renderButtonBorderAndIcon(context, registration.moveDownButton(), currentCategory, false);
        renderButtonBorderAndIcon(context, registration.moveUpButton(), currentCategory, false);
    }

    private static void renderButtonBorderAndIcon(
            GuiGraphicsExtractor context,
            Button button,
            Optional<Category> category,
            boolean drawIcon
    ) {
        if (category.isEmpty()) {
            return;
        }

        int borderColor = 0xFF000000 | category.get().color();
        GuiUtils.drawBorder(context, button.getX(), button.getY(), button.getWidth(), button.getHeight(), borderColor);

        if (!drawIcon) {
            return;
        }

        Item item = BuiltInRegistries.ITEM.getValue(category.get().iconItemId());
        if (item == null) {
            return;
        }
        context.item(new ItemStack(item), button.getX() + 2, button.getY() + 2);
    }

    private static Optional<Category> detectCategoryFromStorage(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return Optional.empty();
        }

        AbstractContainerMenu handler = handledScreen.getMenu();
        Map<String, Integer> countsByCategory = new LinkedHashMap<>();
        int totalItems = 0;

        for (Slot slot : handler.slots) {
            if (slot.container instanceof Container) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            int count = stack.getCount();
            totalItems += count;
            LatchLabelClientState.itemCategoryMappingService()
                    .categoryIdFor(stack)
                    .filter(categoryId -> LatchLabelClientState.categoryStore().getById(categoryId).isPresent())
                    .ifPresent(categoryId -> countsByCategory.merge(categoryId, count, Integer::sum));
        }

        if (totalItems <= 0 || countsByCategory.isEmpty()) {
            return Optional.empty();
        }

        String bestCategoryId = null;
        int bestCount = 0;
        boolean tied = false;
        for (Map.Entry<String, Integer> entry : countsByCategory.entrySet()) {
            int count = entry.getValue();
            if (count > bestCount) {
                bestCategoryId = entry.getKey();
                bestCount = count;
                tied = false;
            } else if (count == bestCount) {
                tied = true;
            }
        }

        if (bestCategoryId == null || tied) {
            return Optional.empty();
        }

        int thresholdPercent = ContainerDetectionSettings.detectedCategoryThresholdPercent();
        if ((long) bestCount * 100 <= (long) totalItems * thresholdPercent) {
            return Optional.empty();
        }

        return LatchLabelClientState.categoryStore().getById(bestCategoryId);
    }

    private static Tooltip moveUpButtonTooltip(Optional<Category> category) {
        if (category.isEmpty()) {
            return Tooltip.create(Component.translatable("latchlabel.move_button.up"));
        }

        String sourceKey = TransferSettings.moveSourceMode() == MoveSourceMode.INVENTORY
                ? "screen.latchlabel.config.move_source.inventory"
                : "screen.latchlabel.config.move_source.inventory_hotbar";
        return Tooltip.create(Component.translatable(
                "latchlabel.move_button.up_mode",
                category.get().name(),
                Component.translatable(sourceKey)
        ));
    }

    private static void moveNonMatchingFromStorageToPlayer(Minecraft client, Screen screen, ButtonRegistration registration) {
        if (client == null || client.player == null || client.gameMode == null) {
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return;
        }

        Optional<Category> category = resolveCategory(client, registration.target());
        if (category.isEmpty()) {
            return;
        }

        int movedStacks = moveNonMatchingFromStorageToPlayer(
                client,
                handledScreen.getMenu(),
                category.get().id()
        );
        showMoveOverlay(client, "latchlabel.move_result.down", movedStacks);
    }

    public static int moveNonMatchingFromStorageToPlayer(Minecraft client, AbstractContainerMenu handler, String categoryId) {
        if (client == null || client.player == null || client.gameMode == null) {
            return 0;
        }

        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();
        int movedStacks = 0;

        for (Slot slot : handler.slots) {
            if (slot.container instanceof Container) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || isInCategory(stack, categoryId)) {
                continue;
            }

            if (moveStorageStackToPlayer(client, handler, slot, includeHotbar)) {
                movedStacks++;
            }
        }

        return movedStacks;
    }

    public static boolean hasMatchingPlayerStacks(AbstractContainerMenu handler, String categoryId) {
        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();

        for (Slot slot : handler.slots) {
            if (!(slot.container instanceof Container)) {
                continue;
            }
            if (!includeHotbar && slot.index < 9) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && isInCategory(stack, categoryId)) {
                return true;
            }
        }

        return false;
    }

    public static int moveMatchingFromPlayerToStorage(Minecraft client, AbstractContainerMenu handler, String categoryId) {
        if (client == null || client.player == null || client.gameMode == null) {
            return 0;
        }

        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();
        int movedStacks = 0;

        for (Slot slot : handler.slots) {
            if (!(slot.container instanceof Container)) {
                continue;
            }
            if (!includeHotbar && slot.index < 9) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isInCategory(stack, categoryId)) {
                continue;
            }

            if (quickMove(client, handler, slot.index)) {
                movedStacks++;
            }
        }

        return movedStacks;
    }

    private static void moveMatchingFromPlayerToStorage(Minecraft client, Screen screen, ButtonRegistration registration) {
        if (client == null || client.player == null || client.gameMode == null) {
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> handledScreen)) {
            return;
        }

        Optional<Category> category = resolveCategory(client, registration.target());
        if (category.isEmpty()) {
            return;
        }

        boolean hadMatchingStacks = hasMatchingPlayerStacks(handledScreen.getMenu(), category.get().id());
        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();
        AbstractContainerMenu handler = handledScreen.getMenu();
        int movedStacks = 0;

        for (Slot slot : handler.slots) {
            if (!(slot.container instanceof Container)) {
                continue;
            }
            if (!includeHotbar && slot.index < 9) {
                continue;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isInCategory(stack, category.get().id())) {
                continue;
            }

            if (quickMove(client, handler, slot.index)) {
                movedStacks++;
            }
        }

        if (movedStacks == 0 && hadMatchingStacks) {
            showMoveOverlay(client, Component.translatable("latchlabel.move_result.up_full"));
        } else {
            showMoveOverlay(client, Component.translatable("latchlabel.move_result.up", movedStacks));
        }
    }

    private static boolean quickMove(Minecraft client, AbstractContainerMenu handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size()) {
            return false;
        }

        Slot slot = handler.slots.get(slotId);
        if (!slot.hasItem()) {
            return false;
        }

        ItemStack before = slot.getItem().copy();
        client.gameMode.handleContainerInput(handler.containerId, slotId, 0, ContainerInput.QUICK_MOVE, client.player);
        ItemStack after = slot.getItem();
        return !ItemStack.matches(before, after);
    }

    private static boolean moveStorageStackToPlayer(
            Minecraft client,
            AbstractContainerMenu handler,
            Slot sourceSlot,
            boolean includeHotbar
    ) {
        if (includeHotbar) {
            return quickMove(client, handler, sourceSlot.index);
        }

        ItemStack sourceBefore = sourceSlot.getItem().copy();
        if (sourceBefore.isEmpty() || !handler.getCarried().isEmpty()) {
            return false;
        }
        if (!hasInventorySpace(handler, sourceBefore, false)) {
            return false;
        }

        pickup(client, handler, sourceSlot.index);
        if (handler.getCarried().isEmpty()) {
            return false;
        }

        moveCursorIntoInventory(client, handler, false);
        if (!handler.getCarried().isEmpty()) {
            pickup(client, handler, sourceSlot.index);
        }

        ItemStack sourceAfter = sourceSlot.getItem();
        return sourceAfter.isEmpty() || sourceAfter.getCount() < sourceBefore.getCount();
    }

    private static void moveCursorIntoInventory(Minecraft client, AbstractContainerMenu handler, boolean includeHotbar) {
        moveCursorIntoCompatiblePlayerStacks(client, handler, includeHotbar);
        moveCursorIntoEmptyPlayerSlots(client, handler, includeHotbar);
    }

    private static void moveCursorIntoCompatiblePlayerStacks(
            Minecraft client,
            AbstractContainerMenu handler,
            boolean includeHotbar
    ) {
        for (Slot slot : handler.slots) {
            ItemStack cursorStack = handler.getCarried();
            if (cursorStack.isEmpty()) {
                return;
            }
            if (!isAllowedPlayerDestination(slot, includeHotbar)) {
                continue;
            }
            ItemStack destination = slot.getItem();
            if (destination.isEmpty()
                    || !ItemStack.isSameItemSameComponents(cursorStack, destination)
                    || destination.getCount() >= maxInsertCount(slot, destination)
                    || !slot.mayPlace(cursorStack)) {
                continue;
            }
            pickup(client, handler, slot.index);
        }
    }

    private static void moveCursorIntoEmptyPlayerSlots(
            Minecraft client,
            AbstractContainerMenu handler,
            boolean includeHotbar
    ) {
        for (Slot slot : handler.slots) {
            ItemStack cursorStack = handler.getCarried();
            if (cursorStack.isEmpty()) {
                return;
            }
            if (!isAllowedPlayerDestination(slot, includeHotbar)
                    || !slot.getItem().isEmpty()
                    || !slot.mayPlace(cursorStack)) {
                continue;
            }
            pickup(client, handler, slot.index);
        }
    }

    private static boolean hasInventorySpace(AbstractContainerMenu handler, ItemStack stack, boolean includeHotbar) {
        for (Slot slot : handler.slots) {
            if (!isAllowedPlayerDestination(slot, includeHotbar) || !slot.mayPlace(stack)) {
                continue;
            }

            ItemStack destination = slot.getItem();
            if (destination.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(stack, destination)
                    && destination.getCount() < maxInsertCount(slot, destination)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedPlayerDestination(Slot slot, boolean includeHotbar) {
        if (!(slot.container instanceof Container)) {
            return false;
        }
        return includeHotbar || slot.index >= 9;
    }

    private static int maxInsertCount(Slot slot, ItemStack stack) {
        return Math.min(stack.getItem().getDefaultMaxStackSize(), slot.getMaxStackSize(stack));
    }

    private static void pickup(Minecraft client, AbstractContainerMenu handler, int slotId) {
        client.gameMode.handleContainerInput(handler.containerId, slotId, 0, ContainerInput.PICKUP, client.player);
    }

    private static boolean isInCategory(ItemStack stack, String categoryId) {
        return LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(stack)
                .map(categoryId::equals)
                .orElse(false);
    }

    private static void showMoveOverlay(Minecraft client, String key, int movedStacks) {
        showMoveOverlay(client, Component.translatable(key, movedStacks));
    }

    private static void showMoveOverlay(Minecraft client, Component text) {
        if (client.player == null) {
            return;
        }
        client.player.sendOverlayMessage(text);
    }

    private static final class ButtonRegistration {
        private final Button button;
        private final Button detectedButton;
        private final Button moveDownButton;
        private final Button moveUpButton;
        private final Optional<ChestKey> target;
        private Optional<Category> currentCategory = Optional.empty();
        private Optional<Category> detectedCategory = Optional.empty();

        private ButtonRegistration(
                Button button,
                Button detectedButton,
                Button moveDownButton,
                Button moveUpButton,
                Optional<ChestKey> target
        ) {
            this.button = button;
            this.detectedButton = detectedButton;
            this.moveDownButton = moveDownButton;
            this.moveUpButton = moveUpButton;
            this.target = target;
        }

        private Button button() {
            return button;
        }

        private Button detectedButton() {
            return detectedButton;
        }

        private Button moveDownButton() {
            return moveDownButton;
        }

        private Button moveUpButton() {
            return moveUpButton;
        }

        private Optional<ChestKey> target() {
            return target;
        }

        private Optional<Category> currentCategory() {
            return currentCategory;
        }

        private void setCurrentCategory(Optional<Category> currentCategory) {
            this.currentCategory = currentCategory == null ? Optional.empty() : currentCategory;
        }

        private Optional<Category> detectedCategory() {
            return detectedCategory;
        }

        private void setDetectedCategory(Optional<Category> detectedCategory) {
            this.detectedCategory = detectedCategory == null ? Optional.empty() : detectedCategory;
        }
    }
}
