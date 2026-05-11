package com.latchandlabel.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;

public final class RenderBox {
    private RenderBox() {
    }

    public static void drawFilledBox(
            PoseStack matrices,
            VertexConsumer consumer,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float r,
            float g,
            float b,
            float a
    ) {
        drawFilledBox(matrices.last(), consumer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    public static void drawFilledBox(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float r,
            float g,
            float b,
            float a
    ) {
        // North (-Z)
        quad(pose, consumer,
                maxX, minY, minZ,
                minX, minY, minZ,
                minX, maxY, minZ,
                maxX, maxY, minZ,
                r, g, b, a
        );
        // South (+Z)
        quad(pose, consumer,
                minX, minY, maxZ,
                maxX, minY, maxZ,
                maxX, maxY, maxZ,
                minX, maxY, maxZ,
                r, g, b, a
        );
        // West (-X)
        quad(pose, consumer,
                minX, minY, minZ,
                minX, minY, maxZ,
                minX, maxY, maxZ,
                minX, maxY, minZ,
                r, g, b, a
        );
        // East (+X)
        quad(pose, consumer,
                maxX, minY, maxZ,
                maxX, minY, minZ,
                maxX, maxY, minZ,
                maxX, maxY, maxZ,
                r, g, b, a
        );
        // Bottom (-Y)
        quad(pose, consumer,
                minX, minY, minZ,
                maxX, minY, minZ,
                maxX, minY, maxZ,
                minX, minY, maxZ,
                r, g, b, a
        );
        // Top (+Y)
        quad(pose, consumer,
                minX, maxY, maxZ,
                maxX, maxY, maxZ,
                maxX, maxY, minZ,
                minX, maxY, minZ,
                r, g, b, a
        );
    }

    private static void quad(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            double x3,
            double y3,
            double z3,
            double x4,
            double y4,
            double z4,
            float r,
            float g,
            float b,
            float a
    ) {
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        consumer.addVertex(pose, (float) x4, (float) y4, (float) z4).setColor(r, g, b, a);
    }
}
