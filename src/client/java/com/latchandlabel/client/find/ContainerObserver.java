package com.latchandlabel.client.find;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.tagging.ContainerScreenContextResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Records the contents of any container the player opens into the persistent observed index.
 * This is the only reliable way the client learns what is inside a chest, so it's the sole
 * feed for content-based {@code /find}.
 */
public final class ContainerObserver {
    private static volatile long lastContentsSyncMs = 0L;

    private ContainerObserver() {
    }

    /**
     * Epoch millis of the last time the open container's contents were synced from the server
     * ({@code initializeContents}). Callers that act on a freshly-opened menu should wait until
     * this post-dates their open request — MC 26.x rejects container clicks with a stale stateId.
     */
    public static long lastContentsSyncMs() {
        return lastContentsSyncMs;
    }

    public static void onContainerContents(AbstractContainerMenu handler) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || handler == null) {
            return;
        }
        if (client.player.containerMenu != handler) {
            return;
        }
        lastContentsSyncMs = System.currentTimeMillis();

        Screen screen = McCompat.getScreen(client);
        if (screen == null) {
            return;
        }
        Optional<ChestKey> key = ContainerScreenContextResolver.resolve(client, screen);
        if (key.isEmpty()) {
            return;
        }

        // Chest slots are every slot not backed by the player's own inventory.
        Object playerInventory = client.player.getInventory();
        Set<Item> items = new LinkedHashSet<>();
        for (var slot : handler.slots) {
            if (slot.container == playerInventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }

        LatchLabelClientState.observedIndexStore().record(key.get(), items);
    }
}
