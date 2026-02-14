package com.latchandlabel.client.render;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;

public final class ThickOutlineRenderer {
    private ThickOutlineRenderer() {
    }

    public static void drawThickOutline(
            MatrixStack matrices,
            VertexConsumer consumer,
            Box box,
            float thickness,
            float r,
            float g,
            float b,
            float a
    ) {
        double t = Math.max(0.001, thickness);
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;

        // Vertical edges
        drawPrism(matrices, consumer, minX - t, minY, minZ - t, minX + t, maxY, minZ + t, r, g, b, a);
        drawPrism(matrices, consumer, maxX - t, minY, minZ - t, maxX + t, maxY, minZ + t, r, g, b, a);
        drawPrism(matrices, consumer, minX - t, minY, maxZ - t, minX + t, maxY, maxZ + t, r, g, b, a);
        drawPrism(matrices, consumer, maxX - t, minY, maxZ - t, maxX + t, maxY, maxZ + t, r, g, b, a);

        // Bottom edges
        drawPrism(matrices, consumer, minX, minY - t, minZ - t, maxX, minY + t, minZ + t, r, g, b, a);
        drawPrism(matrices, consumer, minX, minY - t, maxZ - t, maxX, minY + t, maxZ + t, r, g, b, a);
        drawPrism(matrices, consumer, minX - t, minY - t, minZ, minX + t, minY + t, maxZ, r, g, b, a);
        drawPrism(matrices, consumer, maxX - t, minY - t, minZ, maxX + t, minY + t, maxZ, r, g, b, a);

        // Top edges
        drawPrism(matrices, consumer, minX, maxY - t, minZ - t, maxX, maxY + t, minZ + t, r, g, b, a);
        drawPrism(matrices, consumer, minX, maxY - t, maxZ - t, maxX, maxY + t, maxZ + t, r, g, b, a);
        drawPrism(matrices, consumer, minX - t, maxY - t, minZ, minX + t, maxY + t, maxZ, r, g, b, a);
        drawPrism(matrices, consumer, maxX - t, maxY - t, minZ, maxX + t, maxY + t, maxZ, r, g, b, a);
    }

    private static void drawPrism(
            MatrixStack matrices,
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
        VertexRendering.drawFilledBox(matrices, consumer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }
}
