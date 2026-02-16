package com.latchandlabel.client.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.TagStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ClientDataManager implements AutoCloseable {
    private static final int CURRENT_VERSION = 1;
    private static final long SAVE_DEBOUNCE_MS = 1_000L;
    private static final Identifier FALLBACK_ICON_ITEM_ID = Objects.requireNonNull(
            Identifier.tryParse("minecraft:stone"),
            "Invalid fallback identifier"
    );

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Set<String> LEGACY_DEFAULT_CATEGORY_IDS = Set.of(
            "blocks", "decor", "redstone", "tools_utility", "gear",
            "food_brewing", "materials", "containers_stations", "special_spawn"
    );
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

    private final CategoryStore categoryStore;
    private final TagStore tagStore;
    private final Path configDir;
    private final Path categoriesFilePath;
    private final Path tagsFilePath;
    private final ScheduledExecutorService saveExecutor;
    private final Object saveLock = new Object();

    private ScheduledFuture<?> pendingSave;
    private boolean initialized;
    private boolean closed;
    private boolean suppressSaveScheduling;

    public ClientDataManager(CategoryStore categoryStore, TagStore tagStore) {
        this.categoryStore = Objects.requireNonNull(categoryStore, "categoryStore");
        this.tagStore = Objects.requireNonNull(tagStore, "tagStore");

        this.configDir = FabricLoader.getInstance().getConfigDir().resolve(LatchLabel.MOD_ID);
        this.categoriesFilePath = configDir.resolve("categories.json");
        this.tagsFilePath = configDir.resolve("tags.json");

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "latchlabel-save-worker");
            thread.setDaemon(true);
            return thread;
        };
        this.saveExecutor = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    public void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create config directory: " + configDir, e);
        }

        loadCategories();
        loadTags();

        categoryStore.setChangeListener(this::scheduleSave);
        tagStore.setChangeListener(this::scheduleSave);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownFlush, "latchlabel-save-shutdown"));
        LatchLabel.LOGGER.info("Loaded client data from {}", configDir);
    }

    public void scheduleSave() {
        synchronized (saveLock) {
            if (closed || suppressSaveScheduling) {
                return;
            }

            if (pendingSave != null) {
                pendingSave.cancel(false);
            }
            pendingSave = saveExecutor.schedule(this::flushSafely, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void flushNow() {
        synchronized (saveLock) {
            if (closed) {
                return;
            }

            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }

        writeCategories();
        writeTags();
    }

    public void reloadFromDisk() {
        synchronized (saveLock) {
            if (closed || !initialized) {
                return;
            }

            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        runWithSaveSchedulingSuppressed(() -> {
            loadCategories();
            loadTags();
        });
    }

    @Override
    public void close() {
        synchronized (saveLock) {
            if (closed) {
                return;
            }
            closed = true;

            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }

        writeCategories();
        writeTags();
        saveExecutor.shutdown();
    }

    private void shutdownFlush() {
        try {
            close();
        } catch (Exception e) {
            LatchLabel.LOGGER.error("Failed to flush client data at shutdown", e);
        }
    }

    private void flushSafely() {
        try {
            flushNow();
        } catch (Exception e) {
            LatchLabel.LOGGER.error("Failed to flush debounced client data", e);
        }
    }

    private void runWithSaveSchedulingSuppressed(Runnable operation) {
        synchronized (saveLock) {
            suppressSaveScheduling = true;
        }
        try {
            operation.run();
        } finally {
            synchronized (saveLock) {
                suppressSaveScheduling = false;
            }
        }
    }

    private void loadCategories() {
        if (!Files.exists(categoriesFilePath)) {
            categoryStore.replaceAll(DefaultCategories.create());
            writeCategories();
            return;
        }

        JsonObject root;
        try {
            root = readJsonObject(categoriesFilePath);
        } catch (IllegalStateException e) {
            LatchLabel.LOGGER.warn("Invalid categories file {}, using defaults", categoriesFilePath, e);
            categoryStore.replaceAll(DefaultCategories.create());
            writeCategories();
            return;
        }
        int version = parseVersion(root, categoriesFilePath);

        List<Category> categories = new ArrayList<>();
        JsonElement categoriesElement = root.get("categories");
        if (categoriesElement != null && categoriesElement.isJsonArray()) {
            for (JsonElement element : categoriesElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                Category category = parseCategory(element.getAsJsonObject());
                if (category != null) {
                    categories.add(category);
                }
            }
        }

        if (categories.isEmpty()) {
            categories = DefaultCategories.create();
            LatchLabel.LOGGER.warn("No valid categories found in {}, using defaults", categoriesFilePath);
        }
        if (isLegacyDefaultCategorySet(categories)) {
            categories = DefaultCategories.create();
            writeCategories();
            LatchLabel.LOGGER.info("Migrated legacy default categories to new defaults in {}", categoriesFilePath);
        }

        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn(
                    "Unsupported categories.json version {} in {}; using compatibility load path",
                    version,
                    categoriesFilePath
            );
        }

        categoryStore.replaceAll(categories);
    }

    private void loadTags() {
        if (!Files.exists(tagsFilePath)) {
            tagStore.replaceAllScopes(Map.of(), Map.of(), tagStore.getActiveScopeId());
            writeTags();
            return;
        }

        JsonObject root;
        try {
            root = readJsonObject(tagsFilePath);
        } catch (IllegalStateException e) {
            LatchLabel.LOGGER.warn("Invalid tags file {}, using empty state", tagsFilePath, e);
            tagStore.replaceAllScopes(Map.of(), Map.of(), tagStore.getActiveScopeId());
            writeTags();
            return;
        }
        int version = parseVersion(root, tagsFilePath);

        Map<String, Map<ChestKey, String>> parsedTagsByScope = new HashMap<>();
        Map<String, String> parsedLastUsedByScope = new HashMap<>();
        JsonElement scopesElement = root.get("scopes");
        if (scopesElement != null && scopesElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> scopeEntry : scopesElement.getAsJsonObject().entrySet()) {
                if (!scopeEntry.getValue().isJsonObject()) {
                    continue;
                }
                String scopeId = scopeEntry.getKey();
                JsonObject scopeObject = scopeEntry.getValue().getAsJsonObject();
                Map<ChestKey, String> parsedScopeTags = parseTagsObject(scopeObject.get("tags"));
                parsedTagsByScope.put(scopeId, parsedScopeTags);
                String lastUsedCategoryId = remapLegacyCategoryId(asString(scopeObject.get("lastUsedCategoryId")));
                if (lastUsedCategoryId != null && !lastUsedCategoryId.isBlank()) {
                    parsedLastUsedByScope.put(scopeId, lastUsedCategoryId);
                }
            }
        } else {
            // Legacy format: root-level tags + lastUsedCategoryId.
            parsedTagsByScope.put(TagStore.DEFAULT_SCOPE_ID, parseTagsObject(root.get("tags")));
            String legacyLastUsedCategoryId = remapLegacyCategoryId(asString(root.get("lastUsedCategoryId")));
            if (legacyLastUsedCategoryId != null && !legacyLastUsedCategoryId.isBlank()) {
                parsedLastUsedByScope.put(TagStore.DEFAULT_SCOPE_ID, legacyLastUsedCategoryId);
            }
        }

        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn(
                    "Unsupported tags.json version {} in {}; using compatibility load path",
                    version,
                    tagsFilePath
            );
        }

        tagStore.replaceAllScopes(parsedTagsByScope, parsedLastUsedByScope, tagStore.getActiveScopeId());
    }

    private void writeCategories() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);

        var serializedCategories = new com.google.gson.JsonArray();
        for (Category category : categoryStore.listAll()) {
            JsonObject categoryObject = new JsonObject();
            categoryObject.addProperty("id", category.id());
            categoryObject.addProperty("name", category.name());
            categoryObject.addProperty("color", category.color());
            categoryObject.addProperty("iconItemId", category.iconItemId().toString());
            categoryObject.addProperty("order", category.order());
            categoryObject.addProperty("visible", category.visible());
            serializedCategories.add(categoryObject);
        }

        root.add("categories", serializedCategories);
        writeJsonObject(categoriesFilePath, root);
    }

    private void writeTags() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);

        JsonObject scopesObject = new JsonObject();
        Map<String, Map<ChestKey, String>> tagsByScope = tagStore.snapshotAllTagsByScope();
        Map<String, String> lastUsedByScope = tagStore.snapshotLastUsedCategoryIdByScope();
        Set<String> scopeIds = new HashSet<>(tagsByScope.keySet());
        scopeIds.addAll(lastUsedByScope.keySet());
        for (String scopeId : scopeIds) {
            JsonObject scopeObject = new JsonObject();
            JsonObject tagsObject = new JsonObject();
            for (Map.Entry<ChestKey, String> entry : tagsByScope.getOrDefault(scopeId, Map.of()).entrySet()) {
                tagsObject.addProperty(entry.getKey().toStringKey(), entry.getValue());
            }
            scopeObject.add("tags", tagsObject);

            String lastUsedCategoryId = remapLegacyCategoryId(lastUsedByScope.get(scopeId));
            if (lastUsedCategoryId != null && !lastUsedCategoryId.isBlank()) {
                scopeObject.addProperty("lastUsedCategoryId", lastUsedCategoryId);
            }
            scopesObject.add(scopeId, scopeObject);
        }
        root.add("scopes", scopesObject);

        writeJsonObject(tagsFilePath, root);
    }

    private Map<ChestKey, String> parseTagsObject(JsonElement tagsElement) {
        Map<ChestKey, String> parsedTags = new HashMap<>();
        if (tagsElement == null || !tagsElement.isJsonObject()) {
            return parsedTags;
        }

        for (Map.Entry<String, JsonElement> entry : tagsElement.getAsJsonObject().entrySet()) {
            String categoryId = remapLegacyCategoryId(asString(entry.getValue()));
            if (categoryId == null || categoryId.isBlank()) {
                continue;
            }

            try {
                parsedTags.put(ChestKey.fromStringKey(entry.getKey()), categoryId);
            } catch (IllegalArgumentException ex) {
                LatchLabel.LOGGER.warn("Skipping invalid chest key '{}' in tags file", entry.getKey());
            }
        }
        return parsedTags;
    }

    private static JsonObject readJsonObject(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                throw new JsonParseException("Expected JSON object at root");
            }
            return element.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Failed reading json file: " + path, e);
        }
    }

    private static void writeJsonObject(Path path, JsonObject root) {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing json file: " + path, e);
        }
    }

    private static int parseVersion(JsonObject root, Path path) {
        JsonElement versionElement = root.get("version");
        if (versionElement == null || !versionElement.isJsonPrimitive() || !versionElement.getAsJsonPrimitive().isNumber()) {
            LatchLabel.LOGGER.warn("Missing or invalid version in {}. Falling back to compatibility mode", path);
            return -1;
        }
        return versionElement.getAsInt();
    }

    private static Category parseCategory(JsonObject categoryObject) {
        String id = asString(categoryObject.get("id"));
        String name = asString(categoryObject.get("name"));
        String iconItemIdRaw = asString(categoryObject.get("iconItemId"));

        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            return null;
        }

        Identifier iconItemId = Identifier.tryParse(iconItemIdRaw);
        if (iconItemId == null) {
            iconItemId = FALLBACK_ICON_ITEM_ID;
        }

        int color = asInt(categoryObject.get("color"), 0x8A8A8A);
        int order = asInt(categoryObject.get("order"), 0);
        boolean visible = asBoolean(categoryObject.get("visible"), true);

        try {
            return new Category(id, name, color, iconItemId, order, visible);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String asString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    private static int asInt(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    private static boolean asBoolean(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }

    private static boolean isLegacyDefaultCategorySet(List<Category> categories) {
        if (categories.size() != LEGACY_DEFAULT_CATEGORY_IDS.size()) {
            return false;
        }

        for (Category category : categories) {
            if (!LEGACY_DEFAULT_CATEGORY_IDS.contains(category.id())) {
                return false;
            }
        }
        return true;
    }

    private static String remapLegacyCategoryId(String categoryId) {
        if (categoryId == null) {
            return null;
        }
        return LEGACY_CATEGORY_ID_REMAP.getOrDefault(categoryId, categoryId);
    }
}
