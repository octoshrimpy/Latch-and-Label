package com.latchandlabel.client.tooltip;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.tagging.ShulkerItemCategoryBridge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

public final class ItemCategoryTooltipHandler {
    private ItemCategoryTooltipHandler() {
    }

    public static void appendCategoryLine(ItemStack stack, List<Component> lines) {
        Optional<String> shulkerCategoryId = ShulkerItemCategoryBridge.resolveCategoryIdForStack(stack);
        if (!ClientInputHandler.isShiftDown() && shulkerCategoryId.isEmpty()) {
            return;
        }

        Optional<Category> category = shulkerCategoryId
                .or(() -> LatchLabelClientState.itemCategoryMappingService().categoryIdFor(stack))
                .flatMap(LatchLabelClientState.categoryStore()::getById);

        category.ifPresent(value -> lines.add(
                Component.translatable("latchlabel.tooltip.category", value.name())
                        .setStyle(Style.EMPTY.withColor(value.color()))
        ));
    }
}
