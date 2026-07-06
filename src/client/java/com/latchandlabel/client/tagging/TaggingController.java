package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.ui.CategoryPickerScreen;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Orchestrates tagging operations: opens the category picker, applies/clears tags,
 * and applies the last-used category via keyboard shortcuts.
 */
public final class TaggingController {
    private TaggingController() {
    }

    public static void openPicker(Minecraft client, ChestKey chestKey) {
        if (client == null) {
            return;
        }
        ChestKey resolvedKey = resolveKey(client, chestKey);
        Screen parentScreen = McCompat.getScreen(client);

        McCompat.setScreen(client, new CategoryPickerScreen(
                parentScreen,
                resolvedKey,
                categoryId -> {
                    applyTag(client, resolvedKey, categoryId);
                },
                () -> {
                    LatchLabelClientState.tagStore().clearTag(resolvedKey);
                    showOverlay(client, Component.translatable("latchlabel.tag_cleared"));
                },
                () -> {
                    // no-op
                }
        ));
    }

    public static void applyTag(Minecraft client, ChestKey chestKey, String categoryId) {
        ChestKey resolvedKey = resolveKey(client, chestKey);
        LatchLabelClientState.tagStore().setTag(resolvedKey, categoryId);
        showOverlay(client, Component.translatable("latchlabel.tagged", categoryId));
    }

    public static void clearTag(Minecraft client, ChestKey chestKey) {
        ChestKey resolvedKey = resolveKey(client, chestKey);
        LatchLabelClientState.tagStore().clearTag(resolvedKey);
        showOverlay(client, Component.translatable("latchlabel.tag_cleared"));
    }

    private static ChestKey resolveKey(Minecraft client, ChestKey chestKey) {
        if (client == null || client.level == null || chestKey == null) {
            return chestKey;
        }
        return StorageKeyResolver.normalizeForWorld(client.level, chestKey);
    }

    private static void showOverlay(Minecraft client, Component message) {
        if (client.player != null) {
            client.player.sendOverlayMessage(message);
        }
    }
}
