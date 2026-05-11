package com.latchandlabel.client.render;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

public final class RenderLayerCompat {
    private RenderLayerCompat() {
    }

    public static RenderType debugFilledBox() {
        return RenderTypes.debugFilledBox();
    }
}
