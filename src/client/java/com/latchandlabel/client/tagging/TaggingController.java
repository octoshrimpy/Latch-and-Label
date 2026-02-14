package com.latchandlabel.client.tagging;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.ui.CategoryPickerScreen;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class TaggingController {
    private TaggingController() {
    }

    public static void openPicker(MinecraftClient client, ChestKey chestKey) {
        if (client == null) {
            return;
        }
        ChestKey resolvedKey = resolveKey(client, chestKey);
        Screen parentScreen = client.currentScreen;

        client.setScreen(new CategoryPickerScreen(
                parentScreen,
                resolvedKey,
                categoryId -> {
                    LatchLabelClientState.tagStore().setTag(resolvedKey, categoryId);
                    showOverlay(client, Text.translatable("latchlabel.tagged", categoryId));
                },
                () -> {
                    LatchLabelClientState.tagStore().clearTag(resolvedKey);
                    showOverlay(client, Text.translatable("latchlabel.tag_cleared"));
                },
                () -> {
                    // no-op
                }
        ));
    }

    public static void clearTag(MinecraftClient client, ChestKey chestKey) {
        ChestKey resolvedKey = resolveKey(client, chestKey);
        LatchLabelClientState.tagStore().clearTag(resolvedKey);
        showOverlay(client, Text.translatable("latchlabel.tag_cleared"));
    }

    private static ChestKey resolveKey(MinecraftClient client, ChestKey chestKey) {
        if (client == null || client.world == null || chestKey == null) {
            return chestKey;
        }
        return StorageKeyResolver.normalizeForWorld(client.world, chestKey);
    }

    private static void showOverlay(MinecraftClient client, Text message) {
        if (client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(message, false);
        }
    }
}
