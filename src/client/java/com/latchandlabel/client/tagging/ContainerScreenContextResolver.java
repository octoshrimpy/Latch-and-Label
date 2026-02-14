package com.latchandlabel.client.tagging;

import com.latchandlabel.client.targeting.ContainerTargeting;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.ChestKey;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.screen.ScreenHandler;

import java.util.Optional;

public final class ContainerScreenContextResolver {
    private static final boolean DEBUG = Boolean.getBoolean("latchlabel.debug.container_resolve");

    private ContainerScreenContextResolver() {
    }

    public static Optional<ChestKey> resolve(MinecraftClient client, Screen screen) {
        if (!isSupportedScreen(screen) || client == null || client.world == null) {
            return Optional.empty();
        }

        Optional<ChestKey> fromTarget = ContainerTargeting.getTargetedContainer(client);
        if (fromTarget.isPresent()) {
            debug("Resolved container from current crosshair target: " + fromTarget.get().toStringKey());
            return fromTarget;
        }

        Optional<ChestKey> fromRecent = ContainerInteractionTracker.getRecent();
        if (fromRecent.isPresent()) {
            ChestKey key = fromRecent.get();
            if (key.dimensionId().equals(client.world.getRegistryKey().getValue()) && screenHandlerMatches(client, screen)) {
                debug("Resolved container from recent interaction: " + key.toStringKey());
                return Optional.of(key);
            }
        }

        debug("Unable to resolve container for screen: " + screen.getClass().getSimpleName());
        return Optional.empty();
    }

    public static boolean isSupportedScreen(Screen screen) {
        return screen instanceof GenericContainerScreen
                || screen instanceof ShulkerBoxScreen;
    }

    private static boolean screenHandlerMatches(MinecraftClient client, Screen screen) {
        if (client.player == null) {
            return false;
        }

        ScreenHandler activeHandler = client.player.currentScreenHandler;
        if (screen instanceof HandledScreen<?> handledScreen) {
            return activeHandler == handledScreen.getScreenHandler();
        }
        return false;
    }

    private static void debug(String message) {
        if (DEBUG) {
            LatchLabel.LOGGER.info("[ContainerResolve] {}", message);
        }
    }
}
