package com.latchandlabel.client.store;

import com.latchandlabel.client.model.ChestKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TagStoreTest {
    private static final ChestKey KEY = new ChestKey(
            Identifier.tryParse("minecraft:overworld"),
            new BlockPos(1, 64, 2)
    );

    @Test
    void readsAndSnapshotsOnlyUseActiveScope() {
        TagStore store = new TagStore();
        store.replaceAllScopes(
                Map.of(
                        "primary", Map.of(),
                        "legacy", Map.of(KEY, "fallback_category")
                ),
                Map.of("legacy", "fallback_category"),
                "primary",
                List.of("legacy")
        );

        assertFalse(store.getTag(KEY).isPresent());
        assertFalse(store.snapshotTags().containsKey(KEY));
        assertFalse(store.getLastUsedCategoryId().isPresent());
        assertEquals("primary", store.getActiveScopeId());
    }

    @Test
    void snapshotTagsForNullOrBlankScopeFallsBackToDefault() {
        TagStore store = new TagStore();
        store.replaceAllScopes(
                Map.of(TagStore.DEFAULT_SCOPE_ID, Map.of(KEY, "default_cat")),
                Map.of(),
                TagStore.DEFAULT_SCOPE_ID,
                List.of()
        );

        // Both null and blank scopes must resolve to DEFAULT_SCOPE_ID, not throw.
        assertNotNull(store.snapshotTagsForScope(null));
        assertNotNull(store.snapshotTagsForScope("  "));
        assertEquals("default_cat", store.snapshotTagsForScope(null).get(KEY));
        assertEquals("default_cat", store.snapshotTagsForScope("  ").get(KEY));
    }

    @Test
    void clearingActiveTagDoesNotExposeFallbackTag() {
        TagStore store = new TagStore();
        Map<ChestKey, String> activeTags = new HashMap<>();
        activeTags.put(KEY, "active_category");

        store.replaceAllScopes(
                Map.of(
                        "primary", activeTags,
                        "legacy", Map.of(KEY, "fallback_category")
                ),
                Map.of("primary", "active_category", "legacy", "fallback_category"),
                "primary",
                List.of("legacy")
        );

        assertTrue(store.clearTag(KEY));

        assertFalse(store.getTag(KEY).isPresent());
        assertFalse(store.snapshotTags().containsKey(KEY));
        assertEquals("fallback_category", store.snapshotTagsForScope("legacy").get(KEY));
    }
}
