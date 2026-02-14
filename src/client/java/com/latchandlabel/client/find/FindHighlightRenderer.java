package com.latchandlabel.client.find;

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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class FindHighlightRenderer {
    private static final int MAX_RESULTS_PER_FRAME = 96;
    private static final double OUTLINE_BASE_EXPAND = 0.0015;
    private static final double THICK_NEAR = 0.040;
    private static final double THICK_MID = 0.028;
    private static final double THICK_FAR = 0.018;
    private static final double FOCUS_THICK = 0.050;
    private static final double LOD_NEAR_DISTANCE = 12.0;
    private static final double LOD_MID_DISTANCE = 24.0;
    private static final double FRUSTUM_MARGIN = 0.06;
    private static final double PULSE_SPEED_RADIANS = 6.0;
    private static final double PULSE_MIN_SCALE = 1.0;
    private static final double PULSE_MAX_SCALE = 1.35;
    private static final float MATCH_ALPHA = 0.90f;
    private static final float FOCUS_ALPHA = 0.95f;
    private static final float EXACT_R = 0.20f;
    private static final float EXACT_G = 0.95f;
    private static final float EXACT_B = 0.35f;
    private static final float VARIANT_R = 0.20f;
    private static final float VARIANT_G = 0.70f;
    private static final float VARIANT_B = 1.00f;
    private static final float FOCUS_R = 1.00f;
    private static final float FOCUS_G = 0.85f;
    private static final float FOCUS_B = 0.20f;

    private FindHighlightRenderer() {
    }

    public static void render(WorldRenderContext context) {
        List<FindResultState.ActiveFindResult> activeResults = FindResultState.getActiveResults();
        if (activeResults.isEmpty()) {
            return;
        }

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider consumers = context.consumers();
        MinecraftClient client = MinecraftClient.getInstance();
        if (matrices == null || consumers == null || client == null || client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
            return;
        }
        World world = client.world;
        if (world == null || client.player == null) {
            return;
        }

        Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
        double nearDistanceSq = LOD_NEAR_DISTANCE * LOD_NEAR_DISTANCE;
        double midDistanceSq = LOD_MID_DISTANCE * LOD_MID_DISTANCE;
        long frameParity = world.getTime() & 1L;
        Frustum frustum = context.worldRenderer().getCapturedFrustum();
        VertexConsumer lineConsumer = consumers.getBuffer(RenderLayer.getLines());
        VertexConsumer fillConsumer = consumers.getBuffer(RenderLayer.getDebugFilledBox());

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        double pulse = 0.5 + (0.5 * Math.sin((System.currentTimeMillis() / 1000.0) * PULSE_SPEED_RADIANS));
        double pulseScale = PULSE_MIN_SCALE + ((PULSE_MAX_SCALE - PULSE_MIN_SCALE) * pulse);
        Set<ChestKey> renderedKeys = new HashSet<>();
        List<RenderCandidate> candidates = new ArrayList<>();
        for (FindResultState.ActiveFindResult active : activeResults) {
            FindScanService.FindMatch match = active.match();
            ChestKey key = StorageKeyResolver.normalizeForWorld(world, match.chestKey());
            if (key == null || !renderedKeys.add(key)) {
                continue;
            }

            Optional<Box> renderBox = StorageRenderShapeResolver.resolveBox(world, key);
            if (renderBox.isEmpty()) {
                continue;
            }
            Box box = renderBox.get();
            if (frustum != null && !frustum.isVisible(box.expand(FRUSTUM_MARGIN))) {
                continue;
            }

            Vec3d center = box.getCenter();
            double distanceSq = client.player.squaredDistanceTo(center.x, center.y, center.z);
            boolean focused = FindResultState.isFocused(match.chestKey()) || FindResultState.isFocused(key);
            if (!focused && distanceSq > midDistanceSq && (((key.hashCode() ^ (int) frameParity) & 1) != 0)) {
                continue;
            }

            float r;
            float g;
            float b;
            if (match.matchType() == FindScanService.MatchType.EXACT) {
                r = EXACT_R;
                g = EXACT_G;
                b = EXACT_B;
            } else {
                r = VARIANT_R;
                g = VARIANT_G;
                b = VARIANT_B;
            }

            candidates.add(new RenderCandidate(key, box, distanceSq, focused, r, g, b));
        }

        candidates.sort(Comparator.comparingDouble(RenderCandidate::distanceSq));
        int rendered = 0;
        for (RenderCandidate candidate : candidates) {
            if (rendered >= MAX_RESULTS_PER_FRAME) {
                break;
            }

            Box box = candidate.box();
            float r = candidate.r();
            float g = candidate.g();
            float b = candidate.b();
            double distanceSq = candidate.distanceSq();

            double thickness = distanceSq <= nearDistanceSq
                    ? THICK_NEAR * pulseScale
                    : (distanceSq <= midDistanceSq ? THICK_MID : THICK_FAR);
            ThickOutlineRenderer.drawThickOutline(matrices, fillConsumer, box.expand(OUTLINE_BASE_EXPAND), (float) thickness, r, g, b, MATCH_ALPHA);
            if (distanceSq > nearDistanceSq) {
                VertexRendering.drawBox(matrices.peek(), lineConsumer, box.expand(OUTLINE_BASE_EXPAND), r, g, b, MATCH_ALPHA);
            }

            if (candidate.focused()) {
                ThickOutlineRenderer.drawThickOutline(
                        matrices,
                        fillConsumer,
                        box.expand(OUTLINE_BASE_EXPAND + 0.004),
                        (float) (FOCUS_THICK * pulseScale),
                        FOCUS_R,
                        FOCUS_G,
                        FOCUS_B,
                        FOCUS_ALPHA
                );
            }
            rendered++;
        }

        matrices.pop();
    }

    private record RenderCandidate(
            ChestKey key,
            Box box,
            double distanceSq,
            boolean focused,
            float r,
            float g,
            float b
    ) {
    }
}
