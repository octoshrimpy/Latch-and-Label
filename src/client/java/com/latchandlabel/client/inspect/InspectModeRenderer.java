package com.latchandlabel.client.inspect;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.config.TransferSettings;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.render.CornerBracketRenderer;
import com.latchandlabel.client.render.RenderLayerCompat;
import com.latchandlabel.client.tagging.ShulkerItemCategoryBridge;
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
    // Uniform line width everywhere: state reads from shape (full outline vs corner brackets + marker), not thickness.
    private static final float OUTLINE_THICKNESS = 0.020f;
    private static final float BRACKET_THICKNESS = 0.022f;
    private static final float BRACKET_LEG_LENGTH = 0.22f;
    private static final float PLAIN_ALPHA = 0.45f;
    private static final float FULL_OUTLINE_ALPHA = 0.15f;
    private static final float INV_TARGET_ALPHA = 0.85f;
    private static final float HELD_MATCH_ALPHA = 0.95f;
    private static final double LOD_MID_DISTANCE = 24.0;
    private static final double FRUSTUM_MARGIN = 0.06;
    private static final double MATCH_PULSE_SPEED_RADIANS = 6.0;
    private static final double MARKER_BOB_AMPLITUDE = 0.06;

    private InspectModeRenderer() {
    }

    public static void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        boolean inspect = ClientInputHandler.isInspectModeActive();
        boolean always = InspectSettings.bordersAlwaysVisible();
        if (!inspect && !always) {
            return;
        }

        Level world = client.level;
        Identifier dimensionId = McCompat.dimensionId(world);
        Vec3 cameraPos = McCompat.mainCamera(client).position();
        double maxDistanceSq = Math.pow(InspectSettings.inspectRange(), 2);
        double midDistanceSq = LOD_MID_DISTANCE * LOD_MID_DISTANCE;
        long frameParity = world.getGameTime() & 1L;
        Frustum frustum = context.levelState().cameraRenderState.cullFrustum;

        Map<ChestKey, String> tags = LatchLabelClientState.tagStore().snapshotTags();
        // Held/inventory matches only apply while actively inspecting; always-on shows plain borders only.
        Optional<String> heldItemCategoryId = inspect
                ? categoryIdFor(client.player.getMainHandItem())
                : Optional.empty();
        boolean isAltDown = inspect && ClientInputHandler.isAltDown();
        Set<String> inventoryCategoryIds = isAltDown ? collectMoveCategoryIds(client) : Set.of();
        double pulse = 0.5 + (0.5 * Math.sin((System.currentTimeMillis() / 1000.0) * MATCH_PULSE_SPEED_RADIANS));

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
        final double finalPulse = pulse;
        final List<InspectCandidate> finalCandidates = candidates;

        PoseStack matrices = context.poseStack();
        matrices.pushPose();
        matrices.translate(-camX, -camY, -camZ);

        context.submitNodeCollector().submitCustomGeometry(matrices, RenderLayerCompat.debugFilledBox(), (pose, consumer) -> {
            double bob = (finalPulse - 0.5) * 2.0 * MARKER_BOB_AMPLITUDE;
            int rendered = 0;
            for (InspectCandidate candidate : finalCandidates) {
                if (rendered >= MAX_CONTAINERS_PER_FRAME) {
                    break;
                }

                AABB box = candidate.box().inflate(BASE_OUTLINE_EXPAND);
                float r = candidate.r();
                float g = candidate.g();
                float b = candidate.b();

                if (candidate.isFull()) {
                    // Full storage: faint full outline, no target cue.
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box, OUTLINE_THICKNESS, r, g, b, FULL_OUTLINE_ALPHA);
                } else if (candidate.matchesHeldCategory()) {
                    // Item you're holding goes here: brackets + floating marker + breathing alpha.
                    float a = HELD_MATCH_ALPHA * (float) (0.7 + 0.3 * finalPulse);
                    CornerBracketRenderer.drawCornerBrackets(pose, consumer, box, BRACKET_THICKNESS, BRACKET_LEG_LENGTH, r, g, b, a);
                    CornerBracketRenderer.drawMarker(pose, consumer, box, bob, r, g, b, a);
                } else if (candidate.matchesInventoryCategory()) {
                    // Something in your inventory goes here: brackets, no marker.
                    CornerBracketRenderer.drawCornerBrackets(pose, consumer, box, BRACKET_THICKNESS, BRACKET_LEG_LENGTH, r, g, b, INV_TARGET_ALPHA);
                } else {
                    // Plain tag: hollow full outline.
                    ThickOutlineRenderer.drawThickOutline(pose, consumer, box, OUTLINE_THICKNESS, r, g, b, PLAIN_ALPHA);
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
            categoryIdFor(stack).ifPresent(categoryIds::add);
        }
        return categoryIds;
    }

    /** A shulker of one category counts as that category, same as the alt-punch move and the sort do. */
    private static Optional<String> categoryIdFor(ItemStack stack) {
        return ShulkerItemCategoryBridge.resolveCategoryIdForStack(stack)
                .or(() -> LatchLabelClientState.itemCategoryMappingService().categoryIdFor(stack));
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
