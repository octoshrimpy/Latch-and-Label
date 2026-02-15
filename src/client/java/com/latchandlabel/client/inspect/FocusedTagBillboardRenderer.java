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
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

public final class FocusedTagBillboardRenderer {
    private static final double MARKER_HALF = 0.09;

    private FocusedTagBillboardRenderer() {
    }

    public static void registerHud() {
        HudRenderCallback.EVENT.register(FocusedTagBillboardRenderer::renderHud);
    }

    public static void renderWorld(WorldRenderContext context) {
        if (!ClientInputHandler.isInspectModeActive()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        FocusedCategory focused = resolveFocusedCategory(client);
        if (focused == null) {
            return;
        }

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return;
        }

        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
        VertexConsumer lineConsumer = consumers.getBuffer(RenderLayer.getLines());

        Vec3d center = focused.center;
        Box outer = new Box(
                center.x - MARKER_HALF,
                center.y - MARKER_HALF,
                center.z - MARKER_HALF,
                center.x + MARKER_HALF,
                center.y + MARKER_HALF,
                center.z + MARKER_HALF
        );
        Box inner = outer.shrink(0.02, 0.02, 0.02);

        int rgb = focused.category.color();
        float r = ((rgb >> 16) & 0xFF) / 255.0f;
        float g = ((rgb >> 8) & 0xFF) / 255.0f;
        float b = (rgb & 0xFF) / 255.0f;

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        VertexRendering.drawBox(matrices.peek(), lineConsumer, outer, r, g, b, 0.95f);
        VertexRendering.drawBox(matrices.peek(), lineConsumer, inner, 0.10f, 0.10f, 0.10f, 0.85f);
        matrices.pop();
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
