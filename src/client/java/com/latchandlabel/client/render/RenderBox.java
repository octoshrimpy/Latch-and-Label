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
        drawFilledBox(matrices.peek(), consumer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    public static void drawFilledBox(
            PoseStack.Entry entry,
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
        quad(entry, consumer,
                maxX, minY, minZ,
                minX, minY, minZ,
                minX, maxY, minZ,
                maxX, maxY, minZ,
                r, g, b, a
        );
        // South (+Z)
        quad(entry, consumer,
                minX, minY, maxZ,
                maxX, minY, maxZ,
                maxX, maxY, maxZ,
                minX, maxY, maxZ,
                r, g, b, a
        );
        // West (-X)
        quad(entry, consumer,
                minX, minY, minZ,
                minX, minY, maxZ,
                minX, maxY, maxZ,
                minX, maxY, minZ,
                r, g, b, a
        );
        // East (+X)
        quad(entry, consumer,
                maxX, minY, maxZ,
                maxX, minY, minZ,
                maxX, maxY, minZ,
                maxX, maxY, maxZ,
                r, g, b, a
        );
        // Bottom (-Y)
        quad(entry, consumer,
                minX, minY, minZ,
                maxX, minY, minZ,
                maxX, minY, maxZ,
                minX, minY, maxZ,
                r, g, b, a
        );
        // Top (+Y)
        quad(entry, consumer,
                minX, maxY, maxZ,
                maxX, maxY, maxZ,
                maxX, maxY, minZ,
                minX, maxY, minZ,
                r, g, b, a
        );
    }

    private static void quad(
            PoseStack.Entry entry,
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
        consumer.vertex(entry, (float) x1, (float) y1, (float) z1).color(r, g, b, a);
        consumer.vertex(entry, (float) x2, (float) y2, (float) z2).color(r, g, b, a);
        consumer.vertex(entry, (float) x3, (float) y3, (float) z3).color(r, g, b, a);
        consumer.vertex(entry, (float) x4, (float) y4, (float) z4).color(r, g, b, a);
    }
}
