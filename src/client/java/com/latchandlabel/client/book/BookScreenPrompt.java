package com.latchandlabel.client.book;

import com.latchandlabel.client.McCompat;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * When a vanilla book screen opens for a book that already holds Latch &amp; Label data, shows a
 * hint that holding Alt while opening opens the L&amp;L book UI instead — and does exactly that when
 * Alt is held. Uses screen class-name matching so it doesn't depend on a specific mapped book
 * screen type across Minecraft versions.
 */
public final class BookScreenPrompt {
    private static final long HINT_THROTTLE_MS = 30_000L;
    private static long lastHintMs = 0L;

    private BookScreenPrompt() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof BookConfirmScreen) {
                return;
            }
            if (!screen.getClass().getSimpleName().contains("Book")) {
                return;
            }
            if (client.player == null
                    || !BookExportImportService.isLatchLabelBook(client.player.getMainHandItem())) {
                return;
            }

            if (isAltDown(client.getWindow())) {
                McCompat.setScreen(client, new BookConfirmScreen(BookConfirmScreen.Mode.EXPORT_PICKER));
                return;
            }

            long now = System.currentTimeMillis();
            if (now - lastHintMs > HINT_THROTTLE_MS) {
                client.player.sendSystemMessage(Component.translatable("latchlabel.book.alt_open_hint"));
                lastHintMs = now;
            }
        });
    }

    private static boolean isAltDown(Window window) {
        return window != null && (
                InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT));
    }
}
