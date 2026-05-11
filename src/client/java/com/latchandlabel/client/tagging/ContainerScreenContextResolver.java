package com.latchandlabel.client.tagging;

import com.latchandlabel.client.targeting.ContainerTargeting;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.ChestKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Optional;

/**
 * Determines which {@link ChestKey} corresponds to the currently open container screen,
 * using crosshair targeting first, then falling back to the interaction tracker.
 */
public final class ContainerScreenContextResolver {
    private static final boolean DEBUG = Boolean.getBoolean("latchlabel.debug.container_resolve");

    private ContainerScreenContextResolver() {
    }

    public static Optional<ChestKey> resolve(Minecraft client, Screen screen) {
        if (!isSupportedScreen(screen) || client == null || client.level == null) {
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
            if (key.dimensionId().equals(client.level.dimension().location()) && screenHandlerMatches(client, screen)) {
                debug("Resolved container from recent interaction: " + key.toStringKey());
                return Optional.of(key);
            }
        }

        debug("Unable to resolve container for screen: " + screen.getClass().getSimpleName());
        return Optional.empty();
    }

    public static boolean isSupportedScreen(Screen screen) {
        return screen instanceof ContainerScreen
                || screen instanceof ShulkerBoxScreen;
    }

    private static boolean screenHandlerMatches(Minecraft client, Screen screen) {
        if (client.player == null) {
            return false;
        }

        AbstractContainerMenu activeHandler = client.player.containerMenu;
        if (screen instanceof AbstractContainerScreen<?> handledScreen) {
            return activeHandler == handledScreen.getMenu();
        }
        return false;
    }

    private static void debug(String message) {
        if (DEBUG) {
            LatchLabel.LOGGER.info("[ContainerResolve] {}", message);
        }
    }
}
