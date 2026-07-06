package com.latchandlabel.client;

import com.latchandlabel.client.data.ClientDataManager;
import com.latchandlabel.client.config.ClientConfigManager;
import com.latchandlabel.client.config.ConfigProfileManager;
import com.latchandlabel.client.store.CategoryLifecycleService;
import com.latchandlabel.client.store.CategoryStore;
import com.latchandlabel.client.store.ObservedIndexStore;
import com.latchandlabel.client.store.TagStore;
import com.latchandlabel.client.tagging.StorageTagReconciler;
import com.latchandlabel.client.tooltip.ItemCategoryMappingService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central service locator holding singleton instances of all major subsystems.
 * Call {@link #initialize()} once during mod startup before accessing any services.
 */
public final class LatchLabelClientState {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final CategoryStore CATEGORY_STORE = new CategoryStore();
    private static final TagStore TAG_STORE = new TagStore();
    private static final ItemCategoryMappingService ITEM_CATEGORY_MAPPING_SERVICE = new ItemCategoryMappingService();
    private static final ObservedIndexStore OBSERVED_INDEX_STORE = new ObservedIndexStore(TAG_STORE::getActiveScopeId);
    private static final ClientDataManager DATA_MANAGER = new ClientDataManager(CATEGORY_STORE, TAG_STORE, ITEM_CATEGORY_MAPPING_SERVICE);
    private static final ClientConfigManager CLIENT_CONFIG_MANAGER = new ClientConfigManager();
    private static final ConfigProfileManager CONFIG_PROFILE_MANAGER = new ConfigProfileManager();
    private static final StorageTagReconciler STORAGE_TAG_RECONCILER = new StorageTagReconciler(TAG_STORE);
    private static final CategoryLifecycleService CATEGORY_LIFECYCLE_SERVICE =
            new CategoryLifecycleService(CATEGORY_STORE, TAG_STORE, ITEM_CATEGORY_MAPPING_SERVICE);

    private LatchLabelClientState() {
    }

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
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

    public static ObservedIndexStore observedIndexStore() {
        return OBSERVED_INDEX_STORE;
    }

    public static ClientConfigManager clientConfigManager() {
        return CLIENT_CONFIG_MANAGER;
    }

    public static ConfigProfileManager configProfileManager() {
        return CONFIG_PROFILE_MANAGER;
    }

    public static StorageTagReconciler storageTagReconciler() {
        return STORAGE_TAG_RECONCILER;
    }

    public static CategoryLifecycleService categoryLifecycleService() {
        return CATEGORY_LIFECYCLE_SERVICE;
    }
}
