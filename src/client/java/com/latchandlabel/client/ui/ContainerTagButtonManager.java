package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
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
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.WeakHashMap;

public final class ContainerTagButtonManager {
    private static final int BUTTON_WIDTH = 20;
    private static final int BUTTON_HEIGHT = 20;
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

        ButtonWidget button = ButtonWidget.builder(buttonLabel(resolvedTarget, resolveCategory(client, resolvedTarget)), clicked -> {
            Optional<ButtonRegistration> reg = Optional.ofNullable(BUTTONS.get(screen));
            reg.flatMap(ButtonRegistration::target).ifPresent(chestKey -> TaggingController.openPicker(client, chestKey));
        })
                .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(buttonTooltip(resolvedTarget, resolveCategory(client, resolvedTarget)))
                .build();

        button.active = resolvedTarget.isPresent();
        Screens.getButtons(screen).add(button);

        ButtonRegistration registration = new ButtonRegistration(button, resolvedTarget);
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
            button.setMessage(buttonLabel(reg.target(), currentCategory));
            button.setTooltip(buttonTooltip(reg.target(), currentCategory));
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

        int borderColor = 0xFF4A4A4A;
        if (currentCategory.isPresent()) {
            borderColor = 0xFF000000 | currentCategory.get().color();
        }
        context.drawStrokedRectangle(button.getX(), button.getY(), button.getWidth(), button.getHeight(), borderColor);

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

    private record ButtonRegistration(ButtonWidget button, Optional<ChestKey> target) {
    }
}
