package com.latchandlabel.client.tooltip;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ItemCategoryMappingService {
    private static final Map<String, String> LEGACY_CATEGORY_ID_REMAP = Map.of(
            "blocks", "stones",
            "decor", "decorative",
            "redstone", "redstone_mechanisms",
            "tools_utility", "gear_utility",
            "gear", "gear_utility",
            "food_brewing", "food_brewing",
            "materials", "ores_valuables",
            "containers_stations", "functional",
            "special_spawn", "mob_drops"
    );

    private record OverrideSnapshot(Map<ResourceLocation, String> overrides, Set<ResourceLocation> blocked) {}

    private Runnable changeListener = () -> {
    };
    private Map<ResourceLocation, String> defaultMappings = Map.of();
    private volatile OverrideSnapshot overrideSnapshot = new OverrideSnapshot(new LinkedHashMap<>(), new LinkedHashSet<>());
    private volatile Map<ResourceLocation, String> mergedMappings = Map.of();

    public void initialize() {
        defaultMappings = ItemCategoryMappings.createDefaults();
        overrideSnapshot = new OverrideSnapshot(new LinkedHashMap<>(), new LinkedHashSet<>());
        rebuildMergedMappings();
    }

    public void refreshDefaultMappingsIfExpanded() {
        Map<ResourceLocation, String> refreshed = ItemCategoryMappings.createDefaults();
        if (refreshed.size() <= defaultMappings.size()) {
            return;
        }

        defaultMappings = refreshed;
        rebuildMergedMappings();
    }

    public void reload() {
        // Reload-from-disk is handled by ClientDataManager scoped persistence.
        defaultMappings = ItemCategoryMappings.createDefaults();
        rebuildMergedMappings();
    }

    public Optional<String> categoryIdFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }

        var itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        ResourceLocation itemId = itemKey != null ? itemKey.location() : null;
        if (itemId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(mergedMappings.get(itemId));
    }

    public Optional<String> categoryIdFor(ResourceLocation itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mergedMappings.get(itemId));
    }

    public void setOverride(ResourceLocation itemId, String categoryId) {
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            throw new IllegalArgumentException("Unknown item id: " + itemId);
        }
        String normalizedCategoryId = normalizeCategoryId(categoryId);
        if (normalizedCategoryId == null) {
            throw new IllegalArgumentException("Category id must be non-blank");
        }

        OverrideSnapshot snap = overrideSnapshot;
        Map<ResourceLocation, String> newOverrides = new LinkedHashMap<>(snap.overrides());
        Set<ResourceLocation> newBlocked = new LinkedHashSet<>(snap.blocked());
        newBlocked.remove(itemId);
        newOverrides.put(itemId, normalizedCategoryId);
        overrideSnapshot = new OverrideSnapshot(newOverrides, newBlocked);
        rebuildMergedMappings();
        notifyChanged();
    }

    public boolean removeOverride(ResourceLocation itemId) {
        if (itemId == null) {
            return false;
        }

        OverrideSnapshot snap = overrideSnapshot;
        if (!snap.overrides().containsKey(itemId) && !snap.blocked().contains(itemId)) {
            return false;
        }
        Map<ResourceLocation, String> newOverrides = new LinkedHashMap<>(snap.overrides());
        Set<ResourceLocation> newBlocked = new LinkedHashSet<>(snap.blocked());
        newOverrides.remove(itemId);
        newBlocked.remove(itemId);
        overrideSnapshot = new OverrideSnapshot(newOverrides, newBlocked);
        rebuildMergedMappings();
        notifyChanged();
        return true;
    }

    public void clearMapping(ResourceLocation itemId) {
        if (itemId == null) {
            return;
        }

        OverrideSnapshot snap = overrideSnapshot;
        Map<ResourceLocation, String> newOverrides = new LinkedHashMap<>(snap.overrides());
        Set<ResourceLocation> newBlocked = new LinkedHashSet<>(snap.blocked());
        newOverrides.remove(itemId);
        newBlocked.add(itemId);
        overrideSnapshot = new OverrideSnapshot(newOverrides, newBlocked);
        rebuildMergedMappings();
        notifyChanged();
    }

    public boolean isMappedToCategory(ResourceLocation itemId, String categoryId) {
        if (itemId == null || categoryId == null) {
            return false;
        }
        OverrideSnapshot snap = overrideSnapshot;
        if (snap.blocked().contains(itemId)) {
            return false;
        }

        String normalizedCategoryId = normalizeCategoryId(categoryId);
        if (normalizedCategoryId == null) {
            return false;
        }

        String overrideCategory = snap.overrides().get(itemId);
        if (overrideCategory != null) {
            return normalizedCategoryId.equals(overrideCategory);
        }

        String defaultCategory = defaultMappings.get(itemId);
        return Objects.equals(normalizedCategoryId, defaultCategory);
    }

    public void toggleCategoryMembership(ResourceLocation itemId, String categoryId) {
        if (isMappedToCategory(itemId, categoryId)) {
            clearMapping(itemId);
            return;
        }
        setOverride(itemId, categoryId);
    }

    public void clearCategoryReferences(String categoryId) {
        String normalizedCategoryId = normalizeCategoryId(categoryId);
        if (normalizedCategoryId == null) {
            return;
        }

        OverrideSnapshot snap = overrideSnapshot;
        Map<ResourceLocation, String> newOverrides = new LinkedHashMap<>(snap.overrides());
        boolean changed = newOverrides.entrySet().removeIf(entry -> normalizedCategoryId.equals(entry.getValue()));
        if (changed) {
            overrideSnapshot = new OverrideSnapshot(newOverrides, new LinkedHashSet<>(snap.blocked()));
            rebuildMergedMappings();
            notifyChanged();
        }
    }

    public void applyScopedOverrides(Map<ResourceLocation, String> overrides, Set<ResourceLocation> blocked) {
        overrideSnapshot = new OverrideSnapshot(
                new LinkedHashMap<>(Objects.requireNonNull(overrides, "overrides")),
                new LinkedHashSet<>(Objects.requireNonNull(blocked, "blocked"))
        );
        rebuildMergedMappings();
    }

    public Map<ResourceLocation, String> snapshotOverrides() {
        return immutableMapCopy(overrideSnapshot.overrides());
    }

    public Set<ResourceLocation> snapshotBlockedMappings() {
        return immutableSetCopy(overrideSnapshot.blocked());
    }

    public void setChangeListener(Runnable changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    public static String normalizeCategoryId(String categoryId) {
        if (categoryId == null) {
            return null;
        }
        String normalized = categoryId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return LEGACY_CATEGORY_ID_REMAP.getOrDefault(normalized, normalized);
    }

    private void rebuildMergedMappings() {
        OverrideSnapshot snap = overrideSnapshot;
        Map<ResourceLocation, String> defaults = new LinkedHashMap<>(defaultMappings);
        for (ResourceLocation blockedItemId : snap.blocked()) {
            defaults.remove(blockedItemId);
        }
        defaults.putAll(snap.overrides());
        mergedMappings = immutableMapCopy(defaults);
    }

    private void notifyChanged() {
        changeListener.run();
    }

    private static <K, V> Map<K, V> immutableMapCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <T> Set<T> immutableSetCopy(Set<T> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}
