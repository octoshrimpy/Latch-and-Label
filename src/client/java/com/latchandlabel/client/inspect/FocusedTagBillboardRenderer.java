package com.latchandlabel.client.inspect;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.tagging.StorageTagResolver;
import com.latchandlabel.client.targeting.ContainerTargeting;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public final class FocusedTagBillboardRenderer {
    private FocusedTagBillboardRenderer() {
    }

    public static void registerHud() {
        HudRenderCallback.EVENT.register(FocusedTagBillboardRenderer::renderHud);
    }

    public static void renderWorld(WorldRenderContext context) {
        // Intentionally no-op: removed focused face marker cube.
    }

    private static void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (!ClientInputHandler.isInspectModeActive()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        FocusedCategory focused = resolveFocusedCategory(client);
        if (focused == null) {
            return;
        }

        Text text = Text.literal(focused.category.name());
        int x = (context.getScaledWindowWidth() / 2) - (client.textRenderer.getWidth(text) / 2);
        int y = (context.getScaledWindowHeight() / 2) + 20;
        context.drawTextWithShadow(client.textRenderer, text, x, y, 0xFFFFFFFF);
    }

    private static FocusedCategory resolveFocusedCategory(MinecraftClient client) {
        if (client == null || client.world == null || client.crosshairTarget == null) {
            return null;
        }

        Optional<ChestKey> targeted = ContainerTargeting.getTargetedContainer(client);
        if (targeted.isEmpty()) {
            return null;
        }

        Optional<String> categoryId = StorageTagResolver.resolveCategoryId(client, targeted.get());
        if (categoryId.isEmpty()) {
            return null;
        }

        Optional<Category> category = LatchLabelClientState.categoryStore().getById(categoryId.get());
        if (category.isEmpty()) {
            return null;
        }

        if (!(client.crosshairTarget instanceof BlockHitResult hitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        Vec3d center = faceCenter(targeted.get(), hitResult.getSide());
        return new FocusedCategory(category.get(), center);
    }

    private static Vec3d faceCenter(ChestKey key, Direction face) {
        double x = key.pos().getX() + 0.5 + (face.getOffsetX() * 0.501);
        double y = key.pos().getY() + 0.5 + (face.getOffsetY() * 0.501);
        double z = key.pos().getZ() + 0.5 + (face.getOffsetZ() * 0.501);
        return new Vec3d(x, y, z);
    }

    private record FocusedCategory(Category category, Vec3d center) {
    }
}
