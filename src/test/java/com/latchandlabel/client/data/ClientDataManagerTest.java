package com.latchandlabel.client.data;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientDataManagerTest {
    private static final ChestKey FALLBACK_KEY = new ChestKey(
            ResourceLocation.tryParse("minecraft:overworld"),
            new BlockPos(4, 64, 8)
    );

    @TempDir
    Path tempDir;

    @Test
    void emptyActiveTagsFilePreventsFallbackRemigration() throws Exception {
        Path activeScope = tempDir.resolve("scopes").resolve("primary");
        Path fallbackScope = tempDir.resolve("scopes").resolve("fallback");
        Files.createDirectories(activeScope);
        Files.createDirectories(fallbackScope);
        Files.writeString(activeScope.resolve("tags.json"), """
                {
                  "version": 1,
                  "tags": {}
                }
                """);
        Files.writeString(fallbackScope.resolve("tags.json"), """
                {
                  "version": 1,
                  "tags": {
                    "%s": "legacy_category"
                  },
                  "lastUsedCategoryId": "legacy_category"
                }
                """.formatted(FALLBACK_KEY.toStringKey()));

        TagStore tagStore = new TagStore();
        ClientDataManager manager = new ClientDataManager(
                new CategoryStore(),
                tagStore,
                new ItemCategoryMappingService(),
                tempDir
        );

        try {
            manager.initialize();
            manager.setActiveScopeId("primary", List.of("fallback"));

            assertFalse(tagStore.getTag(FALLBACK_KEY).isPresent());
            assertFalse(tagStore.getLastUsedCategoryId().isPresent());
        } finally {
            manager.close();
        }
    }

    @Test
    void profiledMultiplayerScopeMigratesBaseServerData() throws Exception {
        Path baseScope = tempDir.resolve("scopes").resolve("mp_example.org_25565");
        Files.createDirectories(baseScope);
        Files.writeString(baseScope.resolve("tags.json"), """
                {
                  "version": 1,
                  "tags": {
                    "%s": "custom_category"
                  },
                  "lastUsedCategoryId": "custom_category"
                }
                """.formatted(FALLBACK_KEY.toStringKey()));
        Files.writeString(baseScope.resolve("categories_and_overrides.json"), """
                {
                  "version": 1,
                  "categories": [
                    {
                      "id": "custom_category",
                      "name": "Custom Category",
                      "color": 16711680,
                      "iconItemId": "minecraft:stone",
                      "order": 0,
                      "visible": true
                    }
                  ],
                  "itemOverrides": {}
                }
                """);

        CategoryStore categoryStore = new CategoryStore();
        TagStore tagStore = new TagStore();
        ClientDataManager manager = new ClientDataManager(
                categoryStore,
                tagStore,
                new ItemCategoryMappingService(),
                tempDir
        );

        try {
            manager.initialize();
            manager.setActiveScopeId("mp_example.org_25565_profile_survival", List.of("mp_example.org_25565"));

            assertTrue(tagStore.getTag(FALLBACK_KEY).isPresent());
            assertTrue(categoryStore.getById("custom_category").isPresent());
            assertTrue(Files.exists(tempDir.resolve("scopes")
                    .resolve("mp_example.org_25565_profile_survival")
                    .resolve("tags.json")));
            assertTrue(Files.exists(tempDir.resolve("scopes")
                    .resolve("mp_example.org_25565_profile_survival")
                    .resolve("categories_and_overrides.json")));
        } finally {
            manager.close();
        }
    }
}
