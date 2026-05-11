package com.latchandlabel.client.render;

import net.minecraft.client.renderer.RenderType;

// TODO: After ./gradlew genSources, confirm RenderType.debugFilledBox() exists in 26.2.
// MC 26.2 introduces Vulkan backend — if the debug render type moved or was removed,
// replace with the appropriate Blaze3D/RenderType equivalent.

public final class RenderLayerCompat {
    private RenderLayerCompat() {
    }

    public static RenderType debugFilledBox() {
        return RenderType.debugFilledBox();
    }
}
