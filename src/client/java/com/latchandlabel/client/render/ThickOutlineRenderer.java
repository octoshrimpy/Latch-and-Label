package com.latchandlabel.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;

public final class ThickOutlineRenderer {
    private ThickOutlineRenderer() {
    }

    public static void drawThickOutline(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            AABB box,
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
        drawPrism(pose, consumer, minX - t, minY, minZ - t, minX + t, maxY, minZ + t, r, g, b, a);
        drawPrism(pose, consumer, maxX - t, minY, minZ - t, maxX + t, maxY, minZ + t, r, g, b, a);
        drawPrism(pose, consumer, minX - t, minY, maxZ - t, minX + t, maxY, maxZ + t, r, g, b, a);
        drawPrism(pose, consumer, maxX - t, minY, maxZ - t, maxX + t, maxY, maxZ + t, r, g, b, a);

        // Bottom edges
        drawPrism(pose, consumer, minX, minY - t, minZ - t, maxX, minY + t, minZ + t, r, g, b, a);
        drawPrism(pose, consumer, minX, minY - t, maxZ - t, maxX, minY + t, maxZ + t, r, g, b, a);
        drawPrism(pose, consumer, minX - t, minY - t, minZ, minX + t, minY + t, maxZ, r, g, b, a);
        drawPrism(pose, consumer, maxX - t, minY - t, minZ, maxX + t, minY + t, maxZ, r, g, b, a);

        // Top edges
        drawPrism(pose, consumer, minX, maxY - t, minZ - t, maxX, maxY + t, minZ + t, r, g, b, a);
        drawPrism(pose, consumer, minX, maxY - t, maxZ - t, maxX, maxY + t, maxZ + t, r, g, b, a);
        drawPrism(pose, consumer, minX - t, maxY - t, minZ, minX + t, maxY + t, maxZ, r, g, b, a);
        drawPrism(pose, consumer, maxX - t, maxY - t, minZ, maxX + t, maxY + t, maxZ, r, g, b, a);
    }

    private static void drawPrism(
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
        RenderBox.drawFilledBox(pose, consumer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }
}
