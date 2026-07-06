package com.latchandlabel.client.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.model.ChestKey;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Persistent, per-scope index of the item types last seen inside each container the
 * player has opened. The client only ever knows a chest's contents when it has opened
 * that chest, so this index is the authoritative source for content-based {@code /find}.
 *
 * <p>Observed data is disposable — reopening a chest rebuilds it — so this store keeps its
 * own {@code scopes/<scope>/observed.json} files rather than going through the versioned,
 * migrated tag/category persistence. Bounded to {@link #MAX_PER_SCOPE} chests per scope,
 * evicting the least-recently-recorded.
 */
public final class ObservedIndexStore {
    private static final int MAX_PER_SCOPE = 4096;
    private static final String SCOPES_DIR_NAME = "scopes";
    private static final String FILE_NAME = "observed.json";
    private static final int CURRENT_VERSION = 1;
    // ponytail: 2s save throttle + shutdown-hook flush; losing <2s of observations just means reopening a chest.
    private static final long SAVE_THROTTLE_MS = 2_000L;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path scopesDir;
    private final Supplier<String> activeScopeIdSupplier;
    private final Map<String, ScopeIndex> byScope = new HashMap<>();

    public ObservedIndexStore(Supplier<String> activeScopeIdSupplier) {
        this.activeScopeIdSupplier = activeScopeIdSupplier;
        this.scopesDir = FabricLoader.getInstance().getConfigDir()
                .resolve(LatchLabel.MOD_ID).resolve(SCOPES_DIR_NAME);
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushAll, "latchlabel-observed-flush"));
    }

    public synchronized void record(ChestKey key, Set<Item> items) {
        if (key == null || items == null || items.isEmpty()) {
            return;
        }
        // Fresh observation always clears any staleness flag.
        ScopeIndex index = scope(activeScopeId());
        index.put(key, new Entry(Set.copyOf(items), System.currentTimeMillis(), 0L));
    }

    /**
     * Flags a known chest as possibly-stale: someone opened it since we last saw its contents,
     * so our stored items may be out of date (but might still be accurate). Fed by nearby
     * chest-open block events. No-op if the key isn't known or is already stale.
     */
    public synchronized void markStale(ChestKey key) {
        if (key != null) {
            scope(activeScopeId()).markStale(key);
        }
    }

    public synchronized boolean isStale(ChestKey key) {
        return key != null && scope(activeScopeId()).isStale(key);
    }

    /** Chests recorded in the active scope. */
    public synchronized Set<ChestKey> keys() {
        return Set.copyOf(scope(activeScopeId()).entries.keySet());
    }

    public synchronized Optional<Set<Item>> itemsFor(ChestKey key) {
        Entry entry = scope(activeScopeId()).entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.items());
    }

    public synchronized void flushAll() {
        for (ScopeIndex index : byScope.values()) {
            index.saveNow();
        }
    }

    private String activeScopeId() {
        String scopeId = activeScopeIdSupplier.get();
        return scopeId == null || scopeId.isBlank() ? TagStore.DEFAULT_SCOPE_ID : scopeId;
    }

    private ScopeIndex scope(String scopeId) {
        return byScope.computeIfAbsent(scopeId, id -> ScopeIndex.load(scopesDir.resolve(id).resolve(FILE_NAME)));
    }

    private record Entry(Set<Item> items, long observedAt, long staleSince) {
        boolean stale() {
            return staleSince != 0L;
        }
    }

    private static final class ScopeIndex {
        private final Path file;
        private final LinkedHashMap<ChestKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChestKey, Entry> eldest) {
                return size() > MAX_PER_SCOPE;
            }
        };
        private long lastSaveMs = 0L;
        private boolean dirty = false;

        private ScopeIndex(Path file) {
            this.file = file;
        }

        private static ScopeIndex load(Path file) {
            ScopeIndex index = new ScopeIndex(file);
            index.readFile();
            return index;
        }

        private void put(ChestKey key, Entry entry) {
            entries.remove(key); // reinsert at tail so re-observed chests count as most-recent
            entries.put(key, entry);
            dirty = true;
            maybeSave();
        }

        private void markStale(ChestKey key) {
            Entry e = entries.get(key);
            if (e == null || e.stale()) {
                return;
            }
            // In-place value update: keeps LRU position (unlike put's remove+reinsert).
            entries.put(key, new Entry(e.items(), e.observedAt(), System.currentTimeMillis()));
            dirty = true;
            maybeSave();
        }

        private boolean isStale(ChestKey key) {
            Entry e = entries.get(key);
            return e != null && e.stale();
        }

        private void maybeSave() {
            if (System.currentTimeMillis() - lastSaveMs >= SAVE_THROTTLE_MS) {
                saveNow();
            }
        }

        private void readFile() {
            if (!Files.exists(file)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed == null || !parsed.isJsonObject()) {
                    return;
                }
                JsonObject chests = parsed.getAsJsonObject().getAsJsonObject("c");
                if (chests == null) {
                    return;
                }
                for (Map.Entry<String, JsonElement> e : chests.entrySet()) {
                    ChestKey key;
                    try {
                        key = ChestKey.fromStringKey(e.getKey());
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (!e.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = e.getValue().getAsJsonObject();
                    long t = obj.has("t") ? obj.get("t").getAsLong() : System.currentTimeMillis();
                    long staleSince = obj.has("s") ? obj.get("s").getAsLong() : 0L;
                    Set<Item> items = new LinkedHashSet<>();
                    JsonArray arr = obj.getAsJsonArray("i");
                    if (arr != null) {
                        for (JsonElement idEl : arr) {
                            Identifier id = Identifier.tryParse(idEl.getAsString());
                            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                                continue;
                            }
                            Item item = BuiltInRegistries.ITEM.getValue(id);
                            if (item != Items.AIR) {
                                items.add(item);
                            }
                        }
                    }
                    if (!items.isEmpty()) {
                        entries.put(key, new Entry(Set.copyOf(items), t, staleSince));
                    }
                }
            } catch (Exception ex) {
                LatchLabel.LOGGER.warn("Failed reading observed index {}: {}", file, ex.getMessage());
            }
        }

        private void saveNow() {
            if (!dirty) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("v", CURRENT_VERSION);
            JsonObject chests = new JsonObject();
            for (Map.Entry<ChestKey, Entry> e : entries.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("t", e.getValue().observedAt());
                if (e.getValue().stale()) {
                    obj.addProperty("s", e.getValue().staleSince());
                }
                JsonArray arr = new JsonArray();
                for (Item item : e.getValue().items()) {
                    arr.add(BuiltInRegistries.ITEM.getKey(item).toString());
                }
                obj.add("i", arr);
                chests.add(e.getKey().toStringKey(), obj);
            }
            root.add("c", chests);

            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, writer);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                lastSaveMs = System.currentTimeMillis();
                dirty = false;
            } catch (IOException ex) {
                LatchLabel.LOGGER.warn("Failed writing observed index {}: {}", file, ex.getMessage());
            }
        }
    }
}
