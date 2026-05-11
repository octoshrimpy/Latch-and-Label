package com.latchandlabel.mixin.client;

import com.latchandlabel.client.book.BookConfirmScreen;
import com.latchandlabel.client.book.BookExportImportService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenBookAltClickMixin {
    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void latchlabel$altClickBook(MouseButtonEvent mouseButton, boolean consumed, CallbackInfoReturnable<Boolean> cir) {
        if (mouseButton.button() != 0) {
            return;
        }
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            return;
        }
        if (!isAltDown()) {
            return;
        }

        ItemStack stack = hoveredSlot.getItem();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }

        if (stack.is(Items.WRITABLE_BOOK)) {
            mc.gui.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.EXPORT));
            cir.setReturnValue(true);
        } else if (stack.is(Items.WRITTEN_BOOK) && BookExportImportService.isLatchLabelBook(stack)) {
            mc.gui.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.IMPORT));
            cir.setReturnValue(true);
        }
    }

    private static boolean isAltDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        return InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
    }
}
