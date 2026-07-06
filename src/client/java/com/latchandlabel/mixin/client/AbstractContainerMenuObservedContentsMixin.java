package com.latchandlabel.mixin.client;

import com.latchandlabel.client.find.ContainerObserver;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuObservedContentsMixin {
    @Inject(method = "initializeContents", at = @At("TAIL"))
    private void latchlabel$onInitializeContents(int revision, List<ItemStack> stacks, ItemStack cursorStack, CallbackInfo ci) {
        ContainerObserver.onContainerContents((AbstractContainerMenu) (Object) this);
    }
}
