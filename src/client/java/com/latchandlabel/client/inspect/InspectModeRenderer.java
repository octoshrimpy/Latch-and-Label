package com.latchandlabel.client.inspect;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.config.TransferSettings;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.render.RenderLayerCompat;
import com.latchandlabel.client.render.ThickOutlineRenderer;
import com.latchandlabel.client.targeting.StorageKeyResolver;
import com.latchandlabel.client.targeting.StorageRenderShapeResolver;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

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
    private static final double ALT_TARGET_NEAR = 0.030;
    private static final double ALT_TARGET_MID = 0.023;
    private static final double ALT_TARGET_FAR = 0.018;
    private static final double LOD_NEAR_DISTANCE = 12.0;
    private static final double LOD_MID_DISTANCE = 24.0;
    private static final double FRUSTUM_MARGIN = 0.06;
    private static final double MATCH_PULSE_SPEED_RADIANS = 6.0;
    private static final double MATCH_PULSE_MIN_SCALE = 1.0;
    private static final double MATCH_PULSE_MAX_SCALE = 1.35;
    private static final float FULL_OUTLINE_ALPHA = 0.15f;
    private static final float ALT_TARGET_ALPHA = 0.78f;

    private InspectModeRenderer() {
    }

    public static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        if (!ClientInputHandler.isInspectModeActive()) {
            return;
        }

        Level world = client.level;
        Identifier dimensionId = world.dimension().identifier();
        Vec3 cameraPos = client.gameRenderer.mainCamera().position();
        double maxDistanceSq = Math.pow(InspectSettings.inspectRange(), 2);
        double nearDistanceSq = LOD_NEAR_DISTANCE * LOD_NEAR_DISTANCE;
        double midDistanceSq = LOD_MID_DISTANCE * LOD_MID_DISTANCE;
        long frameParity = world.getGameTime() & 1L;
        Frustum frustum = context.levelState().cameraRenderState.cullFrustum;

        Map<ChestKey, String> tags = LatchLabelClientState.tagStore().snapshotTags();
        Optional<String> heldItemCategoryId = LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(client.player.getMainHandItem());
        boolean isAltDown = ClientInputHandler.isAltDown();
        Set<String> inventoryCategoryIds = isAltDown ? collectMoveCategoryIds(client) : Set.of();
        double pulse = 0.5 + (0.5 * Math.sin((System.currentTimeMillis() / 1000.0) * MATCH_PULSE_SPEED_RADIANS));
        double pulseScale = MATCH_PULSE_MIN_SCALE + ((MATCH_PULSE_MAX_SCALE - MATCH_PULSE_MIN_SCALE) * pulse);

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

            Optional<AABB> renderBox = StorageRenderShapeResolver.resolveBox(world, key);
            if (renderBox.isEmpty()) {
                continue;
            }
            AABB box = renderBox.get();
            Vec3 center = box.getCenter();
            double distanceSq = client.player.distanceToSqr(center.x, center.y, center.z);
            if (!isWithinRange(distanceSq, maxDistanceSq)) {
                continue;
            }
            if (frustum != null && !frustum.isVisible(box.inflate(FRUSTUM_MARGIN))) {
                continue;
            }

            Optional<Category> category = LatchLabelClientState.categoryStore().getById(entry.getValue());
            if (category.isEmpty()) {
                continue;
            }

            boolean matchesHeldCategory = heldItemCategoryId
                    .filter(entry.getValue()::equals)
                    .isPresent();
            boolean isFull = StorageFullness.isStorageFull(world, key.pos());
            boolean matchesInventoryCategory = !isFull && inventoryCategoryIds.contains(entry.getValue());
            if (!matchesHeldCategory && !matchesInventoryCategory && distanceSq > midDistanceSq && (((key.hashCode() ^ (int) frameParity) & 1) != 0)) {
                continue;
            }

            int rgb = category.get().color();
            float r = ((rgb >> 16) & 0xFF) / 255.0f;
            float g = ((rgb >> 8) & 0xFF) / 255.0f;
            float b = (rgb & 0xFF) / 255.0f;

            candidates.add(new InspectCandidate(box, distanceSq, matchesHeldCategory, matchesInventoryCategory, isFull, r, g, b));
        }

        if (candidates.isEmpty()) {
            return;
        }

        candidates.sort(Comparator.comparingDouble(InspectCandidate::distanceSq));

        final double camX = cameraPos.x;
        final double camY = cameraPos.y;
        final double camZ = cameraPos.z;
        final double finalPulseScale = pulseScale;
        final List<InspectCandidate> finalCandidates = candidates;
        final double finalNearSq = nearDistanceSq;
        final double finalMidSq = midDistanceSq;

        PoseStack matrices = context.poseStack();
        matrices.pushPose();
        matrices.translate(-camX, -camY, -camZ);

        context.submitNodeCollector().submitCustomGeometry(matrices, RenderLayerCompat.debugFilledBox(), (pose, consumer) -> {
            int rendered = 0;
            for (InspectCandidate candidate : finalCandidates) {
                if (rendered >= MAX_CONTAINERS_PER_FRAME) {
                    break;
                }

                AABB box = candidate.box();
                float r = candidate.r();
                float g = candidate.g();
                float b = candidate.b();
                double distanceSq = candidate.distanceSq();

                float alpha;
                if (candidate.isFull()) {
                    alpha = FULL_OUTLINE_ALPHA;
                } else if (candidate.matchesHeldCategory()) {
                    alpha = MATCH_OUTLINE_ALPHA;
                } else if (candidate.matchesInventoryCategory()) {
                    alpha = ALT_TARGET_ALPHA;
                } else {
                    alpha = BASE_OUTLINE_ALPHA;
                }

                if (candidate.matchesHeldCategory() && !candidate.isFull()) {
                    double thickness = distanceSq <= finalNearSq
                            ? THICK_NEAR * finalPulseScale
                            : (distanceSq <= finalMidSq ? THICK_MID : THICK_FAR);
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box.inflate(BASE_OUTLINE_EXPAND), (float) thickness, r, g, b, alpha);
                } else if (candidate.matchesInventoryCategory()) {
                    double thickness = distanceSq <= finalNearSq
                            ? ALT_TARGET_NEAR
                            : (distanceSq <= finalMidSq ? ALT_TARGET_MID : ALT_TARGET_FAR);
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box.inflate(BASE_OUTLINE_EXPAND), (float) thickness, r, g, b, alpha);
                } else if (distanceSq <= finalNearSq) {
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box.inflate(BASE_OUTLINE_EXPAND), (float) THIN_NEAR, r, g, b, alpha);
                } else {
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box.inflate(BASE_OUTLINE_EXPAND), (float) THICK_FAR, r, g, b, alpha);
                }
                rendered++;
            }
        });

        matrices.popPose();
    }

    private static boolean isWithinRange(double distanceSq, double maxDistanceSq) {
        return distanceSq <= maxDistanceSq;
    }

    private static Set<String> collectMoveCategoryIds(Minecraft client) {
        if (client == null || client.player == null) {
            return Set.of();
        }

        boolean includeHotbar = TransferSettings.moveSourceMode().includesHotbar();
        Set<String> categoryIds = new HashSet<>();
        for (int slotIndex = 0; slotIndex < 36; slotIndex++) {
            if (!includeHotbar && slotIndex < 9) {
                continue;
            }

            ItemStack stack = client.player.getInventory().getItem(slotIndex);
            if (stack.isEmpty()) {
                continue;
            }
            LatchLabelClientState.itemCategoryMappingService()
                    .categoryIdFor(stack)
                    .ifPresent(categoryIds::add);
        }
        return categoryIds;
    }

    private record InspectCandidate(
            AABB box,
            double distanceSq,
            boolean matchesHeldCategory,
            boolean matchesInventoryCategory,
            boolean isFull,
            float r,
            float g,
            float b
    ) {
    }
}
