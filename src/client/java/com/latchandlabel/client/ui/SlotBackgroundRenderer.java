package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.model.Category;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;

import java.util.Optional;
import java.util.OptionalInt;
import com.latchandlabel.client.find.FindResultState;

public final class SlotBackgroundRenderer {
    private SlotBackgroundRenderer() {
    }

    public static void renderSlot(AbstractContainerScreen<?> handledScreen, GuiGraphics context, Slot slot) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        OptionalInt findHighlightColor = FindResultState.slotHighlightColor(stack);
        if (findHighlightColor.isPresent()) {
            context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, findHighlightColor.getAsInt());
            return;
        }

        boolean inspectDown = ClientInputHandler.isShiftDown() || ClientInputHandler.isAltDown();
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

}
