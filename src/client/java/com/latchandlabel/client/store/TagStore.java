package com.latchandlabel.client.store;

import com.latchandlabel.client.model.ChestKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TagStore {
    public static final String DEFAULT_SCOPE_ID = "global";

    private final Map<ChestKey, String> tagsByChest = new HashMap<>();
    private Runnable changeListener = () -> {
    };
    private String lastUsedCategoryId;
    private String activeScopeId = DEFAULT_SCOPE_ID;

    public synchronized Optional<String> getTag(ChestKey chestKey) {
        Objects.requireNonNull(chestKey, "chestKey");
        return Optional.ofNullable(tagsByChest.get(chestKey));
    }

    public synchronized void setTag(ChestKey chestKey, String categoryId) {
        Objects.requireNonNull(chestKey, "chestKey");
        Objects.requireNonNull(categoryId, "categoryId");

        if (categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId must not be blank");
        }

        String previousCategoryId = tagsByChest.put(chestKey, categoryId);
        boolean changed = !Objects.equals(previousCategoryId, categoryId);
        if (!Objects.equals(lastUsedCategoryId, categoryId)) {
            lastUsedCategoryId = categoryId;
            changed = true;
        }
        if (changed) {
            notifyChanged();
        }
    }

    public synchronized boolean clearTag(ChestKey chestKey) {
        Objects.requireNonNull(chestKey, "chestKey");
        boolean removed = tagsByChest.remove(chestKey) != null;
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    public synchronized Map<ChestKey, String> snapshotTags() {
        return Map.copyOf(tagsByChest);
    }

    public synchronized void replaceAll(Map<ChestKey, String> tags, String lastUsedCategoryId) {
        Objects.requireNonNull(tags, "tags");

        tagsByChest.clear();
        tagsByChest.putAll(tags);
        this.lastUsedCategoryId = lastUsedCategoryId;
        notifyChanged();
    }

    public synchronized Optional<String> getLastUsedCategoryId() {
        return Optional.ofNullable(lastUsedCategoryId);
    }

    public synchronized void setLastUsedCategoryId(String categoryId) {
        String nextValue = (categoryId == null || categoryId.isBlank()) ? null : categoryId;
        if (Objects.equals(lastUsedCategoryId, nextValue)) {
            return;
        }

        lastUsedCategoryId = nextValue;
        notifyChanged();
    }

    public synchronized void setChangeListener(Runnable changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    public synchronized void clearCategoryReferences(String categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");

        boolean changed = tagsByChest.entrySet().removeIf(entry -> categoryId.equals(entry.getValue()));
        if (Objects.equals(lastUsedCategoryId, categoryId)) {
            lastUsedCategoryId = null;
            changed = true;
        }

        if (changed) {
            notifyChanged();
        }
    }

    public synchronized void setActiveScopeId(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            activeScopeId = DEFAULT_SCOPE_ID;
            return;
        }
        activeScopeId = scopeId;
    }

    public synchronized String getActiveScopeId() {
        return activeScopeId;
    }

    private void notifyChanged() {
        changeListener.run();
    }
}
