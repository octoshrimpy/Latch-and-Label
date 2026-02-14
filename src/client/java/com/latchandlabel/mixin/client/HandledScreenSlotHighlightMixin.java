package com.latchandlabel.mixin.client;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

@Mixin(HandledScreen.class)
public abstract class HandledScreenSlotHighlightMixin {
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void latchlabel$drawCategoryBackground(DrawContext context, Slot slot, CallbackInfo ci) {
        if (slot.getStack().isEmpty()) {
            return;
        }

        Optional<Category> itemCategoryOpt = LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(slot.getStack())
                .flatMap(LatchLabelClientState.categoryStore()::getById);
        if (itemCategoryOpt.isEmpty()) {
            return;
        }

        boolean shiftDown = latchlabel$isShiftDown();
        Category categoryToDraw;
        if (shiftDown) {
            categoryToDraw = itemCategoryOpt.get();
        } else {
            Optional<Category> activeCategoryOpt = ContainerTagButtonManager.activeCategoryFor((Screen) (Object) this);
            if (activeCategoryOpt.isEmpty()) {
                return;
            }
            Category activeCategory = activeCategoryOpt.get();
            if (!activeCategory.id().equals(itemCategoryOpt.get().id())) {
                return;
            }
            categoryToDraw = activeCategory;
        }

        int alpha = shiftDown ? 0x99 : 0x66;
        int color = (alpha << 24) | (categoryToDraw.color() & 0x00FFFFFF);
        int left = slot.x;
        int top = slot.y;
        context.fill(left, top, left + 16, top + 16, color);
    }

    private static boolean latchlabel$isShiftDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }

        return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
