package com.latchandlabel.client.store;

import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CategoryLifecycleServiceTest {
    private static final String TARGET_CATEGORY = "target";
    private static final String OTHER_CATEGORY = "other";
    private static final Identifier STONE = Identifier.tryParse("minecraft:stone");
    private static final Identifier DIRT = Identifier.tryParse("minecraft:dirt");
    private static final Identifier GOLD = Identifier.tryParse("minecraft:gold_ingot");
    private static final ChestKey ACTIVE_KEY = keyAt(1);
    private static final ChestKey FALLBACK_KEY = keyAt(2);
    private static final ChestKey OTHER_KEY = keyAt(3);

    @Test
    void deletingCategoryRemovesReferencesAcrossStoresAndScopes() {
        CategoryStore categoryStore = new CategoryStore();
        TagStore tagStore = new TagStore();
        ItemCategoryMappingService mappingService = new ItemCategoryMappingService();
        categoryStore.replaceAll(List.of(
                category(TARGET_CATEGORY),
                category(OTHER_CATEGORY)
        ));
        tagStore.replaceAllScopes(
                Map.of(
                        "primary", Map.of(ACTIVE_KEY, TARGET_CATEGORY, OTHER_KEY, OTHER_CATEGORY),
                        "fallback", Map.of(FALLBACK_KEY, TARGET_CATEGORY)
                ),
                Map.of("primary", TARGET_CATEGORY, "fallback", TARGET_CATEGORY),
                "primary",
                List.of("fallback")
        );
        mappingService.applyScopedOverrides(
                Map.of(STONE, TARGET_CATEGORY, DIRT, OTHER_CATEGORY),
                Set.of(GOLD)
        );

        boolean deleted = new CategoryLifecycleService(categoryStore, tagStore, mappingService)
                .deleteCategoryWithCascade(TARGET_CATEGORY);

        assertTrue(deleted);
        assertFalse(categoryStore.getById(TARGET_CATEGORY).isPresent());
        assertTrue(categoryStore.getById(OTHER_CATEGORY).isPresent());
        assertEquals(Map.of(OTHER_KEY, OTHER_CATEGORY), tagStore.snapshotTagsForScope("primary"));
        assertEquals(Map.of(), tagStore.snapshotTagsForScope("fallback"));
        assertEquals(Map.of(DIRT, OTHER_CATEGORY), mappingService.snapshotOverrides());
        assertEquals(Set.of(GOLD), mappingService.snapshotBlockedMappings());
        assertFalse(tagStore.snapshotLastUsedCategoryIdForScope("primary").isPresent());
        assertFalse(tagStore.snapshotLastUsedCategoryIdForScope("fallback").isPresent());
    }

    private static Category category(String id) {
        return new Category(id, id, 0xFFFFFF, STONE, 0, true);
    }

    private static ChestKey keyAt(int x) {
        return new ChestKey(Identifier.tryParse("minecraft:overworld"), new BlockPos(x, 64, 0));
    }
}
