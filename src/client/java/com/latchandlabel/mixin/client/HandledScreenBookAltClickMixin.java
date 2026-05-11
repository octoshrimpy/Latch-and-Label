package com.latchandlabel.mixin.client;

import com.latchandlabel.client.book.BookConfirmScreen;
import com.latchandlabel.client.book.BookExportImportService;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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

// TODO: After ./gradlew genSources, verify:
//  1. The exact mouseClicked signature (Click click, boolean doubled) vs (double x, double y, int button)
//  2. The shadow field name for hoveredSlot (was focusedSlot in 1.21.11)
//  3. The @Inject method descriptor to match actual 26.2 bytecode

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenBookAltClickMixin {
    // TODO: verify field name — was "focusedSlot" in Yarn 1.21.11, likely "hoveredSlot" in Mojang 26.x
    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    // TODO: verify mouseClicked signature in 26.2; if Click class removed, change to (double mouseX, double mouseY, int button, CIR)
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void latchlabel$altClickBook(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0) {
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
            mc.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.EXPORT));
            cir.setReturnValue(true);
        } else if (stack.is(Items.WRITTEN_BOOK) && BookExportImportService.isLatchLabelBook(stack)) {
            mc.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.IMPORT));
            cir.setReturnValue(true);
        }
    }

    private static boolean isAltDown() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return false;
        }
        long handle = mc.getWindow().getWindow();
        return InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_LEFT_ALT)
                || InputConstants.isKeyDown(handle, GLFW.GLFW_KEY_RIGHT_ALT);
    }
}
