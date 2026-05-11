package com.latchandlabel.client.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Manages persistence of categories, tags, and item-category overrides to disk.
 * Data is scoped per-world/server and saved with a debounce to avoid excessive I/O.
 * Handles legacy migration from older flat-file formats to the current scoped layout.
 */
public final class ClientDataManager implements AutoCloseable {
    private static final int CURRENT_VERSION = 1;
    private static final long SAVE_DEBOUNCE_MS = 1_000L;
    private static final Identifier FALLBACK_ICON_ITEM_ID = Objects.requireNonNull(
            Identifier.tryParse("minecraft:stone"),
            "Invalid fallback identifier"
    );
    private static final String SCOPES_DIR_NAME = "scopes";
    private static final String TAGS_FILE_NAME = "tags.json";
    private static final String CATEGORIES_AND_OVERRIDES_FILE_NAME = "categories_and_overrides.json";

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
    private final ItemCategoryMappingService itemCategoryMappingService;
    private final Path configDir;
    private final Path scopesDir;
    private final Path legacyCategoriesFilePath;
    private final Path legacyTagsFilePath;
    private final Path legacyOverridesFilePath;
    private final ScheduledExecutorService saveExecutor;
    private final Object saveLock = new Object();
    private final Object flushLock = new Object();

    private String activeScopeId = TagStore.DEFAULT_SCOPE_ID;
    private List<String> activeFallbackReadScopeIds = List.of();
    private ScheduledFuture<?> pendingSave;
    private boolean initialized;
    private boolean closed;
    private boolean suppressSaveScheduling;

    public ClientDataManager(
            CategoryStore categoryStore,
            TagStore tagStore,
            ItemCategoryMappingService itemCategoryMappingService
    ) {
        this(
                categoryStore,
                tagStore,
                itemCategoryMappingService,
                FabricLoader.getInstance().getConfigDir().resolve(LatchLabel.MOD_ID)
        );
    }

    ClientDataManager(
            CategoryStore categoryStore,
            TagStore tagStore,
            ItemCategoryMappingService itemCategoryMappingService,
            Path configDir
    ) {
        this.categoryStore = Objects.requireNonNull(categoryStore, "categoryStore");
        this.tagStore = Objects.requireNonNull(tagStore, "tagStore");
        this.itemCategoryMappingService = Objects.requireNonNull(itemCategoryMappingService, "itemCategoryMappingService");

        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.scopesDir = configDir.resolve(SCOPES_DIR_NAME);
        this.legacyCategoriesFilePath = configDir.resolve("categories.json");
        this.legacyTagsFilePath = configDir.resolve("tags.json");
        this.legacyOverridesFilePath = configDir.resolve("item_categories_overrides.json");

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
            Files.createDirectories(scopesDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create config directory: " + configDir, e);
        }

        loadActiveScopeData();

        categoryStore.setChangeListener(this::scheduleSave);
        tagStore.setChangeListener(this::scheduleSave);
        itemCategoryMappingService.setChangeListener(this::scheduleSave);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownFlush, "latchlabel-save-shutdown"));
        LatchLabel.LOGGER.info("Loaded scoped client data from {}", scopesDir);
    }

    public void setActiveScopeId(String scopeId) {
        setActiveScopeId(scopeId, List.of());
    }

    public void setActiveScopeId(String scopeId, List<String> fallbackReadScopeIds) {
        String normalizedScopeId = normalizeScopeId(scopeId);
        List<String> normalizedFallbackScopeIds = normalizeFallbackScopeIds(normalizedScopeId, fallbackReadScopeIds);

        synchronized (saveLock) {
            if (closed || !initialized) {
                return;
            }
            if (Objects.equals(activeScopeId, normalizedScopeId)
                    && Objects.equals(activeFallbackReadScopeIds, normalizedFallbackScopeIds)) {
                LatchLabel.LOGGER.debug("[DataManager] setActiveScopeId: no change (scope={})", normalizedScopeId);
                return;
            }
            if (Objects.equals(activeScopeId, normalizedScopeId)) {
                LatchLabel.LOGGER.debug("[DataManager] setActiveScopeId: fallbacks updated for scope={}", normalizedScopeId);
                activeFallbackReadScopeIds = normalizedFallbackScopeIds;
                tagStore.setActiveScopeId(activeScopeId, activeFallbackReadScopeIds);
                return;
            }
            LatchLabel.LOGGER.info("[DataManager] scope changing: {} -> {} (fallbacks={})",
                    activeScopeId, normalizedScopeId, normalizedFallbackScopeIds.size());
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }

        flushNow();
        synchronized (saveLock) {
            activeScopeId = normalizedScopeId;
            activeFallbackReadScopeIds = normalizedFallbackScopeIds;
        }
        runWithSaveSchedulingSuppressed(this::loadActiveScopeData);
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
        synchronized (flushLock) {
            String scopeId;
            boolean hadPending;
            synchronized (saveLock) {
                if (closed) {
                    return;
                }
                hadPending = pendingSave != null;
                if (pendingSave != null) {
                    pendingSave.cancel(false);
                    pendingSave = null;
                }
                scopeId = activeScopeId;
            }

            PersistenceSnapshot snapshot = snapshotForScope(scopeId);
            LatchLabel.LOGGER.debug("[DataManager] flushNow scope={} cancelledPending={}", scopeId, hadPending);
            writeCategoriesAndOverrides(snapshot);
            writeTags(snapshot);
        }
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
        runWithSaveSchedulingSuppressed(this::loadActiveScopeData);
    }

    public void reloadCategoriesFromDisk() {
        synchronized (saveLock) {
            if (closed || !initialized) {
                return;
            }
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        runWithSaveSchedulingSuppressed(this::loadCategoriesAndOverrides);
    }

    public void reloadTagsFromDisk() {
        synchronized (saveLock) {
            if (closed || !initialized) {
                return;
            }
            if (pendingSave != null) {
                pendingSave.cancel(false);
                pendingSave = null;
            }
        }
        runWithSaveSchedulingSuppressed(this::loadTags);
    }

    @Override
    public void close() {
        synchronized (flushLock) {
            String scopeId;
            synchronized (saveLock) {
                if (closed) {
                    return;
                }
                if (pendingSave != null) {
                    pendingSave.cancel(false);
                    pendingSave = null;
                }
                scopeId = activeScopeId;
                closed = true;
            }

            PersistenceSnapshot snapshot = snapshotForScope(scopeId);
            writeCategoriesAndOverrides(snapshot);
            writeTags(snapshot);
        }
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

    private void loadActiveScopeData() {
        String scopeId;
        List<String> fallbackIds;
        synchronized (saveLock) {
            scopeId = activeScopeId;
            fallbackIds = activeFallbackReadScopeIds;
        }
        tagStore.setActiveScopeId(scopeId, fallbackIds);
        loadCategoriesAndOverrides();
        loadTags();
    }

    private void loadCategoriesAndOverrides() {
        Path filePath = categoriesAndOverridesFilePathForScope(activeScopeId);

        if (!Files.exists(filePath)) {
            if (TagStore.DEFAULT_SCOPE_ID.equals(activeScopeId)
                    && (Files.exists(legacyCategoriesFilePath) || Files.exists(legacyOverridesFilePath))) {
                LatchLabel.LOGGER.info("[DataManager] Migrating legacy categories for scope {}", activeScopeId);
                loadLegacyCategoriesAndOverrides();
                writeCategoriesAndOverrides(snapshotForScope(activeScopeId));
                return;
            }

            for (String fallbackScopeId : activeFallbackReadScopeIds) {
                Path fallbackFilePath = categoriesAndOverridesFilePathForScope(fallbackScopeId);
                if (!Files.exists(fallbackFilePath)) {
                    continue;
                }
                LatchLabel.LOGGER.info("[DataManager] Migrating categories from fallback scope {} to primary scope {}",
                        fallbackScopeId, activeScopeId);
                loadCategoriesAndOverridesFromFile(fallbackFilePath);
                writeCategoriesAndOverrides(snapshotForScope(activeScopeId));
                return;
            }

            LatchLabel.LOGGER.info("[DataManager] No categories file for scope {}, creating defaults", activeScopeId);
            categoryStore.replaceAll(DefaultCategories.create());
            itemCategoryMappingService.applyScopedOverrides(Map.of(), Set.of());
            writeCategoriesAndOverrides(snapshotForScope(activeScopeId));
            return;
        }

        loadCategoriesAndOverridesFromFile(filePath);
    }

    private void loadCategoriesAndOverridesFromFile(Path filePath) {
        JsonObject root;
        try {
            root = readJsonObject(filePath);
        } catch (IllegalStateException e) {
            Path backup = backupCorruptFile(filePath);
            LatchLabel.LOGGER.warn("Invalid scoped categories file {}, backed up to {}, using defaults", filePath, backup, e);
            categoryStore.replaceAll(DefaultCategories.create());
            itemCategoryMappingService.applyScopedOverrides(Map.of(), Set.of());
            writeCategoriesAndOverrides(snapshotForScope(activeScopeId));
            return;
        }
        int version = parseVersion(root, filePath);

        List<Category> categories = parseCategories(root.get("categories"));
        if (categories.isEmpty()) {
            categories = DefaultCategories.create();
        }
        if (isLegacyDefaultCategorySet(categories)) {
            categories = DefaultCategories.create();
        }

        ParsedOverrides parsedOverrides = parseItemOverrides(root.get("itemOverrides"));

        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn(
                    "Unsupported categories_and_overrides version {} in {}; using compatibility load path",
                    version,
                    filePath
            );
        }

        categoryStore.replaceAll(categories);
        itemCategoryMappingService.applyScopedOverrides(parsedOverrides.overrides(), parsedOverrides.blocked());
    }

    private void loadLegacyCategoriesAndOverrides() {
        List<Category> categories = readLegacyCategories();
        if (categories.isEmpty()) {
            categories = DefaultCategories.create();
        }
        if (isLegacyDefaultCategorySet(categories)) {
            categories = DefaultCategories.create();
        }

        ParsedOverrides overrides = readLegacyOverrides();
        categoryStore.replaceAll(categories);
        itemCategoryMappingService.applyScopedOverrides(overrides.overrides(), overrides.blocked());
    }

    private void loadTags() {
        LinkedHashSet<String> readScopes = new LinkedHashSet<>();
        readScopes.add(activeScopeId);
        readScopes.addAll(activeFallbackReadScopeIds);

        Map<String, Map<ChestKey, String>> tagsByScope = new LinkedHashMap<>();
        Map<String, String> lastUsedByScope = new HashMap<>();
        boolean loadedAnyScope = false;
        boolean loadedActiveScope = false;

        for (String scopeId : readScopes) {
            ScopedTags scopedTags = loadScopedTags(scopeId);
            if (scopedTags == null) {
                continue;
            }
            loadedAnyScope = true;
            if (Objects.equals(scopeId, activeScopeId)) {
                loadedActiveScope = true;
            }
            tagsByScope.put(scopeId, new HashMap<>(scopedTags.tags()));
            if (scopedTags.lastUsedCategoryId() != null && !scopedTags.lastUsedCategoryId().isBlank()) {
                lastUsedByScope.put(scopeId, scopedTags.lastUsedCategoryId());
            }
        }

        if (!loadedAnyScope && Files.exists(legacyTagsFilePath)) {
            ScopedTags legacyTags = loadLegacyTags(activeScopeId);
            tagsByScope.put(activeScopeId, new HashMap<>(legacyTags.tags()));
            if (legacyTags.lastUsedCategoryId() != null && !legacyTags.lastUsedCategoryId().isBlank()) {
                lastUsedByScope.put(activeScopeId, legacyTags.lastUsedCategoryId());
            }
            tagStore.replaceAllScopes(tagsByScope, lastUsedByScope, activeScopeId, activeFallbackReadScopeIds);
            writeTags(snapshotForScope(activeScopeId));
            return;
        }

        boolean activeHasData = hasScopeData(tagsByScope.get(activeScopeId), lastUsedByScope.get(activeScopeId));
        boolean migratedFromFallback = false;
        if (!activeHasData && !loadedActiveScope) {
            for (String fallbackScopeId : activeFallbackReadScopeIds) {
                Map<ChestKey, String> fallbackTags = tagsByScope.get(fallbackScopeId);
                String fallbackLastUsed = lastUsedByScope.get(fallbackScopeId);
                if (!hasScopeData(fallbackTags, fallbackLastUsed)) {
                    continue;
                }
                tagsByScope.put(activeScopeId, fallbackTags == null ? new HashMap<>() : new HashMap<>(fallbackTags));
                if (fallbackLastUsed != null && !fallbackLastUsed.isBlank()) {
                    lastUsedByScope.put(activeScopeId, fallbackLastUsed);
                }
                migratedFromFallback = true;
                LatchLabel.LOGGER.info("[DataManager] Migrating {} tags from fallback scope {} to primary scope {}",
                        fallbackTags == null ? 0 : fallbackTags.size(), fallbackScopeId, activeScopeId);
                break;
            }
        }

        tagsByScope.computeIfAbsent(activeScopeId, unused -> new HashMap<>());
        tagStore.replaceAllScopes(tagsByScope, lastUsedByScope, activeScopeId, activeFallbackReadScopeIds);
        if (migratedFromFallback || !loadedAnyScope) {
            writeTags(snapshotForScope(activeScopeId));
        }
    }

    private ScopedTags loadScopedTags(String scopeId) {
        Path filePath = tagsFilePathForScope(scopeId);
        if (!Files.exists(filePath)) {
            return null;
        }

        JsonObject root;
        try {
            root = readJsonObject(filePath);
        } catch (IllegalStateException e) {
            LatchLabel.LOGGER.warn("Invalid scoped tags file {}, using empty state", filePath, e);
            return ScopedTags.empty();
        }
        int version = parseVersion(root, filePath);

        Map<ChestKey, String> parsedTags = parseTagsObject(root.get("tags"), filePath);
        String lastUsedCategoryId = remapLegacyCategoryId(asString(root.get("lastUsedCategoryId")));

        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn(
                    "Unsupported tags version {} in {}; using compatibility load path",
                    version,
                    filePath
            );
        }
        return new ScopedTags(parsedTags, lastUsedCategoryId);
    }

    private ScopedTags loadLegacyTags(String scopeId) {
        JsonObject root;
        try {
            root = readJsonObject(legacyTagsFilePath);
        } catch (IllegalStateException e) {
            LatchLabel.LOGGER.warn("Invalid legacy tags file {}, using empty state", legacyTagsFilePath, e);
            return ScopedTags.empty();
        }

        JsonElement scopesElement = root.get("scopes");
        if (scopesElement != null && scopesElement.isJsonObject()) {
            JsonObject scopes = scopesElement.getAsJsonObject();
            JsonObject selectedScope = null;
            JsonElement selected = scopes.get(scopeId);
            if (selected != null && selected.isJsonObject()) {
                selectedScope = selected.getAsJsonObject();
            } else {
                JsonElement global = scopes.get(TagStore.DEFAULT_SCOPE_ID);
                if (global != null && global.isJsonObject()) {
                    selectedScope = global.getAsJsonObject();
                }
            }
            if (selectedScope == null) {
                return ScopedTags.empty();
            }

            Map<ChestKey, String> parsedTags = parseTagsObject(selectedScope.get("tags"), legacyTagsFilePath);
            String lastUsedCategoryId = remapLegacyCategoryId(asString(selectedScope.get("lastUsedCategoryId")));
            return new ScopedTags(parsedTags, lastUsedCategoryId);
        }

        Map<ChestKey, String> parsedTags = parseTagsObject(root.get("tags"), legacyTagsFilePath);
        String lastUsedCategoryId = remapLegacyCategoryId(asString(root.get("lastUsedCategoryId")));
        return new ScopedTags(parsedTags, lastUsedCategoryId);
    }

    private PersistenceSnapshot snapshotForScope(String scopeId) {
        String normalizedScopeId = normalizeScopeId(scopeId);
        return new PersistenceSnapshot(
                normalizedScopeId,
                categoryStore.listAll(),
                itemCategoryMappingService.snapshotOverrides(),
                itemCategoryMappingService.snapshotBlockedMappings(),
                tagStore.snapshotTagsForScope(normalizedScopeId),
                tagStore.snapshotLastUsedCategoryIdForScope(normalizedScopeId).orElse(null)
        );
    }

    private void writeCategoriesAndOverrides(PersistenceSnapshot snapshot) {
        Path filePath = categoriesAndOverridesFilePathForScope(snapshot.scopeId());
        ensureScopeDirectory(filePath.getParent());
        LatchLabel.LOGGER.debug("[DataManager] writeCategoriesAndOverrides: {} categories -> {}",
                snapshot.categories().size(), filePath);

        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);

        var serializedCategories = new com.google.gson.JsonArray();
        for (Category category : snapshot.categories()) {
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

        JsonObject itemOverrides = new JsonObject();
        for (Identifier blockedItemId : snapshot.blockedItemMappings()) {
            itemOverrides.add(blockedItemId.toString(), JsonNull.INSTANCE);
        }
        for (Map.Entry<Identifier, String> entry : snapshot.itemOverrides().entrySet()) {
            itemOverrides.addProperty(entry.getKey().toString(), entry.getValue());
        }
        root.add("itemOverrides", itemOverrides);

        writeJsonObject(filePath, root);
    }

    private void writeTags(PersistenceSnapshot snapshot) {
        Path filePath = tagsFilePathForScope(snapshot.scopeId());
        ensureScopeDirectory(filePath.getParent());

        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);

        LatchLabel.LOGGER.debug("[DataManager] writeTags: {} tags -> {}", snapshot.tags().size(), filePath);
        JsonObject tagsObject = new JsonObject();
        for (Map.Entry<ChestKey, String> entry : snapshot.tags().entrySet()) {
            tagsObject.addProperty(entry.getKey().toStringKey(), entry.getValue());
        }
        root.add("tags", tagsObject);

        String lastUsed = snapshot.lastUsedCategoryId();
        if (lastUsed != null && !lastUsed.isBlank()) {
            root.addProperty("lastUsedCategoryId", lastUsed);
        }

        writeJsonObject(filePath, root);
    }

    private List<Category> readLegacyCategories() {
        if (!Files.exists(legacyCategoriesFilePath)) {
            return DefaultCategories.create();
        }
        JsonObject root;
        try {
            root = readJsonObject(legacyCategoriesFilePath);
        } catch (IllegalStateException e) {
            LatchLabel.LOGGER.warn("Invalid legacy categories file {}, using defaults", legacyCategoriesFilePath, e);
            return DefaultCategories.create();
        }
        return parseCategories(root.get("categories"));
    }

    private ParsedOverrides readLegacyOverrides() {
        if (!Files.exists(legacyOverridesFilePath)) {
            return ParsedOverrides.empty();
        }
        JsonObject root;
        try {
            root = readJsonObject(legacyOverridesFilePath);
        } catch (IllegalStateException e) {
            LatchLabel.LOGGER.warn("Invalid legacy overrides file {}, using empty overrides", legacyOverridesFilePath, e);
            return ParsedOverrides.empty();
        }
        JsonElement source = root.get("itemOverrides");
        if (source == null) {
            source = root.get("overrides");
        }
        return parseItemOverrides(source);
    }

    private List<Category> parseCategories(JsonElement categoriesElement) {
        List<Category> categories = new ArrayList<>();
        if (categoriesElement == null || !categoriesElement.isJsonArray()) {
            return categories;
        }
        for (JsonElement element : categoriesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            Category category = parseCategory(element.getAsJsonObject());
            if (category != null) {
                categories.add(category);
            }
        }
        return categories;
    }

    private Map<ChestKey, String> parseTagsObject(JsonElement tagsElement, Path sourcePath) {
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
                LatchLabel.LOGGER.warn("Skipping invalid chest key '{}' in {}", entry.getKey(), sourcePath);
            }
        }
        return parsedTags;
    }

    private ParsedOverrides parseItemOverrides(JsonElement overridesElement) {
        if (overridesElement == null || !overridesElement.isJsonObject()) {
            return ParsedOverrides.empty();
        }

        Map<Identifier, String> parsedOverrides = new LinkedHashMap<>();
        Set<Identifier> blocked = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : overridesElement.getAsJsonObject().entrySet()) {
            Identifier itemId = Identifier.tryParse(entry.getKey());
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                continue;
            }
            if (entry.getValue().isJsonNull()) {
                blocked.add(itemId);
                continue;
            }
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                continue;
            }

            String categoryId = ItemCategoryMappingService.normalizeCategoryId(entry.getValue().getAsString());
            categoryId = remapLegacyCategoryId(categoryId);
            if (categoryId == null || categoryId.isBlank()) {
                continue;
            }
            parsedOverrides.put(itemId, categoryId);
        }
        return new ParsedOverrides(Map.copyOf(parsedOverrides), Set.copyOf(blocked));
    }

    private static Path backupCorruptFile(Path path) {
        Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis() + ".bak");
        try {
            Files.copy(path, backup);
        } catch (IOException ex) {
            LatchLabel.LOGGER.warn("Could not back up corrupt file {}: {}", path, ex.getMessage());
            return path;
        }
        return backup;
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
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing json file: " + tmp, e);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // ATOMIC_MOVE not supported on this filesystem; fall back to non-atomic replace
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Failed renaming temp file to: " + path, e);
            }
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

    private static boolean hasScopeData(Map<ChestKey, String> tags, String lastUsedCategoryId) {
        return (tags != null && !tags.isEmpty()) || (lastUsedCategoryId != null && !lastUsedCategoryId.isBlank());
    }

    private Path scopeDirectory(String scopeId) {
        return scopesDir.resolve(normalizeScopeId(scopeId));
    }

    private Path tagsFilePathForScope(String scopeId) {
        return scopeDirectory(scopeId).resolve(TAGS_FILE_NAME);
    }

    private Path categoriesAndOverridesFilePathForScope(String scopeId) {
        return scopeDirectory(scopeId).resolve(CATEGORIES_AND_OVERRIDES_FILE_NAME);
    }

    private static void ensureScopeDirectory(Path scopeDir) {
        try {
            Files.createDirectories(scopeDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create scope directory: " + scopeDir, e);
        }
    }

    private static String normalizeScopeId(String scopeId) {
        return ScopeUtil.normalizeScopeId(scopeId, TagStore.DEFAULT_SCOPE_ID);
    }

    private static List<String> normalizeFallbackScopeIds(String activeScopeId, List<String> fallbackReadScopeIds) {
        if (fallbackReadScopeIds == null || fallbackReadScopeIds.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String fallbackScopeId : fallbackReadScopeIds) {
            String normalizedScopeId = normalizeScopeId(fallbackScopeId);
            if (Objects.equals(activeScopeId, normalizedScopeId)) {
                continue;
            }
            normalized.add(normalizedScopeId);
        }
        return List.copyOf(normalized);
    }

    private record ParsedOverrides(Map<Identifier, String> overrides, Set<Identifier> blocked) {
        private static ParsedOverrides empty() {
            return new ParsedOverrides(Map.of(), Set.of());
        }
    }

    private record ScopedTags(Map<ChestKey, String> tags, String lastUsedCategoryId) {
        private static ScopedTags empty() {
            return new ScopedTags(Map.of(), null);
        }
    }

    private record PersistenceSnapshot(
            String scopeId,
            List<Category> categories,
            Map<Identifier, String> itemOverrides,
            Set<Identifier> blockedItemMappings,
            Map<ChestKey, String> tags,
            String lastUsedCategoryId
    ) {
    }
}
