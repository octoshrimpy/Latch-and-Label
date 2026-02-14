package com.latchandlabel.client.input;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.config.KeybindSettings;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.tagging.TaggingController;
import com.latchandlabel.client.targeting.ContainerTargeting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

public final class ClientInputHandler {
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("latchlabel", "general"));

    private static KeyBinding openPickerKey;
    private static boolean inspectModeActive;

    private ClientInputHandler() {
    }

    public static void register() {
        openPickerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.latchlabel.open_picker",
                InputUtil.Type.KEYSYM,
                KeybindSettings.openPickerKeyCode(),
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(ClientInputHandler::onEndTick);
    }

    public static void reloadFromSettings() {
        if (openPickerKey == null) {
            return;
        }

        openPickerKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(KeybindSettings.openPickerKeyCode()));
        KeyBinding.updateKeysByCode();
    }

    public static boolean isInspectModeActive() {
        return inspectModeActive;
    }

    private static void onEndTick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            inspectModeActive = false;
            return;
        }

        Window window = client.getWindow();
        boolean isShiftDown = isModifierDown(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean isCtrlDown = isModifierDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean isAltDown = isModifierDown(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);

        inspectModeActive = isAltDown || (client.player != null && client.player.isSneaking());

        while (openPickerKey.wasPressed()) {
            onOpenPickerPressed(client, isShiftDown, isCtrlDown);
        }
    }

    private static void onOpenPickerPressed(MinecraftClient client, boolean isShiftDown, boolean isCtrlDown) {
        Optional<ChestKey> target = ContainerTargeting.getTargetedContainer(client);
        if (target.isEmpty()) {
            return;
        }

        if (isCtrlDown) {
            LatchLabelClientState.tagStore().clearTag(target.get());
            LatchLabel.LOGGER.info("Cleared tag for {}", target.get().toStringKey());
            return;
        }

        if (isShiftDown) {
            quickApplyLastUsed(target.get(), LatchLabelClientState.tagStore());
            return;
        }

        TaggingController.openPicker(client, target.get());
        LatchLabel.LOGGER.info("Opened picker for {}", target.get().toStringKey());
    }

    private static void quickApplyLastUsed(ChestKey chestKey, TagStore tagStore) {
        tagStore.getLastUsedCategoryId().ifPresent(lastUsed -> {
            tagStore.setTag(chestKey, lastUsed);
            LatchLabel.LOGGER.info("Applied last-used category '{}' to {}", lastUsed, chestKey.toStringKey());
        });
    }

    private static boolean isModifierDown(Window window, int leftKey, int rightKey) {
        return InputUtil.isKeyPressed(window, leftKey) || InputUtil.isKeyPressed(window, rightKey);
    }
}
