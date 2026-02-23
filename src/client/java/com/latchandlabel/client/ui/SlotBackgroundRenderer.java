package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

public final class SlotBackgroundRenderer {
    private SlotBackgroundRenderer() {
    }

    public static void renderSlot(HandledScreen<?> handledScreen, DrawContext context, Slot slot) {
        ItemStack stack = slot.getStack();
        if (stack.isEmpty()) {
            return;
        }

        boolean inspectDown = isInspectDown();
        Optional<Category> activeCategoryOpt = inspectDown
                ? Optional.empty()
                : ContainerTagButtonManager.activeCategoryFor(handledScreen);

        Optional<Category> itemCategoryOpt = LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(stack)
                .flatMap(LatchLabelClientState.categoryStore()::getById);
        if (itemCategoryOpt.isEmpty()) {
            return;
        }

        Category categoryToDraw;
        if (inspectDown) {
            categoryToDraw = itemCategoryOpt.get();
        } else {
            if (activeCategoryOpt.isEmpty()) {
                return;
            }
            Category activeCategory = activeCategoryOpt.get();
            if (!activeCategory.id().equals(itemCategoryOpt.get().id())) {
                return;
            }
            categoryToDraw = activeCategory;
        }

        int alpha = inspectDown ? 0x99 : 0x66;
        int color = (alpha << 24) | (categoryToDraw.color() & 0x00FFFFFF);
        int left = slot.x;
        int top = slot.y;
        context.fill(left, top, left + 16, top + 16, color);
    }

    private static boolean isInspectDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }

        boolean shiftDown = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean altDown = InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
        return shiftDown || altDown;
    }
}
