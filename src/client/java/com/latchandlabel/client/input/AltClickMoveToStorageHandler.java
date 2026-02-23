package com.latchandlabel.client.input;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.tagging.StorageTagResolver;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.TrackableStorage;
import com.latchandlabel.client.ui.ContainerTagButtonManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

public final class AltClickMoveToStorageHandler {
    private static final int AUTO_MOVE_TIMEOUT_TICKS = 20;

    private static PendingAutoMove pendingAutoMove;

    private AltClickMoveToStorageHandler() {
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClient() || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.interactionManager == null) {
                return ActionResult.PASS;
            }
            if (!isAltDown(client.getWindow())) {
                return ActionResult.PASS;
            }
            if (pendingAutoMove != null) {
                return ActionResult.FAIL;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!TrackableStorage.isTrackableStorage(blockEntity)) {
                return ActionResult.PASS;
            }

            Optional<ChestKey> resolved = StorageKeyResolver.resolveForWorld(world, pos);
            if (resolved.isEmpty()) {
                return ActionResult.PASS;
            }
            if (StorageTagResolver.resolveCategoryId(client, resolved.get()).isEmpty()) {
                return ActionResult.PASS;
            }

            BlockHitResult hitResult = new BlockHitResult(Vec3d.ofCenter(pos), direction, pos, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
            pendingAutoMove = new PendingAutoMove(resolved.get(), AUTO_MOVE_TIMEOUT_TICKS);
            return ActionResult.FAIL;
        });

        ClientTickEvents.END_CLIENT_TICK.register(AltClickMoveToStorageHandler::onEndTick);
    }

    private static void onEndTick(MinecraftClient client) {
        if (pendingAutoMove == null) {
            return;
        }
        if (client == null || client.player == null) {
            pendingAutoMove = null;
            return;
        }

        PendingAutoMove current = pendingAutoMove;
        if (current.remainingTicks() <= 0) {
            pendingAutoMove = null;
            return;
        }

        if (client.currentScreen instanceof HandledScreen<?>) {
            boolean moved = ContainerTagButtonManager.triggerMoveToStorageForCurrentScreen(client, current.target());
            if (moved) {
                client.player.closeHandledScreen();
                pendingAutoMove = null;
                return;
            }
        }

        pendingAutoMove = current.withRemainingTicks(current.remainingTicks() - 1);
    }

    private static boolean isAltDown(Window window) {
        return window != null && (
                InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                        || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_ALT)
        );
    }

    private record PendingAutoMove(ChestKey target, int remainingTicks) {
        private PendingAutoMove withRemainingTicks(int ticks) {
            return new PendingAutoMove(target, ticks);
        }
    }
}
