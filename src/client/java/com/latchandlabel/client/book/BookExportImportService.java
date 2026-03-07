package com.latchandlabel.client.book;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WritableBookContentComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BookExportImportService {
    private static final String HEADER = "Latch & Label\n";
    private static final String BOOK_TITLE = "Latch & Label Export";
    private static final int MAX_PAGE_LENGTH = 1024;
    private static final int MAX_PAGES = 100;
    private static final int CURRENT_VERSION = 1;

    private static final Gson COMPACT_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private BookExportImportService() {
    }

    public static ExportResult exportToHeldBook(MinecraftClient client) {
        if (client.player == null) {
            return ExportResult.failure(Text.translatable("latchlabel.book.error_no_player"));
        }

        ItemStack heldStack = client.player.getMainHandStack();
        if (!heldStack.isOf(Items.WRITABLE_BOOK)) {
            return ExportResult.failure(Text.translatable("latchlabel.book.error_no_writable_book"));
        }

        TagStore tagStore = LatchLabelClientState.tagStore();
        CategoryStore categoryStore = LatchLabelClientState.categoryStore();
        ItemCategoryMappingService mappingService = LatchLabelClientState.itemCategoryMappingService();

        Map<ChestKey, String> tags = tagStore.snapshotTags();
        String lastUsedCategoryId = tagStore.getLastUsedCategoryId().orElse(null);
        List<Category> categories = categoryStore.listAll();
        Map<Identifier, String> overrides = mappingService.snapshotOverrides();
        Set<Identifier> blocked = mappingService.snapshotBlockedMappings();

        String json = serializeToJson(tags, lastUsedCategoryId, categories, overrides, blocked);
        String payload = HEADER + json;

        List<String> pages = splitIntoPages(payload);
        if (pages.size() > MAX_PAGES) {
            return ExportResult.failure(Text.translatable("latchlabel.book.error_data_too_large",
                    String.valueOf(pages.size()), String.valueOf(MAX_PAGES)));
        }

        int slot = client.player.getInventory().getSelectedSlot();
        client.getNetworkHandler().sendPacket(
                new BookUpdateC2SPacket(slot, pages, Optional.of(BOOK_TITLE))
        );

        int tagCount = tags.size();
        int categoryCount = categories.size();
        return ExportResult.success(
                Text.translatable("latchlabel.book.export_success",
                        String.valueOf(pages.size()), String.valueOf(tagCount), String.valueOf(categoryCount)),
                pages.size(), tagCount, categoryCount
        );
    }

    public static ImportResult importFromHeldBook(MinecraftClient client) {
        if (client.player == null) {
            return ImportResult.failure(Text.translatable("latchlabel.book.error_no_player"));
        }

        ItemStack heldStack = client.player.getMainHandStack();
        List<String> pages = extractPages(heldStack);
        if (pages == null) {
            return ImportResult.failure(Text.translatable("latchlabel.book.error_no_book"));
        }
        if (pages.isEmpty()) {
            return ImportResult.failure(Text.translatable("latchlabel.book.error_book_empty"));
        }

        String fullText = String.join("", pages);
        if (!fullText.startsWith(HEADER)) {
            return ImportResult.failure(Text.translatable("latchlabel.book.error_invalid_header"));
        }

        String json = fullText.substring(HEADER.length());
        BookData bookData;
        try {
            bookData = deserializeFromJson(json);
        } catch (Exception e) {
            LatchLabel.LOGGER.warn("Failed to parse book data", e);
            return ImportResult.failure(Text.translatable("latchlabel.book.error_invalid_data", e.getMessage()));
        }

        int tagsImported = mergeData(bookData);
        LatchLabelClientState.dataManager().scheduleSave();

        return ImportResult.success(
                Text.translatable("latchlabel.book.import_success",
                        String.valueOf(tagsImported), String.valueOf(bookData.categories.size())),
                tagsImported, bookData.categories.size()
        );
    }

    public static boolean isLatchLabelBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        if (stack.isOf(Items.WRITTEN_BOOK)) {
            WrittenBookContentComponent content = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (content == null || content.pages().isEmpty()) {
                return false;
            }
            String firstPage = content.pages().getFirst().raw().getString();
            return firstPage.startsWith("Latch & Label");
        }

        if (stack.isOf(Items.WRITABLE_BOOK)) {
            WritableBookContentComponent content = stack.get(DataComponentTypes.WRITABLE_BOOK_CONTENT);
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

    private static List<String> extractPages(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        if (stack.isOf(Items.WRITTEN_BOOK)) {
            WrittenBookContentComponent content = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (content == null) {
                return null;
            }
            List<String> pages = new ArrayList<>();
            for (RawFilteredPair<Text> page : content.pages()) {
                pages.add(page.raw().getString());
            }
            return pages;
        }

        if (stack.isOf(Items.WRITABLE_BOOK)) {
            WritableBookContentComponent content = stack.get(DataComponentTypes.WRITABLE_BOOK_CONTENT);
            if (content == null) {
                return null;
            }
            List<String> pages = new ArrayList<>();
            for (RawFilteredPair<String> page : content.pages()) {
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
            Set<Identifier> blocked
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("v", CURRENT_VERSION);

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

    private static BookData deserializeFromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        Map<ChestKey, String> tags = new HashMap<>();
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

        return new BookData(tags, lastUsedCategoryId, categories, overrides, blocked);
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

    private static int mergeData(BookData bookData) {
        CategoryStore categoryStore = LatchLabelClientState.categoryStore();
        TagStore tagStore = LatchLabelClientState.tagStore();
        ItemCategoryMappingService mappingService = LatchLabelClientState.itemCategoryMappingService();

        // Merge categories: update existing, add new
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

        // Merge tags
        int tagsImported = 0;
        for (Map.Entry<ChestKey, String> entry : bookData.tags.entrySet()) {
            tagStore.setTag(entry.getKey(), entry.getValue());
            tagsImported++;
        }

        if (bookData.lastUsedCategoryId != null) {
            tagStore.setLastUsedCategoryId(bookData.lastUsedCategoryId);
        }

        // Merge item overrides
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

    public record ExportResult(boolean success, Text message, int pageCount, int tagCount, int categoryCount) {
        static ExportResult success(Text message, int pageCount, int tagCount, int categoryCount) {
            return new ExportResult(true, message, pageCount, tagCount, categoryCount);
        }

        static ExportResult failure(Text message) {
            return new ExportResult(false, message, 0, 0, 0);
        }
    }

    public record ImportResult(boolean success, Text message, int tagsImported, int categoriesImported) {
        static ImportResult success(Text message, int tagsImported, int categoriesImported) {
            return new ImportResult(true, message, tagsImported, categoriesImported);
        }

        static ImportResult failure(Text message) {
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
