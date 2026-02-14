package com.latchandlabel.client.tooltip;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.tagging.ShulkerItemCategoryBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;

public final class ItemCategoryTooltipHandler {
    private ItemCategoryTooltipHandler() {
    }

    public static void appendCategoryLine(ItemStack stack, List<Text> lines) {
        Optional<String> shulkerCategoryId = ShulkerItemCategoryBridge.resolveCategoryIdForStack(stack);
        if (!isShiftDown() && shulkerCategoryId.isEmpty()) {
            return;
        }

        Optional<Category> category = shulkerCategoryId
                .or(() -> LatchLabelClientState.itemCategoryMappingService().categoryIdFor(stack))
                .flatMap(LatchLabelClientState.categoryStore()::getById);

        category.ifPresent(value -> lines.add(
                Text.translatable("latchlabel.tooltip.category", value.name())
                        .setStyle(Style.EMPTY.withColor(value.color()))
        ));
    }

    private static boolean isShiftDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }

        return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
