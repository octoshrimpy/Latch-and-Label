package com.latchandlabel.mixin.client;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.ui.SlotBackgroundRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenSlotBackgroundLayerMixin {
    private static final boolean DEBUG = Boolean.getBoolean("latchlabel.debug.slot_bg");
    private static boolean logged12110;
    private static boolean logged12111;

    @Inject(
            method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawItem(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V"
            ),
            require = 0
    )
    private void latchlabel$drawSlotBackground12110(DrawContext context, Slot slot, CallbackInfo ci) {
        if (DEBUG && !logged12110) {
            LatchLabel.LOGGER.info("[SlotBg] 1.21.10 drawSlot hook fired");
            logged12110 = true;
        }
        SlotBackgroundRenderer.renderSlot((HandledScreen<?>) (Object) this, context, slot);
    }

    @Inject(
            method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawItem(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V"
            ),
            require = 0
    )
    private void latchlabel$drawSlotBackground12111(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (DEBUG && !logged12111) {
            LatchLabel.LOGGER.info("[SlotBg] 1.21.11 drawSlot hook fired");
            logged12111 = true;
        }
        SlotBackgroundRenderer.renderSlot((HandledScreen<?>) (Object) this, context, slot);
    }
}
