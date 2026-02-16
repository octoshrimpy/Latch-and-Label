package com.latchandlabel.client.tooltip;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

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

    private Runnable changeListener = () -> {
    };
    private Map<Identifier, String> defaultMappings = Map.of();
    private Map<Identifier, String> overrideMappings = new LinkedHashMap<>();
    private Set<Identifier> blockedMappings = new LinkedHashSet<>();
    private Map<Identifier, String> mergedMappings = Map.of();

    public void initialize() {
        defaultMappings = ItemCategoryMappings.createDefaults();
        overrideMappings = new LinkedHashMap<>();
        blockedMappings = new LinkedHashSet<>();
        rebuildMergedMappings();
    }

    public void refreshDefaultMappingsIfExpanded() {
        Map<Identifier, String> refreshed = ItemCategoryMappings.createDefaults();
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

    public void flushNow() {
        // Persisted by ClientDataManager.
    }

    public Optional<String> categoryIdFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }

        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(mergedMappings.get(itemId));
    }

    public Optional<String> categoryIdFor(Identifier itemId) {
        if (itemId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mergedMappings.get(itemId));
    }

    public void setOverride(Identifier itemId, String categoryId) {
        if (itemId == null || !Registries.ITEM.containsId(itemId)) {
            throw new IllegalArgumentException("Unknown item id: " + itemId);
        }
        String normalizedCategoryId = normalizeCategoryId(categoryId);
        if (normalizedCategoryId == null) {
            throw new IllegalArgumentException("Category id must be non-blank");
        }

        blockedMappings.remove(itemId);
        overrideMappings.put(itemId, normalizedCategoryId);
        rebuildMergedMappings();
        notifyChanged();
    }

    public boolean removeOverride(Identifier itemId) {
        if (itemId == null) {
            return false;
        }

        String removed = overrideMappings.remove(itemId);
        if (removed == null && !blockedMappings.contains(itemId)) {
            return false;
        }

        blockedMappings.remove(itemId);
        rebuildMergedMappings();
        notifyChanged();
        return true;
    }

    public void clearMapping(Identifier itemId) {
        if (itemId == null) {
            return;
        }

        overrideMappings.remove(itemId);
        blockedMappings.add(itemId);
        rebuildMergedMappings();
        notifyChanged();
    }

    public boolean isMappedToCategory(Identifier itemId, String categoryId) {
        if (itemId == null || categoryId == null) {
            return false;
        }
        if (blockedMappings.contains(itemId)) {
            return false;
        }

        String normalizedCategoryId = normalizeCategoryId(categoryId);
        if (normalizedCategoryId == null) {
            return false;
        }

        String overrideCategory = overrideMappings.get(itemId);
        if (overrideCategory != null) {
            return normalizedCategoryId.equals(overrideCategory);
        }

        String defaultCategory = defaultMappings.get(itemId);
        return Objects.equals(normalizedCategoryId, defaultCategory);
    }

    public void toggleCategoryMembership(Identifier itemId, String categoryId) {
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

        boolean changed = overrideMappings.entrySet().removeIf(entry -> normalizedCategoryId.equals(entry.getValue()));
        if (changed) {
            rebuildMergedMappings();
            notifyChanged();
        }
    }

    public void applyScopedOverrides(Map<Identifier, String> overrides, Set<Identifier> blocked) {
        overrideMappings = new LinkedHashMap<>(Objects.requireNonNull(overrides, "overrides"));
        blockedMappings = new LinkedHashSet<>(Objects.requireNonNull(blocked, "blocked"));
        rebuildMergedMappings();
    }

    public Map<Identifier, String> snapshotOverrides() {
        return immutableMapCopy(overrideMappings);
    }

    public Set<Identifier> snapshotBlockedMappings() {
        return immutableSetCopy(blockedMappings);
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
        Map<Identifier, String> defaults = new LinkedHashMap<>(defaultMappings);
        for (Identifier blockedItemId : blockedMappings) {
            defaults.remove(blockedItemId);
        }
        defaults.putAll(overrideMappings);
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
