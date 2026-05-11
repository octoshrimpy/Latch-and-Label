package com.latchandlabel.mixin.client;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.ui.SlotBackgroundRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TODO: After ./gradlew genSources, verify:
//  1. method = "renderSlot" (was "drawSlot" in Yarn)
//  2. @At INVOKE target descriptor for the item rendering call inside renderSlot
//     The INVOKE target below uses "renderItem" which is the expected Mojang name — confirm

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenSlotBackgroundLayerMixin {
    private static final boolean DEBUG = Boolean.getBoolean("latchlabel.debug.slot_bg");
    private static boolean logged;

    @Inject(
            method = "renderSlot",
            at = @At(
                    value = "INVOKE",
                    // TODO: verify exact method name — was "drawItem" in Yarn, Mojang name is likely "renderItem"
                    target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;renderItem(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"
            ),
            require = 0
    )
    private void latchlabel$drawSlotBackground(GuiGraphics context, Slot slot, CallbackInfo ci) {
        if (DEBUG && !logged) {
            LatchLabel.LOGGER.info("[SlotBg] renderSlot hook fired");
            logged = true;
        }
        SlotBackgroundRenderer.renderSlot((AbstractContainerScreen<?>) (Object) this, context, slot);
    }
}
