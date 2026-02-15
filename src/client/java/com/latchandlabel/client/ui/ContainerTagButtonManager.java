package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.WeakHashMap;

public final class ContainerTagButtonManager {
    private static final int BUTTON_WIDTH = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 2;
    private static final WeakHashMap<Screen, ButtonRegistration> BUTTONS = new WeakHashMap<>();

    private ContainerTagButtonManager() {
    }

    public static void addIfSupported(MinecraftClient client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!ContainerScreenContextResolver.isSupportedScreen(screen)) {
            return;
        }

        Optional<ChestKey> resolvedTarget = ContainerScreenContextResolver.resolve(client, screen);
        int backgroundWidth = 176;
        int backgroundHeight = 166;

        if (screen instanceof GenericContainerScreen genericScreen) {
            GenericContainerScreenHandler handler = genericScreen.getScreenHandler();
            backgroundHeight = 114 + handler.getRows() * 18;
        } else if (screen instanceof ShulkerBoxScreen) {
            backgroundHeight = 166;
        }

        int x = Math.max(4, (scaledWidth - backgroundWidth) / 2 - (BUTTON_WIDTH + 4));
        int y = Math.max(4, (scaledHeight - backgroundHeight) / 2 + 4);
        int moveDownY = y + BUTTON_HEIGHT + BUTTON_GAP;
        int moveUpY = moveDownY + BUTTON_HEIGHT + BUTTON_GAP;

        ButtonWidget button = ButtonWidget.builder(buttonLabel(resolvedTarget, resolveCategory(client, resolvedTarget)), clicked -> {
            Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
            reg.flatMap(ButtonRegistration::target).ifPresent(chestKey -> TaggingController.openPicker(client, chestKey));
        })
                .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(buttonTooltip(resolvedTarget, resolveCategory(client, resolvedTarget)))
                .build();

        ButtonWidget moveDownButton = ButtonWidget.builder(Text.literal("↓"), clicked -> {
                    Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
                    reg.ifPresent(registration -> moveNonMatchingFromStorageToPlayer(client, screen, registration));
                })
                .dimensions(x, moveDownY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.translatable("latchlabel.move_button.down")))
                .build();

        ButtonWidget moveUpButton = ButtonWidget.builder(Text.literal("↑"), clicked -> {
                    Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
                    reg.ifPresent(registration -> moveMatchingFromPlayerToStorage(client, screen, registration));
                })
                .dimensions(x, moveUpY, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(moveUpButtonTooltip(resolveCategory(client, resolvedTarget)))
                .build();

        button.active = resolvedTarget.isPresent();
        moveDownButton.active = false;
        moveUpButton.active = false;
        Screens.getButtons(screen).add(button);
        Screens.getButtons(screen).add(moveDownButton);
        Screens.getButtons(screen).add(moveUpButton);

        ButtonRegistration registration = new ButtonRegistration(button, moveDownButton, moveUpButton, resolvedTarget);
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

        ScreenEvents.afterRender(screen).register((currentScreen, drawContext, mouseX, mouseY, tickDelta) -> {
            ButtonRegistration reg = BUTTONS.get(currentScreen);
            if (reg == null) {
                return;
            }
            button.active = reg.target().isPresent();
            Optional<Category> currentCategory = resolveCategory(client, reg.target());
            boolean moveButtonsActive = reg.target().isPresent() && currentCategory.isPresent();
            reg.moveDownButton().active = moveButtonsActive;
            reg.moveUpButton().active = moveButtonsActive;
            button.setMessage(buttonLabel(reg.target(), currentCategory));
            button.setTooltip(buttonTooltip(reg.target(), currentCategory));
            reg.moveUpButton().setTooltip(moveUpButtonTooltip(currentCategory));
            renderButtonDecoration(drawContext, reg, currentCategory);
        });
    }

    public static Optional<Category> activeCategoryFor(Screen screen) {
        ButtonRegistration registration = BUTTONS.get(screen);
        if (registration == null) {
            return Optional.empty();
        }
        MinecraftClient client = MinecraftClient.getInstance();
        return resolveCategory(client, registration.target());
    }

    private static Text buttonLabel(Optional<ChestKey> target, Optional<Category> category) {
        if (target.isEmpty()) {
            return Text.literal("?");
        }

        if (category.isEmpty()) {
            return Text.literal("+");
        }

        return Text.empty();
    }

    private static Tooltip buttonTooltip(Optional<ChestKey> target, Optional<Category> category) {
        if (target.isEmpty()) {
            return Tooltip.of(Text.translatable("latchlabel.tag_button.unresolved"));
        }

        if (category.isPresent()) {
            return Tooltip.of(Text.translatable("latchlabel.tag_button.current", category.get().name()));
        }

        return Tooltip.of(Text.translatable("latchlabel.tag_button.none"));
    }

    private static Optional<Category> resolveCategory(MinecraftClient client, Optional<ChestKey> target) {
        return target.flatMap(chestKey -> StorageTagResolver.resolveCategoryId(client, chestKey)
                .flatMap(categoryId -> LatchLabelClientState.categoryStore().getById(categoryId)));
    }

    private static boolean isWithinButton(double mouseX, double mouseY, ButtonWidget button) {
        return mouseX >= button.getX()
                && mouseX <= button.getX() + button.getWidth()
                && mouseY >= button.getY()
                && mouseY <= button.getY() + button.getHeight();
    }

    private static void renderButtonDecoration(DrawContext context, ButtonRegistration registration, Optional<Category> currentCategory) {
        ButtonWidget button = registration.button();
        ButtonWidget moveDownButton = registration.moveDownButton();
        ButtonWidget moveUpButton = registration.moveUpButton();

        int borderColor = 0xFF4A4A4A;
        if (currentCategory.isPresent()) {
            borderColor = 0xFF000000 | currentCategory.get().color();
        }
        context.drawStrokedRectangle(button.getX(), button.getY(), button.getWidth(), button.getHeight(), borderColor);
        context.drawStrokedRectangle(moveDownButton.getX(), moveDownButton.getY(), moveDownButton.getWidth(), moveDownButton.getHeight(), borderColor);
        context.drawStrokedRectangle(moveUpButton.getX(), moveUpButton.getY(), moveUpButton.getWidth(), moveUpButton.getHeight(), borderColor);

        currentCategory.ifPresent(category -> {
            Item item = Registries.ITEM.get(category.iconItemId());
            if (item == null) {
                return;
            }

            int iconX = button.getX() + 2;
            int iconY = button.getY() + 2;
            context.drawItem(new ItemStack(item), iconX, iconY);
        });
    }

    private static Tooltip moveUpButtonTooltip(Optional<Category> category) {
        if (category.isEmpty()) {
            return Tooltip.of(Text.translatable("latchlabel.move_button.up"));
        }

        String sourceKey = TransferSettings.moveSourceMode() == MoveSourceMode.INVENTORY
                ? "screen.latchlabel.config.move_source.inventory"
                : "screen.latchlabel.config.move_source.inventory_hotbar";
        return Tooltip.of(Text.translatable(
                "latchlabel.move_button.up_mode",
                category.get().name(),
                Text.translatable(sourceKey)
        ));
    }

    private static void moveNonMatchingFromStorageToPlayer(MinecraftClient client, Screen screen, ButtonRegistration registration) {
        if (client == null || client.player == null || client.interactionManager == null) {
            return;
        }
        if (!(screen instanceof HandledScreen<?> handledScreen)) {
            return;
        }

        Optional<Category> category = resolveCategory(client, registration.target());
        if (category.isEmpty()) {
            return;
        }

        ScreenHandler handler = handledScreen.getScreenHandler();
        int movedStacks = 0;

        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || isInCategory(stack, category.get().id())) {
                continue;
            }

            if (quickMove(client, handler, slot.id)) {
                movedStacks++;
            }
        }

        showMoveOverlay(client, "latchlabel.move_result.down", movedStacks);
    }

    private static void moveMatchingFromPlayerToStorage(MinecraftClient client, Screen screen, ButtonRegistration registration) {
        if (client == null || client.player == null || client.interactionManager == null) {
            return;
        }
        if (!(screen instanceof HandledScreen<?> handledScreen)) {
            return;
        }

        Optional<Category> category = resolveCategory(client, registration.target());
        if (category.isEmpty()) {
            return;
        }

        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();
        ScreenHandler handler = handledScreen.getScreenHandler();
        int movedStacks = 0;

        for (Slot slot : handler.slots) {
            if (!(slot.inventory instanceof PlayerInventory)) {
                continue;
            }
            if (!includeHotbar && slot.getIndex() < 9) {
                continue;
            }

            ItemStack stack = slot.getStack();
            if (stack.isEmpty() || !isInCategory(stack, category.get().id())) {
                continue;
            }

            if (quickMove(client, handler, slot.id)) {
                movedStacks++;
            }
        }

        showMoveOverlay(client, "latchlabel.move_result.up", movedStacks);
    }

    private static boolean quickMove(MinecraftClient client, ScreenHandler handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size()) {
            return false;
        }

        Slot slot = handler.slots.get(slotId);
        if (!slot.hasStack()) {
            return false;
        }

        ItemStack before = slot.getStack().copy();
        client.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, client.player);
        ItemStack after = slot.getStack();
        return !ItemStack.areEqual(before, after);
    }

    private static boolean isInCategory(ItemStack stack, String categoryId) {
        return LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(stack)
                .map(categoryId::equals)
                .orElse(false);
    }

    private static void showMoveOverlay(MinecraftClient client, String key, int movedStacks) {
        if (client.inGameHud == null) {
            return;
        }
        client.inGameHud.setOverlayMessage(Text.translatable(key, movedStacks), false);
    }

    private record ButtonRegistration(
            ButtonWidget button,
            ButtonWidget moveDownButton,
            ButtonWidget moveUpButton,
            Optional<ChestKey> target
    ) {
    }
}
