package com.latchandlabel.client.find;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.render.CornerBracketRenderer;
import com.latchandlabel.client.render.RenderBox;
import com.latchandlabel.client.render.RenderLayerCompat;
import com.latchandlabel.client.render.ThickOutlineRenderer;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.StorageRenderShapeResolver;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Renders {@code /find} results in the same visual language as the inspect overlay: uniform
 * line width, state read from shape. Brackets/outline use the chest's tag (category) color so
 * they match the HUD; {@code KNOWN} chests (item seen inside) add a pulsing gold "found" overlay
 * + floating marker; {@code LIKELY} chests (tagged with the item's category, contents unknown)
 * get a plain category-colored outline. Untagged found chests fall back to gold.
 */
public final class FindHighlightRenderer {
    private static final int MAX_RESULTS_PER_FRAME = 96;
    private static final double OUTLINE_EXPAND = 0.0015;
    private static final float OUTLINE_THICKNESS = 0.020f;
    private static final float BRACKET_THICKNESS = 0.024f;
    private static final float BRACKET_LEG_LENGTH = 0.24f;
    private static final float KNOWN_ALPHA = 0.95f;
    private static final float STALE_ALPHA = 0.55f;
    private static final float LIKELY_ALPHA = 0.50f;
    private static final float FOUND_PULSE_MAX_ALPHA = 0.55f;
    private static final double FRUSTUM_MARGIN = 0.06;
    private static final double PULSE_SPEED_RADIANS = 6.0;
    private static final double MARKER_BOB_AMPLITUDE = 0.06;
    // Nav beacon: a tall translucent pillar above the current target, visible from far.
    private static final double BEAM_HALF = 0.06;
    private static final double BEAM_HEIGHT = 16.0;
    private static final float BEAM_ALPHA = 0.22f;
    // Gold accent: "find" hue, distinct from category-colored inspect outlines.
    private static final float FIND_R = 1.00f;
    private static final float FIND_G = 0.82f;
    private static final float FIND_B = 0.20f;

    private FindHighlightRenderer() {
    }

    public static void render(LevelRenderContext context) {
        List<FindScanService.FindMatch> activeResults = FindResultState.getActiveResults();
        if (activeResults.isEmpty()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gameRenderer == null || McCompat.mainCamera(client) == null) {
            return;
        }
        Level world = client.level;
        if (world == null || client.player == null) {
            return;
        }

        Vec3 cameraPos = McCompat.mainCamera(client).position();
        Frustum frustum = context.levelState().cameraRenderState.cullFrustum;
        double pulse = 0.5 + (0.5 * Math.sin((System.currentTimeMillis() / 1000.0) * PULSE_SPEED_RADIANS));

        Set<ChestKey> renderedKeys = new HashSet<>();
        List<RenderCandidate> candidates = new ArrayList<>();
        for (FindScanService.FindMatch match : activeResults) {
            ChestKey key = StorageKeyResolver.normalizeForWorld(world, match.chestKey());
            if (key == null || !renderedKeys.add(key)) {
                continue;
            }

            Optional<AABB> renderBox = StorageRenderShapeResolver.resolveBox(world, key);
            if (renderBox.isEmpty()) {
                continue;
            }
            AABB box = renderBox.get();
            if (frustum != null && !frustum.isVisible(box.inflate(FRUSTUM_MARGIN))) {
                continue;
            }

            Vec3 center = box.getCenter();
            double distanceSq = client.player.distanceToSqr(center.x, center.y, center.z);
            boolean known = match.matchType() == FindScanService.MatchType.KNOWN;
            boolean stale = match.matchType() == FindScanService.MatchType.KNOWN_STALE;
            float[] rgb = categoryRgb(match.chestKey(), key);
            candidates.add(new RenderCandidate(box.inflate(OUTLINE_EXPAND), distanceSq, known, stale, rgb[0], rgb[1], rgb[2]));
        }

        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort(Comparator.comparingDouble(RenderCandidate::distanceSq));

        final double camX = cameraPos.x;
        final double camY = cameraPos.y;
        final double camZ = cameraPos.z;
        final double finalPulse = pulse;
        final List<RenderCandidate> finalCandidates = candidates;

        AABB targetBox = null;
        Optional<FindScanService.FindMatch> current = FindResultState.currentTarget();
        if (current.isPresent()) {
            ChestKey targetKey = StorageKeyResolver.normalizeForWorld(world, current.get().chestKey());
            if (targetKey != null) {
                targetBox = StorageRenderShapeResolver.resolveBox(world, targetKey).orElse(null);
            }
        }
        final AABB finalTargetBox = targetBox;

        PoseStack matrices = context.poseStack();
        matrices.pushPose();
        matrices.translate(-camX, -camY, -camZ);

        context.submitNodeCollector().submitCustomGeometry(matrices, RenderLayerCompat.debugFilledBox(), (pose, consumer) -> {
            double bob = (finalPulse - 0.5) * 2.0 * MARKER_BOB_AMPLITUDE;
            int rendered = 0;
            for (RenderCandidate candidate : finalCandidates) {
                if (rendered >= MAX_RESULTS_PER_FRAME) {
                    break;
                }
                AABB box = candidate.box();
                float cr = candidate.r();
                float cg = candidate.g();
                float cb = candidate.b();
                if (candidate.known()) {
                    // Tag color brackets (static) — match the HUD category color.
                    CornerBracketRenderer.drawCornerBrackets(pose, consumer, box, BRACKET_THICKNESS, BRACKET_LEG_LENGTH, cr, cg, cb, KNOWN_ALPHA);
                    // Pulsing gold "found" overlay: brackets + floating marker.
                    float foundAlpha = FOUND_PULSE_MAX_ALPHA * (float) finalPulse;
                    CornerBracketRenderer.drawCornerBrackets(pose, consumer, box.inflate(0.004), BRACKET_THICKNESS, BRACKET_LEG_LENGTH, FIND_R, FIND_G, FIND_B, foundAlpha);
                    CornerBracketRenderer.drawMarker(pose, consumer, box, bob, FIND_R, FIND_G, FIND_B, 0.55f + 0.45f * (float) finalPulse);
                } else if (candidate.stale()) {
                    // Known location, contents unverified since another player opened it: faint category
                    // brackets + faint pulsing gold, but no floating marker — reads as "probably here".
                    CornerBracketRenderer.drawCornerBrackets(pose, consumer, box, BRACKET_THICKNESS, BRACKET_LEG_LENGTH, cr, cg, cb, STALE_ALPHA);
                    float staleGold = FOUND_PULSE_MAX_ALPHA * 0.5f * (float) finalPulse;
                    CornerBracketRenderer.drawCornerBrackets(pose, consumer, box.inflate(0.004), BRACKET_THICKNESS, BRACKET_LEG_LENGTH, FIND_R, FIND_G, FIND_B, staleGold);
                } else {
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box, OUTLINE_THICKNESS, cr, cg, cb, LIKELY_ALPHA);
                }
                rendered++;
            }

            if (finalTargetBox != null) {
                double cx = (finalTargetBox.minX + finalTargetBox.maxX) * 0.5;
                double cz = (finalTargetBox.minZ + finalTargetBox.maxZ) * 0.5;
                double top = finalTargetBox.maxY;
                RenderBox.drawFilledBox(pose, consumer,
                        cx - BEAM_HALF, top, cz - BEAM_HALF,
                        cx + BEAM_HALF, top + BEAM_HEIGHT, cz + BEAM_HALF,
                        FIND_R, FIND_G, FIND_B, BEAM_ALPHA);
            }
        });

        matrices.popPose();
    }

    private record RenderCandidate(AABB box, double distanceSq, boolean known, boolean stale, float r, float g, float b) {
    }

    /** The chest's tag category color as RGB floats, or gold if untagged/unknown. */
    private static float[] categoryRgb(ChestKey originalKey, ChestKey normalizedKey) {
        String categoryId = LatchLabelClientState.tagStore().getTag(originalKey)
                .or(() -> LatchLabelClientState.tagStore().getTag(normalizedKey))
                .orElse(null);
        if (categoryId != null) {
            Optional<Category> category = LatchLabelClientState.categoryStore().getById(categoryId);
            if (category.isPresent()) {
                int color = category.get().color();
                return new float[]{((color >> 16) & 0xFF) / 255.0f, ((color >> 8) & 0xFF) / 255.0f, (color & 0xFF) / 255.0f};
            }
        }
        return new float[]{FIND_R, FIND_G, FIND_B};
    }
}
