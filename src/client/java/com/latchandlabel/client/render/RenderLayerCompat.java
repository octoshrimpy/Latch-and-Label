package com.latchandlabel.client.render;

import net.minecraft.client.render.RenderLayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RenderLayerCompat {
    private static final Method RENDER_LAYERS_LINES;
    private static final Method RENDER_LAYERS_DEBUG_FILLED_BOX;
    private static final Field RENDER_LAYERS_LINES_FIELD;
    private static final Field RENDER_LAYERS_DEBUG_FILLED_BOX_FIELD;
    private static final Method RENDER_LAYER_GET_LINES;
    private static final Method RENDER_LAYER_GET_DEBUG_FILLED_BOX;

    static {
        Method renderLayersLines = null;
        Method renderLayersDebugFilledBox = null;
        Field renderLayersLinesField = null;
        Field renderLayersDebugFilledBoxField = null;
        Method renderLayerGetLines = null;
        Method renderLayerGetDebugFilledBox = null;

        try {
            Class<?> renderLayersClass = Class.forName("net.minecraft.client.render.RenderLayers");
            renderLayersLines = findMethod(renderLayersClass, "lines");
            renderLayersDebugFilledBox = findMethod(renderLayersClass, "debugFilledBox");
            renderLayersLinesField = findField(renderLayersClass, "LINES");
            renderLayersDebugFilledBoxField = findField(renderLayersClass, "DEBUG_FILLED_BOX");
        } catch (ClassNotFoundException ignored) {
        }

        try {
            Class<?> renderLayerClass = Class.forName("net.minecraft.client.render.RenderLayer");
            renderLayerGetLines = findMethod(renderLayerClass, "getLines");
            renderLayerGetDebugFilledBox = findMethod(renderLayerClass, "getDebugFilledBox");
        } catch (ClassNotFoundException ignored) {
        }

        RENDER_LAYERS_LINES = renderLayersLines;
        RENDER_LAYERS_DEBUG_FILLED_BOX = renderLayersDebugFilledBox;
        RENDER_LAYERS_LINES_FIELD = renderLayersLinesField;
        RENDER_LAYERS_DEBUG_FILLED_BOX_FIELD = renderLayersDebugFilledBoxField;
        RENDER_LAYER_GET_LINES = renderLayerGetLines;
        RENDER_LAYER_GET_DEBUG_FILLED_BOX = renderLayerGetDebugFilledBox;
    }

    private RenderLayerCompat() {
    }

    public static RenderLayer lines() {
        RenderLayer layer = invokeLayer(RENDER_LAYERS_LINES);
        if (layer != null) {
            return layer;
        }
        layer = readLayer(RENDER_LAYERS_LINES_FIELD);
        if (layer != null) {
            return layer;
        }
        layer = invokeLayer(RENDER_LAYER_GET_LINES);
        if (layer != null) {
            return layer;
        }
        throw new IllegalStateException("Unable to resolve lines render layer");
    }

    public static RenderLayer debugFilledBox() {
        RenderLayer layer = invokeLayer(RENDER_LAYERS_DEBUG_FILLED_BOX);
        if (layer != null) {
            return layer;
        }
        layer = readLayer(RENDER_LAYERS_DEBUG_FILLED_BOX_FIELD);
        if (layer != null) {
            return layer;
        }
        layer = invokeLayer(RENDER_LAYER_GET_DEBUG_FILLED_BOX);
        if (layer != null) {
            return layer;
        }
        throw new IllegalStateException("Unable to resolve debug filled box render layer");
    }

    private static Method findMethod(Class<?> target, String name) {
        try {
            return target.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> target, String name) {
        try {
            return target.getField(name);
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static RenderLayer invokeLayer(Method method) {
        if (method == null) {
            return null;
        }
        try {
            return (RenderLayer) method.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static RenderLayer readLayer(Field field) {
        if (field == null) {
            return null;
        }
        try {
            return (RenderLayer) field.get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
