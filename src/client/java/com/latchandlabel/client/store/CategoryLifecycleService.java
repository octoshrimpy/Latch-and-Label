package com.latchandlabel.client.store;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;

import java.util.Objects;

/**
 * Coordinates safe category deletion by cascading cleanup to all dependent stores.
 * Always use this instead of calling {@link CategoryStore#deleteCategory} directly,
 * to ensure orphaned tag references and item overrides are removed before the
 * category itself is deleted.
 */
public final class CategoryLifecycleService {
    private final CategoryStore categoryStore;
    private final TagStore tagStore;
    private final ItemCategoryMappingService itemCategoryMappingService;

    public CategoryLifecycleService(
            CategoryStore categoryStore,
            TagStore tagStore,
            ItemCategoryMappingService itemCategoryMappingService
    ) {
        this.categoryStore = Objects.requireNonNull(categoryStore, "categoryStore");
        this.tagStore = Objects.requireNonNull(tagStore, "tagStore");
        this.itemCategoryMappingService = Objects.requireNonNull(itemCategoryMappingService, "itemCategoryMappingService");
    }

    /**
     * Deletes the given category and removes all tag and item-override references to it.
     * Returns {@code true} if the category existed and was removed, {@code false} otherwise.
     * If cleanup fails, the category is left in place so the user can retry instead of
     * ending up with a deleted category and stale references.
     */
    public boolean deleteCategoryWithCascade(String categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");

        try {
            itemCategoryMappingService.clearCategoryReferences(categoryId);
            tagStore.clearCategoryReferences(categoryId);
        } catch (Exception e) {
            LatchLabel.LOGGER.error("[CategoryLifecycle] categoryId={} cleanup failed; category was not deleted", categoryId, e);
            return false;
        }

        try {
            return categoryStore.deleteCategory(categoryId);
        } catch (Exception e) {
            LatchLabel.LOGGER.error("[CategoryLifecycle] categoryId={} delete failed after cleanup", categoryId, e);
            return false;
        }
    }
}
