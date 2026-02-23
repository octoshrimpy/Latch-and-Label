package com.latchandlabel.client.render;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

public final class RenderBox {
    private static final MethodHandle NEXT_VERTEX;
    private static final MethodHandle LINE_WIDTH;
    private static volatile boolean lineWidthFailed;
    private static volatile boolean lineBoxRenderingDisabled;

    static {
        MethodHandle nextHandle = null;
        MethodHandle lineWidthHandle = null;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            nextHandle = lookup.unreflect(VertexConsumer.class.getMethod("next"));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }
        try {
            lineWidthHandle = lookup.unreflect(VertexConsumer.class.getMethod("lineWidth", float.class));
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
        }
        NEXT_VERTEX = nextHandle;
        LINE_WIDTH = lineWidthHandle;
    }

    private RenderBox() {
    }

    public static void drawBox(
            MatrixStack.Entry entry,
            VertexConsumer consumer,
            Box box,
            float r,
            float g,
            float b,
            float a
    ) {
        if (lineBoxRenderingDisabled) {
            return;
        }

        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;
        try {
            // Bottom rectangle
            line(entry, consumer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
            line(entry, consumer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
            line(entry, consumer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
            line(entry, consumer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);

            // Top rectangle
            line(entry, consumer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
            line(entry, consumer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
            line(entry, consumer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
            line(entry, consumer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);

            // Vertical edges
            line(entry, consumer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
            line(entry, consumer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
            line(entry, consumer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
            line(entry, consumer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);
        } catch (IllegalStateException error) {
            if (isIncompatibleLineVertexFormat(error)) {
                lineBoxRenderingDisabled = true;
                return;
            }
            throw error;
        }
    }

    public static void drawFilledBox(
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
        drawFilledBox(matrices.peek(), consumer, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    public static void drawFilledBox(
            MatrixStack.Entry entry,
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
            MatrixStack.Entry entry,
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
        vertex(entry, consumer, x1, y1, z1, r, g, b, a, false);
        vertex(entry, consumer, x2, y2, z2, r, g, b, a, false);
        vertex(entry, consumer, x3, y3, z3, r, g, b, a, false);
        vertex(entry, consumer, x4, y4, z4, r, g, b, a, false);
    }

    private static void line(
            MatrixStack.Entry entry,
            VertexConsumer consumer,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            float r,
            float g,
            float b,
            float a
    ) {
        vertex(entry, consumer, x1, y1, z1, r, g, b, a, true);
        vertex(entry, consumer, x2, y2, z2, r, g, b, a, true);
    }

    private static void vertex(
            MatrixStack.Entry entry,
            VertexConsumer consumer,
            double x,
            double y,
            double z,
            float r,
            float g,
            float b,
            float a,
            boolean withLineWidth
    ) {
        VertexConsumer chained = consumer.vertex(entry, (float) x, (float) y, (float) z).color(r, g, b, a);
        if (withLineWidth) {
            applyLineWidth(chained, 1.0f);
        }
        finishVertex(chained);
    }

    private static void finishVertex(VertexConsumer consumer) {
        if (NEXT_VERTEX == null) {
            return;
        }
        try {
            NEXT_VERTEX.invoke(consumer);
        } catch (Throwable error) {
            throw new IllegalStateException("Failed to finish vertex", error);
        }
    }

    private static void applyLineWidth(VertexConsumer consumer, float width) {
        if (lineWidthFailed || LINE_WIDTH == null) {
            return;
        }
        try {
            LINE_WIDTH.invoke(consumer, width);
        } catch (Throwable error) {
            lineWidthFailed = true;
        }
    }

    private static boolean isIncompatibleLineVertexFormat(IllegalStateException error) {
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Missing elements in vertex");
    }
}
