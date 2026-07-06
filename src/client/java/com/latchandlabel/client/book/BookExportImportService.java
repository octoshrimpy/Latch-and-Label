package com.latchandlabel.client.book;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.find.FindSettings;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BookExportImportService {
    private static final String HEADER = "Latch & Label\n";
    private static final int MAX_PAGE_LENGTH = 1024;
    // Vanilla kicks on rapid ServerboundEditBookPacket ("book edited too quickly"); throttle writes.
    private static final long MIN_WRITE_INTERVAL_MS = 2000L;
    private static long lastWriteMs = 0L;
    private static final int MAX_PAGES = 100;
    private static final int CURRENT_VERSION = 2;

    private static final Gson COMPACT_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private BookExportImportService() {
    }

    public static ExportResult exportToHeldBook(
            Minecraft client,
            TagStore tagStore,
            CategoryStore categoryStore,
            ItemCategoryMappingService mappingService
    ) {
        return exportToHeldBookInternal(client, tagStore, categoryStore, mappingService, false, null);
    }

    public static ExportResult exportNearbyToHeldBook(
            Minecraft client,
            TagStore tagStore,
            CategoryStore categoryStore,
            ItemCategoryMappingService mappingService
    ) {
        return exportToHeldBookInternal(client, tagStore, categoryStore, mappingService, true, null);
    }

    /** Nearby export seeded at a specific chest rather than the player. */
    public static ExportResult exportNearbyToHeldBook(
            Minecraft client,
            TagStore tagStore,
            CategoryStore categoryStore,
            ItemCategoryMappingService mappingService,
            BlockPos origin
    ) {
        return exportToHeldBookInternal(client, tagStore, categoryStore, mappingService, true,
                origin == null ? null : Vec3.atCenterOf(origin));
    }

    /** The tagged chests that a nearby export seeded at {@code origin} would include. */
    public static Set<ChestKey> nearbyTaggedKeys(Minecraft client, BlockPos origin) {
        if (client == null || client.player == null || client.level == null) {
            return Set.of();
        }
        return filterTagsToNearby(client, LatchLabelClientState.tagStore().snapshotTags(),
                origin == null ? null : Vec3.atCenterOf(origin)).keySet();
    }

    private static ExportResult exportToHeldBookInternal(
            Minecraft client,
            TagStore tagStore,
            CategoryStore categoryStore,
            ItemCategoryMappingService mappingService,
            boolean nearby,
            Vec3 origin
    ) {
        if (client.player == null) {
            return ExportResult.failure(Component.translatable("latchlabel.book.error_no_player"));
        }
        if (nearby && client.level == null) {
            return ExportResult.failure(Component.translatable("latchlabel.find.error_world_unavailable"));
        }

        ItemStack heldStack = client.player.getMainHandItem();
        if (!heldStack.is(Items.WRITABLE_BOOK)) {
            return ExportResult.failure(Component.translatable("latchlabel.book.error_no_writable_book"));
        }

        Map<ChestKey, String> allTags = tagStore.snapshotTags();
        Map<ChestKey, String> tags = nearby ? filterTagsToNearby(client, allTags, origin) : allTags;
        String lastUsedCategoryId = tagStore.getLastUsedCategoryId().orElse(null);
        List<Category> categories = categoryStore.listAll();
        Map<Identifier, String> overrides = mappingService.snapshotOverrides();
        Set<Identifier> blocked = mappingService.snapshotBlockedMappings();

        String json = serializeToJson(tags, lastUsedCategoryId, categories, overrides, blocked, nearby);
        String payload = HEADER + json;

        List<String> pages = splitIntoPages(payload);
        if (pages.size() > MAX_PAGES) {
            return ExportResult.failure(Component.translatable("latchlabel.book.error_data_too_large",
                    String.valueOf(pages.size()), String.valueOf(MAX_PAGES)));
        }

        // Skip the write entirely if the book already holds exactly this payload (idempotent + avoids the kick).
        List<String> currentPages = extractPages(heldStack);
        if (currentPages != null && currentPages.equals(pages)) {
            return ExportResult.success(Component.translatable("latchlabel.book.export_up_to_date"),
                    pages.size(), tags.size(), categories.size());
        }
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastWriteMs < MIN_WRITE_INTERVAL_MS) {
            return ExportResult.failure(Component.translatable("latchlabel.book.export_too_fast"));
        }

        int slot = client.player.getInventory().getSelectedSlot();
        // ponytail: empty title = server saves draft (writable_book, overwritable) instead of signing to immutable written_book.
        client.getConnection().send(
                new ServerboundEditBookPacket(slot, pages, Optional.empty())
        );
        lastWriteMs = nowMs;

        int tagCount = tags.size();
        int categoryCount = categories.size();
        if (nearby) {
            return ExportResult.success(
                    Component.translatable("latchlabel.book.export_nearby_success",
                            String.valueOf(tagCount),
                            String.valueOf(allTags.size() - tagCount),
                            String.valueOf(pages.size())),
                    pages.size(), tagCount, categoryCount
            );
        }
        return ExportResult.success(
                Component.translatable("latchlabel.book.export_success",
                        String.valueOf(pages.size()), String.valueOf(tagCount), String.valueOf(categoryCount)),
                pages.size(), tagCount, categoryCount
        );
    }

    public static ImportResult importFromHeldBook(
            Minecraft client,
            TagStore tagStore,
            CategoryStore categoryStore,
            ItemCategoryMappingService mappingService
    ) {
        if (client.player == null) {
            return ImportResult.failure(Component.translatable("latchlabel.book.error_no_player"));
        }

        ItemStack heldStack = client.player.getMainHandItem();
        List<String> pages = extractPages(heldStack);
        if (pages == null) {
            return ImportResult.failure(Component.translatable("latchlabel.book.error_no_book"));
        }
        if (pages.isEmpty()) {
            return ImportResult.failure(Component.translatable("latchlabel.book.error_book_empty"));
        }

        String fullText = String.join("", pages);
        if (!fullText.startsWith(HEADER)) {
            return ImportResult.failure(Component.translatable("latchlabel.book.error_invalid_header"));
        }

        String json = fullText.substring(HEADER.length());
        ParsedBook parsed;
        try {
            parsed = deserializeFromJson(json);
        } catch (Exception e) {
            LatchLabel.LOGGER.warn("Failed to parse book data", e);
            return ImportResult.failure(Component.translatable("latchlabel.book.error_invalid_data", e.getMessage()));
        }

        BookData bookData = parsed.data();
        int nearbySkipped = 0;
        if (parsed.nearby()) {
            FilteredTags filteredTags = filterTagsToExistingContainers(client, bookData.tags());
            nearbySkipped = filteredTags.skippedCount();
            bookData = new BookData(
                    filteredTags.tags(),
                    bookData.lastUsedCategoryId(),
                    bookData.categories(),
                    bookData.overrides(),
                    bookData.blocked()
            );
        }

        int tagsImported = mergeData(bookData, tagStore, categoryStore, mappingService);
        LatchLabelClientState.dataManager().scheduleSave();

        Component message = parsed.nearby()
                ? Component.translatable("latchlabel.book.import_nearby_success",
                        String.valueOf(tagsImported), String.valueOf(nearbySkipped))
                : Component.translatable("latchlabel.book.import_success",
                        String.valueOf(tagsImported), String.valueOf(bookData.categories.size()));
        if (parsed.skippedChestKeys() > 0) {
            LatchLabel.LOGGER.warn("Book import skipped {} invalid chest key(s)", parsed.skippedChestKeys());
            message = message.copy().append(Component.translatable("latchlabel.book.import_skipped_keys",
                    String.valueOf(parsed.skippedChestKeys())));
        }
        return ImportResult.success(message, tagsImported, bookData.categories.size());
    }

    public static boolean isLatchLabelBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        if (stack.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content == null || content.pages().isEmpty()) {
                return false;
            }
            String firstPage = content.pages().getFirst().raw().getString();
            return firstPage.startsWith("Latch & Label");
        }

        if (stack.is(Items.WRITABLE_BOOK)) {
            WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (content == null || content.pages().isEmpty()) {
                return false;
            }
            String firstPage = content.pages().getFirst().raw();
            return firstPage.startsWith("Latch & Label");
        }

        return false;
    }

    public static int countCurrentTags() {
        return LatchLabelClientState.tagStore().snapshotTags().size();
    }

    public static int countCurrentCategories() {
        return LatchLabelClientState.categoryStore().listAll().size();
    }

    public static int countCurrentNearbyTags(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return 0;
        }
        return filterTagsToNearby(client, LatchLabelClientState.tagStore().snapshotTags(), null).size();
    }

    private static List<String> extractPages(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        if (stack.is(Items.WRITTEN_BOOK)) {
            WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (content == null) {
                return null;
            }
            List<String> pages = new ArrayList<>();
            for (Filterable<Component> page : content.pages()) {
                pages.add(page.raw().getString());
            }
            return pages;
        }

        if (stack.is(Items.WRITABLE_BOOK)) {
            WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (content == null) {
                return null;
            }
            List<String> pages = new ArrayList<>();
            for (Filterable<String> page : content.pages()) {
                pages.add(page.raw());
            }
            return pages;
        }

        return null;
    }

    private static String serializeToJson(
            Map<ChestKey, String> tags,
            String lastUsedCategoryId,
            List<Category> categories,
            Map<Identifier, String> overrides,
            Set<Identifier> blocked,
            boolean nearby
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("v", CURRENT_VERSION);
        if (nearby) {
            root.addProperty("n", true);
        }

        JsonObject tagsObj = new JsonObject();
        for (Map.Entry<ChestKey, String> entry : tags.entrySet()) {
            tagsObj.addProperty(entry.getKey().toStringKey(), entry.getValue());
        }
        root.add("t", tagsObj);

        if (lastUsedCategoryId != null && !lastUsedCategoryId.isBlank()) {
            root.addProperty("lu", lastUsedCategoryId);
        }

        JsonArray categoriesArr = new JsonArray();
        for (Category cat : categories) {
            JsonObject catObj = new JsonObject();
            catObj.addProperty("i", cat.id());
            catObj.addProperty("n", cat.name());
            catObj.addProperty("cl", cat.color() & 0x00FFFFFF);
            catObj.addProperty("ic", cat.iconItemId().toString());
            catObj.addProperty("o", cat.order());
            catObj.addProperty("vi", cat.visible());
            categoriesArr.add(catObj);
        }
        root.add("c", categoriesArr);

        if (!overrides.isEmpty()) {
            JsonObject overridesObj = new JsonObject();
            for (Map.Entry<Identifier, String> entry : overrides.entrySet()) {
                overridesObj.addProperty(entry.getKey().toString(), entry.getValue());
            }
            root.add("io", overridesObj);
        }

        if (!blocked.isEmpty()) {
            JsonArray blockedArr = new JsonArray();
            for (Identifier id : blocked) {
                blockedArr.add(id.toString());
            }
            root.add("bl", blockedArr);
        }

        return COMPACT_GSON.toJson(root);
    }

    private static Map<ChestKey, String> filterTagsToNearby(Minecraft client, Map<ChestKey, String> tags, Vec3 originOrNull) {
        if (client.player == null || client.level == null) {
            return Map.of();
        }

        Identifier dimensionId = McCompat.dimensionId(client.level);
        Vec3 origin = originOrNull != null ? originOrNull : client.player.position();
        int radius = FindSettings.defaultFindRadius();
        double radiusSquared = (double) radius * radius;

        Map<ChestKey, String> filtered = new LinkedHashMap<>();
        for (Map.Entry<ChestKey, String> entry : tags.entrySet()) {
            ChestKey chestKey = entry.getKey();
            if (!chestKey.dimensionId().equals(dimensionId)) {
                continue;
            }
            if (chestKey.pos().distToCenterSqr(origin) > radiusSquared) {
                continue;
            }
            filtered.put(chestKey, entry.getValue());
        }
        return filtered;
    }

    private static FilteredTags filterTagsToExistingContainers(Minecraft client, Map<ChestKey, String> tags) {
        if (client.level == null) {
            return new FilteredTags(Map.of(), tags.size());
        }

        Identifier dimensionId = McCompat.dimensionId(client.level);
        Map<ChestKey, String> filtered = new LinkedHashMap<>();
        int skippedCount = 0;

        for (Map.Entry<ChestKey, String> entry : tags.entrySet()) {
            ChestKey chestKey = entry.getKey();
            if (!chestKey.dimensionId().equals(dimensionId)) {
                skippedCount++;
                continue;
            }

            BlockEntity blockEntity = client.level.getBlockEntity(chestKey.pos());
            if (!(blockEntity instanceof Container)) {
                skippedCount++;
                continue;
            }

            filtered.put(chestKey, entry.getValue());
        }
        return new FilteredTags(filtered, skippedCount);
    }

    private record ParsedBook(BookData data, int skippedChestKeys, boolean nearby) {
    }

    private record FilteredTags(Map<ChestKey, String> tags, int skippedCount) {
    }

    private static ParsedBook deserializeFromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        Map<ChestKey, String> tags = new HashMap<>();
        int skippedChestKeys = 0;
        JsonElement tagsEl = root.get("t");
        if (tagsEl != null && tagsEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : tagsEl.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                try {
                    tags.put(ChestKey.fromStringKey(entry.getKey()), entry.getValue().getAsString());
                } catch (IllegalArgumentException e) {
                    LatchLabel.LOGGER.warn("Skipping invalid chest key '{}' from book", entry.getKey());
                    skippedChestKeys++;
                }
            }
        }

        String lastUsedCategoryId = null;
        JsonElement luEl = root.get("lu");
        if (luEl != null && luEl.isJsonPrimitive()) {
            lastUsedCategoryId = luEl.getAsString();
        }

        List<Category> categories = new ArrayList<>();
        JsonElement catsEl = root.get("c");
        if (catsEl != null && catsEl.isJsonArray()) {
            for (JsonElement el : catsEl.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                Category cat = parseCategory(el.getAsJsonObject());
                if (cat != null) {
                    categories.add(cat);
                }
            }
        }

        Map<Identifier, String> overrides = new LinkedHashMap<>();
        JsonElement ioEl = root.get("io");
        if (ioEl != null && ioEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : ioEl.getAsJsonObject().entrySet()) {
                Identifier itemId = Identifier.tryParse(entry.getKey());
                if (itemId == null) {
                    continue;
                }
                if (entry.getValue().isJsonPrimitive()) {
                    overrides.put(itemId, entry.getValue().getAsString());
                }
            }
        }

        Set<Identifier> blocked = new LinkedHashSet<>();
        JsonElement blEl = root.get("bl");
        if (blEl != null && blEl.isJsonArray()) {
            for (JsonElement el : blEl.getAsJsonArray()) {
                if (el.isJsonPrimitive()) {
                    Identifier itemId = Identifier.tryParse(el.getAsString());
                    if (itemId != null) {
                        blocked.add(itemId);
                    }
                }
            }
        }

        boolean nearby = asBoolean(root.get("n"), false);
        return new ParsedBook(new BookData(tags, lastUsedCategoryId, categories, overrides, blocked), skippedChestKeys, nearby);
    }

    private static Category parseCategory(JsonObject obj) {
        String id = asString(obj.get("i"));
        String name = asString(obj.get("n"));
        String iconItemIdRaw = asString(obj.get("ic"));

        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            return null;
        }

        Identifier iconItemId = Identifier.tryParse(iconItemIdRaw);
        if (iconItemId == null) {
            iconItemId = Identifier.tryParse("minecraft:stone");
        }

        int color = asInt(obj.get("cl"), 0x8A8A8A);
        int order = asInt(obj.get("o"), 0);
        boolean visible = asBoolean(obj.get("vi"), true);

        try {
            return new Category(id, name, color & 0x00FFFFFF, iconItemId, order, visible);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int mergeData(
            BookData bookData,
            TagStore tagStore,
            CategoryStore categoryStore,
            ItemCategoryMappingService mappingService
    ) {
        List<Category> existing = new ArrayList<>(categoryStore.listAll());
        Map<String, Integer> existingIndex = new HashMap<>();
        for (int i = 0; i < existing.size(); i++) {
            existingIndex.put(existing.get(i).id(), i);
        }
        for (Category imported : bookData.categories) {
            Integer idx = existingIndex.get(imported.id());
            if (idx != null) {
                existing.set(idx, imported);
            } else {
                existing.add(imported);
            }
        }
        categoryStore.replaceAll(existing);

        int tagsImported = 0;
        for (Map.Entry<ChestKey, String> entry : bookData.tags.entrySet()) {
            tagStore.setTag(entry.getKey(), entry.getValue());
            tagsImported++;
        }

        if (bookData.lastUsedCategoryId != null) {
            tagStore.setLastUsedCategoryId(bookData.lastUsedCategoryId);
        }

        Map<Identifier, String> currentOverrides = new LinkedHashMap<>(mappingService.snapshotOverrides());
        Set<Identifier> currentBlocked = new LinkedHashSet<>(mappingService.snapshotBlockedMappings());

        currentOverrides.putAll(bookData.overrides);
        for (Identifier blockedId : bookData.blocked) {
            currentOverrides.remove(blockedId);
            currentBlocked.add(blockedId);
        }
        mappingService.applyScopedOverrides(currentOverrides, currentBlocked);

        return tagsImported;
    }

    private static List<String> splitIntoPages(String payload) {
        List<String> pages = new ArrayList<>();
        int offset = 0;
        while (offset < payload.length()) {
            int end = Math.min(offset + MAX_PAGE_LENGTH, payload.length());
            pages.add(payload.substring(offset, end));
            offset = end;
        }
        if (pages.isEmpty()) {
            pages.add("");
        }
        return pages;
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

    public record ExportResult(boolean success, Component message, int pageCount, int tagCount, int categoryCount) {
        static ExportResult success(Component message, int pageCount, int tagCount, int categoryCount) {
            return new ExportResult(true, message, pageCount, tagCount, categoryCount);
        }

        static ExportResult failure(Component message) {
            return new ExportResult(false, message, 0, 0, 0);
        }
    }

    public record ImportResult(boolean success, Component message, int tagsImported, int categoriesImported) {
        static ImportResult success(Component message, int tagsImported, int categoriesImported) {
            return new ImportResult(true, message, tagsImported, categoriesImported);
        }

        static ImportResult failure(Component message) {
            return new ImportResult(false, message, 0, 0);
        }
    }

    private record BookData(
            Map<ChestKey, String> tags,
            String lastUsedCategoryId,
            List<Category> categories,
            Map<Identifier, String> overrides,
            Set<Identifier> blocked
    ) {
    }
}
