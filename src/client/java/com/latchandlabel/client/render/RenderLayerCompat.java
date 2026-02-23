package com.latchandlabel.client.render;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class RenderLayerCompat {
    private static volatile RenderLayer linesLayer;
    private static volatile RenderLayer debugFilledBoxLayer;

    private RenderLayerCompat() {
    }

    public static RenderLayer lines() {
        RenderLayer cached = linesLayer;
        if (cached != null) {
            return cached;
        }
        synchronized (RenderLayerCompat.class) {
            if (linesLayer == null) {
                linesLayer = resolveLayer(name -> name.contains("lines") && !name.contains("translucent"));
            }
            return linesLayer;
        }
    }

    public static RenderLayer debugFilledBox() {
        RenderLayer cached = debugFilledBoxLayer;
        if (cached != null) {
            return cached;
        }
        synchronized (RenderLayerCompat.class) {
            if (debugFilledBoxLayer == null) {
                debugFilledBoxLayer = resolveLayer(name ->
                        name.contains("debug_filled_box")
                                || (name.contains("debug") && name.contains("filled") && name.contains("box"))
                );
            }
            return debugFilledBoxLayer;
        }
    }

    private static RenderLayer resolveLayer(Predicate<String> matcher) {
        List<String> candidates = new ArrayList<>();
        RenderLayer fromMethods = resolveFromMethods(RenderLayers.class, matcher, candidates);
        if (fromMethods != null) {
            return fromMethods;
        }
        RenderLayer fromFields = resolveFromFields(RenderLayers.class, matcher, candidates);
        if (fromFields != null) {
            return fromFields;
        }
        fromMethods = resolveFromMethods(RenderLayer.class, matcher, candidates);
        if (fromMethods != null) {
            return fromMethods;
        }
        fromFields = resolveFromFields(RenderLayer.class, matcher, candidates);
        if (fromFields != null) {
            return fromFields;
        }

        throw new IllegalStateException("Unable to resolve render layer. Candidates: " + candidates);
    }

    private static RenderLayer resolveFromMethods(Class<?> owner, Predicate<String> matcher, List<String> candidates) {
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                continue;
            }
            if (!RenderLayer.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }

            method.setAccessible(true);
            try {
                RenderLayer layer = (RenderLayer) method.invoke(null);
                if (layer == null) {
                    continue;
                }
                String name = normalizedName(layer);
                candidates.add(owner.getSimpleName() + "#" + method.getName() + "=" + name);
                if (matcher.test(name)) {
                    return layer;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static RenderLayer resolveFromFields(Class<?> owner, Predicate<String> matcher, List<String> candidates) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!RenderLayer.class.isAssignableFrom(field.getType())) {
                continue;
            }

            field.setAccessible(true);
            try {
                RenderLayer layer = (RenderLayer) field.get(null);
                if (layer == null) {
                    continue;
                }
                String name = normalizedName(layer);
                candidates.add(owner.getSimpleName() + "." + field.getName() + "=" + name);
                if (matcher.test(name)) {
                    return layer;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static String normalizedName(RenderLayer layer) {
        return String.valueOf(layer).toLowerCase(Locale.ROOT);
    }
}
