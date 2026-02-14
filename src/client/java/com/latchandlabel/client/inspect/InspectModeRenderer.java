package com.latchandlabel.client.inspect;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.render.ThickOutlineRenderer;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.StorageRenderShapeResolver;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class InspectModeRenderer {
    private static final int MAX_CONTAINERS_PER_FRAME = 72;
    private static final double BASE_OUTLINE_EXPAND = 0.0015;
    private static final float BASE_OUTLINE_ALPHA = 0.52f;
    private static final float MATCH_OUTLINE_ALPHA = 0.90f;
    private static final double THICK_NEAR = 0.040;
    private static final double THICK_MID = 0.028;
    private static final double THICK_FAR = 0.018;
    private static final double THIN_NEAR = 0.020;
    private static final double LOD_NEAR_DISTANCE = 12.0;
    private static final double LOD_MID_DISTANCE = 24.0;
    private static final double FRUSTUM_MARGIN = 0.06;
    private static final double MATCH_PULSE_SPEED_RADIANS = 6.0;
    private static final double MATCH_PULSE_MIN_SCALE = 1.0;
    private static final double MATCH_PULSE_MAX_SCALE = 1.35;

    private InspectModeRenderer() {
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (!ClientInputHandler.isInspectModeActive()) {
            return;
        }

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        if (matrices == null || consumers == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return;
        }

        World world = client.world;
        Identifier dimensionId = world.getRegistryKey().getValue();
        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
        double maxDistanceSq = Math.pow(InspectSettings.inspectRange(), 2);
        double nearDistanceSq = LOD_NEAR_DISTANCE * LOD_NEAR_DISTANCE;
        double midDistanceSq = LOD_MID_DISTANCE * LOD_MID_DISTANCE;
        long frameParity = world.getTime() & 1L;
        Frustum frustum = context.worldRenderer().getCapturedFrustum();

        VertexConsumer lineConsumer = consumers.getBuffer(RenderLayer.getLines());
        VertexConsumer fillConsumer = consumers.getBuffer(RenderLayer.getDebugFilledBox());
        Map<ChestKey, String> tags = LatchLabelClientState.tagStore().snapshotTags();
        Optional<String> heldItemCategoryId = LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(client.player.getMainHandStack());
        double pulse = 0.5 + (0.5 * Math.sin((System.currentTimeMillis() / 1000.0) * MATCH_PULSE_SPEED_RADIANS));
        double pulseScale = MATCH_PULSE_MIN_SCALE + ((MATCH_PULSE_MAX_SCALE - MATCH_PULSE_MIN_SCALE) * pulse);

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        List<InspectCandidate> candidates = new ArrayList<>();
        Set<ChestKey> renderedKeys = new HashSet<>();
        for (Map.Entry<ChestKey, String> entry : tags.entrySet()) {
            ChestKey key = StorageKeyResolver.normalizeForWorld(world, entry.getKey());
            if (!key.dimensionId().equals(dimensionId)) {
                continue;
            }
            if (!renderedKeys.add(key)) {
                continue;
            }

            Optional<Box> renderBox = StorageRenderShapeResolver.resolveBox(world, key);
            if (renderBox.isEmpty()) {
                continue;
            }
            Box box = renderBox.get();
            Vec3d center = box.getCenter();
            double distanceSq = client.player.squaredDistanceTo(center.x, center.y, center.z);
            if (!isWithinRange(distanceSq, maxDistanceSq)) {
                continue;
            }
            if (frustum != null && !frustum.isVisible(box.expand(FRUSTUM_MARGIN))) {
                continue;
            }

            Optional<Category> category = LatchLabelClientState.categoryStore().getById(entry.getValue());
            if (category.isEmpty()) {
                continue;
            }

            boolean matchesHeldCategory = heldItemCategoryId
                    .filter(entry.getValue()::equals)
                    .isPresent();
            if (!matchesHeldCategory && distanceSq > midDistanceSq && (((key.hashCode() ^ (int) frameParity) & 1) != 0)) {
                continue;
            }

            int rgb = category.get().color();
            float r = ((rgb >> 16) & 0xFF) / 255.0f;
            float g = ((rgb >> 8) & 0xFF) / 255.0f;
            float b = (rgb & 0xFF) / 255.0f;

            candidates.add(new InspectCandidate(box, distanceSq, matchesHeldCategory, r, g, b));
        }

        candidates.sort(Comparator.comparingDouble(InspectCandidate::distanceSq));
        int rendered = 0;
        for (InspectCandidate candidate : candidates) {
            if (rendered >= MAX_CONTAINERS_PER_FRAME) {
                break;
            }

            Box box = candidate.box();
            float r = candidate.r();
            float g = candidate.g();
            float b = candidate.b();
            double distanceSq = candidate.distanceSq();

            if (candidate.matchesHeldCategory()) {
                double thickness = distanceSq <= nearDistanceSq
                        ? THICK_NEAR * pulseScale
                        : (distanceSq <= midDistanceSq ? THICK_MID : THICK_FAR);
                ThickOutlineRenderer.drawThickOutline(matrices, fillConsumer, box.expand(BASE_OUTLINE_EXPAND), (float) thickness, r, g, b, MATCH_OUTLINE_ALPHA);
            } else if (distanceSq <= nearDistanceSq) {
                ThickOutlineRenderer.drawThickOutline(matrices, fillConsumer, box.expand(BASE_OUTLINE_EXPAND), (float) THIN_NEAR, r, g, b, BASE_OUTLINE_ALPHA);
            } else {
                VertexRendering.drawBox(matrices.peek(), lineConsumer, box.expand(BASE_OUTLINE_EXPAND), r, g, b, BASE_OUTLINE_ALPHA);
            }
            rendered++;
        }

        matrices.pop();
    }

    private static boolean isWithinRange(double distanceSq, double maxDistanceSq) {
        return distanceSq <= maxDistanceSq;
    }

    private record InspectCandidate(
            Box box,
            double distanceSq,
            boolean matchesHeldCategory,
            float r,
            float g,
            float b
    ) {
    }
}
