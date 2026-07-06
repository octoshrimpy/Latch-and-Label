package com.latchandlabel.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;

/**
 * Draws corner "brackets" (the 8 corners of a box, each as three short legs) plus an
 * optional floating marker. State reads from shape — brackets vs a full outline, and the
 * presence of a marker — not from line thickness or hue (hue is the category color).
 */
public final class CornerBracketRenderer {
    private static final double MARKER_HALF = 0.05;
    private static final double MARKER_HEIGHT = 0.28;
    private static final double MARKER_GAP = 0.12;

    private CornerBracketRenderer() {
    }

    public static void drawCornerBrackets(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            AABB box,
            float thickness,
            float legLength,
            float r,
            float g,
            float b,
            float a
    ) {
        double t = Math.max(0.001, thickness);
        double maxLeg = Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) * 0.5;
        double len = Math.min(legLength, maxLeg);

        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double cx = xs[xi];
                    double cy = ys[yi];
                    double cz = zs[zi];
                    double x2 = cx + (xi == 0 ? len : -len);
                    double y2 = cy + (yi == 0 ? len : -len);
                    double z2 = cz + (zi == 0 ? len : -len);

                    // leg along X
                    RenderBox.drawFilledBox(pose, consumer,
                            Math.min(cx, x2), cy - t, cz - t, Math.max(cx, x2), cy + t, cz + t, r, g, b, a);
                    // leg along Y
                    RenderBox.drawFilledBox(pose, consumer,
                            cx - t, Math.min(cy, y2), cz - t, cx + t, Math.max(cy, y2), cz + t, r, g, b, a);
                    // leg along Z
                    RenderBox.drawFilledBox(pose, consumer,
                            cx - t, cy - t, Math.min(cz, z2), cx + t, cy + t, Math.max(cz, z2), r, g, b, a);
                }
            }
        }
    }

    /** Small beacon prism hovering above the box top-center. {@code bob} shifts it vertically for a breathing effect. */
    public static void drawMarker(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            AABB box,
            double bob,
            float r,
            float g,
            float b,
            float a
    ) {
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double baseY = box.maxY + MARKER_GAP + bob;
        RenderBox.drawFilledBox(pose, consumer,
                cx - MARKER_HALF, baseY, cz - MARKER_HALF,
                cx + MARKER_HALF, baseY + MARKER_HEIGHT, cz + MARKER_HALF,
                r, g, b, a);
    }
}
