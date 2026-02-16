package com.latchandlabel.client;

import com.latchandlabel.client.data.ClientDataManager;
import com.latchandlabel.client.config.ClientConfigManager;
import com.latchandlabel.client.config.ConfigProfileManager;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.TagStore;

public final class LatchLabelClientState {
    private static final CategoryStore CATEGORY_STORE = new CategoryStore();
    private static final TagStore TAG_STORE = new TagStore();
    private static final ItemCategoryMappingService ITEM_CATEGORY_MAPPING_SERVICE = new ItemCategoryMappingService();
    private static final ClientDataManager DATA_MANAGER = new ClientDataManager(CATEGORY_STORE, TAG_STORE, ITEM_CATEGORY_MAPPING_SERVICE);
    private static final ClientConfigManager CLIENT_CONFIG_MANAGER = new ClientConfigManager();
    private static final ConfigProfileManager CONFIG_PROFILE_MANAGER = new ConfigProfileManager();

    private LatchLabelClientState() {
    }

    public static void initialize() {
        CLIENT_CONFIG_MANAGER.initialize();
        ITEM_CATEGORY_MAPPING_SERVICE.initialize();
        DATA_MANAGER.initialize();
    }

    public static CategoryStore categoryStore() {
        return CATEGORY_STORE;
    }

    public static TagStore tagStore() {
        return TAG_STORE;
    }

    public static ClientDataManager dataManager() {
        return DATA_MANAGER;
    }

    public static ItemCategoryMappingService itemCategoryMappingService() {
        return ITEM_CATEGORY_MAPPING_SERVICE;
    }

    public static ClientConfigManager clientConfigManager() {
        return CLIENT_CONFIG_MANAGER;
    }

    public static ConfigProfileManager configProfileManager() {
        return CONFIG_PROFILE_MANAGER;
    }
}
