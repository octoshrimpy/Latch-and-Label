package com.latchandlabel.client.input;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.book.BookConfirmScreen;
import com.latchandlabel.client.book.BookExportImportService;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.config.KeybindSettings;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.find.FindCommand;
import com.latchandlabel.client.find.FindSettings;
import com.latchandlabel.client.tagging.TaggingController;
import com.latchandlabel.client.targeting.ContainerTargeting;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

public final class ClientInputHandler {
    // Category is a translation key string in Mojang KeyMapping
    private static final String CATEGORY = "key.categories.latchlabel.general";

    private static KeyMapping openPickerKey;
    private static KeyMapping findShortcutKey;
    private static KeyMapping moveToStorageKey;
    private static volatile boolean inspectModeActive;

    private ClientInputHandler() {
    }

    public static void register() {
        openPickerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.latchlabel.open_picker",
                InputConstants.Type.KEYSYM,
                KeybindSettings.openPickerKeyCode(),
                CATEGORY
        ));
        findShortcutKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.latchlabel.find_shortcut",
                InputConstants.Type.KEYSYM,
                KeybindSettings.findShortcutKeyCode(),
                CATEGORY
        ));
        moveToStorageKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.latchlabel.move_to_storage",
                InputConstants.Type.KEYSYM,
                KeybindSettings.moveToStorageKeyCode(),
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(ClientInputHandler::onEndTick);
    }

    public static void reloadFromSettings() {
        if (openPickerKey == null) {
            return;
        }

        openPickerKey.setBoundKey(InputConstants.Type.KEYSYM.createFromCode(KeybindSettings.openPickerKeyCode()));
        findShortcutKey.setBoundKey(InputConstants.Type.KEYSYM.createFromCode(KeybindSettings.findShortcutKeyCode()));
        moveToStorageKey.setBoundKey(InputConstants.Type.KEYSYM.createFromCode(KeybindSettings.moveToStorageKeyCode()));
        KeyMapping.resetMapping();
    }

    public static boolean isInspectModeActive() {
        return inspectModeActive;
    }

    public static boolean isShiftDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }
        return isModifierDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    public static boolean isAltDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }
        return isModifierDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private static void onEndTick(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            inspectModeActive = false;
            return;
        }

        Window window = client.getWindow();
        boolean isShiftDown = isModifierDown(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        boolean isCtrlDown = isModifierDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean isAltDown = isModifierDown(window, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT);

        boolean isSneaking = client.player != null && client.player.isSneaking();
        inspectModeActive = InspectSettings.isInspectActive(isAltDown, isSneaking);

        while (openPickerKey.wasPressed()) {
            onOpenPickerPressed(client, isShiftDown, isCtrlDown);
        }
        while (findShortcutKey.wasPressed()) {
            onFindShortcutPressed(client);
        }
        while (moveToStorageKey.wasPressed()) {
            onMoveToStoragePressed(client);
        }
    }

    private static void onOpenPickerPressed(Minecraft client, boolean isShiftDown, boolean isCtrlDown) {
        Optional<ChestKey> target = ContainerTargeting.getTargetedContainer(client);
        if (target.isEmpty()) {
            if (client.player != null && tryBookInteraction(client)) {
                return;
            }
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

    private static void onFindShortcutPressed(Minecraft client) {
        if (!FindSettings.allowFindKeybind()) {
            return;
        }
        FindCommand.runFromShortcut(client);
    }

    private static void onMoveToStoragePressed(Minecraft client) {
        ContainerTagButtonManager.triggerMoveToStorageForCurrentScreen(client);
    }

    private static boolean tryBookInteraction(Minecraft client) {
        ItemStack heldStack = client.player.getMainHandItem();
        if (heldStack.is(Items.WRITABLE_BOOK)) {
            client.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.EXPORT));
            return true;
        }
        if (heldStack.is(Items.WRITTEN_BOOK) && BookExportImportService.isLatchLabelBook(heldStack)) {
            client.setScreen(new BookConfirmScreen(BookConfirmScreen.Mode.IMPORT));
            return true;
        }
        return false;
    }

    private static boolean isModifierDown(Window window, int leftKey, int rightKey) {
        return InputConstants.isKeyDown(window.getWindow(), leftKey) || InputConstants.isKeyDown(window.getWindow(), rightKey);
    }
}
