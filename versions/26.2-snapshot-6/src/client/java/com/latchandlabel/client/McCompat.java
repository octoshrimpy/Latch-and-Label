package com.latchandlabel.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/** Shims MC/Gui API differences between Minecraft versions. This file: 26.2-snapshot-6. */
public final class McCompat {
    private McCompat() {}

    public static Screen getScreen(Minecraft mc) {
        return mc.gui.screen();
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    public static Camera mainCamera(Minecraft mc) {
        return mc.gameRenderer.mainCamera();
    }

    public static Identifier dimensionId(Level level) {
        return level.dimension().identifier();
    }
}
