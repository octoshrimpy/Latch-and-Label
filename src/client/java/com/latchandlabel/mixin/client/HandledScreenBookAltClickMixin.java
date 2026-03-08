package com.latchandlabel.mixin.client;

import com.latchandlabel.client.book.BookConfirmScreen;
import com.latchandlabel.client.book.BookExportImportService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public abstract class HandledScreenBookAltClickMixin {
    @Shadow
    @Nullable
    protected Slot focusedSlot;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void latchlabel$altClickBook(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) {
            return;
        }
        if (focusedSlot == null || !focusedSlot.hasStack()) {
            return;
        }
        if (!isAltDown()) {
            return;
        }

        ItemStack stack = focusedSlot.getStack();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) {
            return;
        }

        if (stack.isOf(Items.WRITABLE_BOOK)) {
            mc.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.EXPORT));
            cir.setReturnValue(true);
        } else if (stack.isOf(Items.WRITTEN_BOOK) && BookExportImportService.isLatchLabelBook(stack)) {
            mc.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.IMPORT));
            cir.setReturnValue(true);
        }
    }

    private static boolean isAltDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        return InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
                || InputUtil.isKeyPressed(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
    }
}
