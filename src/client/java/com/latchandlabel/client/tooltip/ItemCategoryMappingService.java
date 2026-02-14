package com.latchandlabel.client.tooltip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ItemCategoryMappingService {
    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
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

    private final Path overridesPath;
    private Map<Identifier, String> defaultMappings = Map.of();
    private Map<Identifier, String> overrideMappings = new LinkedHashMap<>();
    private Set<Identifier> blockedMappings = new LinkedHashSet<>();
    private Map<Identifier, String> mergedMappings = Map.of();

    public ItemCategoryMappingService() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(LatchLabel.MOD_ID);
        this.overridesPath = configDir.resolve("item_categories_overrides.json");
    }

    public void initialize() {
        try {
            Files.createDirectories(overridesPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create config directory for item mapping", e);
        }

        if (!Files.exists(overridesPath)) {
            writeDefaultOverridesSkeleton();
        }

        LoadedOverrides loaded = loadOverrides();
        defaultMappings = ItemCategoryMappings.createDefaults();
        overrideMappings = new LinkedHashMap<>(loaded.overrides());
        blockedMappings = new LinkedHashSet<>(loaded.blocked());
        rebuildMergedMappings();
        LatchLabel.LOGGER.info("Loaded {} item-category mappings ({} overrides)", mergedMappings.size(), overrideMappings.size());
    }

    public void refreshDefaultMappingsIfExpanded() {
        Map<Identifier, String> refreshed = ItemCategoryMappings.createDefaults();
        if (refreshed.size() <= defaultMappings.size()) {
            return;
        }

        int previous = defaultMappings.size();
        defaultMappings = refreshed;
        rebuildMergedMappings();
        LatchLabel.LOGGER.info("Refreshed default item-category mappings: {} -> {}", previous, refreshed.size());
    }

    public void reload() {
        LoadedOverrides loaded = loadOverrides();
        defaultMappings = ItemCategoryMappings.createDefaults();
        overrideMappings = new LinkedHashMap<>(loaded.overrides());
        blockedMappings = new LinkedHashSet<>(loaded.blocked());
        rebuildMergedMappings();
    }

    public void flushNow() {
        writeOverrides();
    }

    public Path overridesPath() {
        return overridesPath;
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
        writeOverrides();
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
        writeOverrides();
        return true;
    }

    public void clearMapping(Identifier itemId) {
        if (itemId == null) {
            return;
        }

        overrideMappings.remove(itemId);
        blockedMappings.add(itemId);
        rebuildMergedMappings();
        writeOverrides();
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
            writeOverrides();
        }
    }

    private LoadedOverrides loadOverrides() {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(overridesPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                LatchLabel.LOGGER.warn("Invalid mapping overrides file {}, expected JSON object", overridesPath);
                return LoadedOverrides.empty();
            }
            root = parsed.getAsJsonObject();
        } catch (IOException e) {
            LatchLabel.LOGGER.warn("Failed reading mapping overrides file {}", overridesPath, e);
            return LoadedOverrides.empty();
        }

        int version = parseVersion(root);
        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn(
                    "Unsupported item mapping overrides version {} in {}, using compatibility mode",
                    version,
                    overridesPath
            );
        }

        JsonElement overridesElement = root.get("overrides");
        if (overridesElement == null || !overridesElement.isJsonObject()) {
            return LoadedOverrides.empty();
        }

        Map<Identifier, String> parsedOverrides = new LinkedHashMap<>();
        Set<Identifier> blocked = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : overridesElement.getAsJsonObject().entrySet()) {
            Identifier itemId = Identifier.tryParse(entry.getKey());
            if (itemId == null || !Registries.ITEM.containsId(itemId)) {
                LatchLabel.LOGGER.warn("Ignoring invalid override item id '{}'", entry.getKey());
                continue;
            }
            if (entry.getValue().isJsonNull()) {
                blocked.add(itemId);
                continue;
            }
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                LatchLabel.LOGGER.warn("Ignoring invalid override category for item '{}'", entry.getKey());
                continue;
            }

            String categoryId = normalizeCategoryId(entry.getValue().getAsString());
            if (categoryId == null) {
                LatchLabel.LOGGER.warn("Ignoring blank override category for item '{}'", entry.getKey());
                continue;
            }

            parsedOverrides.put(itemId, categoryId);
        }

        return new LoadedOverrides(immutableMapCopy(parsedOverrides), immutableSetCopy(blocked));
    }

    private void writeDefaultOverridesSkeleton() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.add("overrides", new JsonObject());

        try (Writer writer = Files.newBufferedWriter(overridesPath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing default mapping overrides file: " + overridesPath, e);
        }
    }

    private void rebuildMergedMappings() {
        Map<Identifier, String> defaults = new LinkedHashMap<>(defaultMappings);
        for (Identifier blockedItemId : blockedMappings) {
            defaults.remove(blockedItemId);
        }
        defaults.putAll(overrideMappings);
        mergedMappings = immutableMapCopy(defaults);
    }

    private void writeOverrides() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);

        JsonObject overrides = new JsonObject();
        for (Identifier blockedItemId : blockedMappings) {
            overrides.add(blockedItemId.toString(), JsonNull.INSTANCE);
        }
        for (Map.Entry<Identifier, String> entry : overrideMappings.entrySet()) {
            overrides.addProperty(entry.getKey().toString(), entry.getValue());
        }
        root.add("overrides", overrides);

        try (Writer writer = Files.newBufferedWriter(overridesPath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing mapping overrides file: " + overridesPath, e);
        }
    }

    private static int parseVersion(JsonObject root) {
        JsonElement versionElement = root.get("version");
        if (versionElement == null || !versionElement.isJsonPrimitive() || !versionElement.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        return versionElement.getAsInt();
    }

    private static <K, V> Map<K, V> immutableMapCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static <T> Set<T> immutableSetCopy(Set<T> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }

    private record LoadedOverrides(Map<Identifier, String> overrides, Set<Identifier> blocked) {
        private static LoadedOverrides empty() {
            return new LoadedOverrides(Map.of(), Set.of());
        }
    }

    private static String normalizeCategoryId(String categoryId) {
        if (categoryId == null) {
            return null;
        }
        String normalized = categoryId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        return LEGACY_CATEGORY_ID_REMAP.getOrDefault(normalized, normalized);
    }
}
