package com.latchandlabel.client.store;

import com.latchandlabel.client.model.ChestKey;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class TagStore {
    public static final String DEFAULT_SCOPE_ID = "global";

    private final Map<String, Map<ChestKey, String>> tagsByScope = new HashMap<>();
    private final Map<String, String> lastUsedCategoryIdByScope = new HashMap<>();
    private Runnable changeListener = () -> {
    };
    private String activeScopeId = DEFAULT_SCOPE_ID;
    private List<String> activeReadScopeIds = List.of(DEFAULT_SCOPE_ID);

    public synchronized Optional<String> getTag(ChestKey chestKey) {
        Objects.requireNonNull(chestKey, "chestKey");
        for (String scopeId : activeReadScopeIds) {
            Map<ChestKey, String> tags = tagsByScope.get(scopeId);
            if (tags == null) {
                continue;
            }
            String categoryId = tags.get(chestKey);
            if (categoryId != null) {
                return Optional.of(categoryId);
            }
        }
        return Optional.empty();
    }

    public synchronized void setTag(ChestKey chestKey, String categoryId) {
        Objects.requireNonNull(chestKey, "chestKey");
        Objects.requireNonNull(categoryId, "categoryId");

        if (categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId must not be blank");
        }

        Map<ChestKey, String> tags = tagsForActiveScope();
        String previousCategoryId = tags.put(chestKey, categoryId);
        boolean changed = !Objects.equals(previousCategoryId, categoryId);
        String previousLastUsedCategoryId = lastUsedCategoryIdByScope.put(activeScopeId, categoryId);
        if (!Objects.equals(previousLastUsedCategoryId, categoryId)) {
            changed = true;
        }
        if (changed) {
            notifyChanged();
        }
    }

    public synchronized boolean clearTag(ChestKey chestKey) {
        Objects.requireNonNull(chestKey, "chestKey");
        boolean removed = false;
        for (String scopeId : activeReadScopeIds) {
            Map<ChestKey, String> tags = tagsByScope.get(scopeId);
            if (tags != null && tags.remove(chestKey) != null) {
                removed = true;
            }
        }
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    public synchronized Map<ChestKey, String> snapshotTags() {
        Map<ChestKey, String> merged = new HashMap<>();
        for (int i = activeReadScopeIds.size() - 1; i >= 0; i--) {
            String scopeId = activeReadScopeIds.get(i);
            Map<ChestKey, String> tags = tagsByScope.get(scopeId);
            if (tags != null && !tags.isEmpty()) {
                merged.putAll(tags);
            }
        }
        tagsByScope.computeIfAbsent(activeScopeId, unused -> new HashMap<>());
        return Map.copyOf(merged);
    }

    public synchronized void replaceAll(Map<ChestKey, String> tags, String lastUsedCategoryId) {
        Objects.requireNonNull(tags, "tags");
        Map<String, Map<ChestKey, String>> tagsByScope = new HashMap<>();
        tagsByScope.put(activeScopeId, new HashMap<>(tags));

        Map<String, String> lastUsedByScope = new HashMap<>();
        if (lastUsedCategoryId != null && !lastUsedCategoryId.isBlank()) {
            lastUsedByScope.put(activeScopeId, lastUsedCategoryId);
        }
        replaceAllScopes(tagsByScope, lastUsedByScope, activeScopeId);
    }

    public synchronized Optional<String> getLastUsedCategoryId() {
        for (String scopeId : activeReadScopeIds) {
            String categoryId = lastUsedCategoryIdByScope.get(scopeId);
            if (categoryId != null && !categoryId.isBlank()) {
                return Optional.of(categoryId);
            }
        }
        return Optional.empty();
    }

    public synchronized void setLastUsedCategoryId(String categoryId) {
        String nextValue = (categoryId == null || categoryId.isBlank()) ? null : categoryId;
        String previousValue = lastUsedCategoryIdByScope.get(activeScopeId);
        if (Objects.equals(previousValue, nextValue)) {
            return;
        }

        if (nextValue == null) {
            lastUsedCategoryIdByScope.remove(activeScopeId);
        } else {
            lastUsedCategoryIdByScope.put(activeScopeId, nextValue);
        }
        notifyChanged();
    }

    public synchronized void setChangeListener(Runnable changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    public synchronized void clearCategoryReferences(String categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");

        boolean changed = false;
        for (Map<ChestKey, String> tags : tagsByScope.values()) {
            if (tags.entrySet().removeIf(entry -> categoryId.equals(entry.getValue()))) {
                changed = true;
            }
        }
        for (Map.Entry<String, String> entry : Map.copyOf(lastUsedCategoryIdByScope).entrySet()) {
            if (Objects.equals(entry.getValue(), categoryId)) {
                lastUsedCategoryIdByScope.remove(entry.getKey());
                changed = true;
            }
        }

        if (changed) {
            notifyChanged();
        }
    }

    public synchronized void setActiveScopeId(String scopeId) {
        setActiveScopeId(scopeId, List.of());
    }

    public synchronized void setActiveScopeId(String scopeId, List<String> fallbackReadScopeIds) {
        if (scopeId == null || scopeId.isBlank()) {
            scopeId = DEFAULT_SCOPE_ID;
        }
        activeScopeId = scopeId;
        tagsByScope.computeIfAbsent(activeScopeId, unused -> new HashMap<>());

        LinkedHashSet<String> readScopes = new LinkedHashSet<>();
        readScopes.add(activeScopeId);
        if (fallbackReadScopeIds != null) {
            for (String fallbackScopeId : fallbackReadScopeIds) {
                String normalized = normalizeScopeId(fallbackScopeId);
                if (normalized != null) {
                    readScopes.add(normalized);
                }
            }
        }
        activeReadScopeIds = List.copyOf(readScopes);
    }

    public synchronized String getActiveScopeId() {
        return activeScopeId;
    }

    public synchronized void replaceAllScopes(
            Map<String, Map<ChestKey, String>> tagsByScope,
            Map<String, String> lastUsedCategoryIdByScope,
            String activeScopeId
    ) {
        Objects.requireNonNull(tagsByScope, "tagsByScope");
        Objects.requireNonNull(lastUsedCategoryIdByScope, "lastUsedCategoryIdByScope");

        this.tagsByScope.clear();
        for (Map.Entry<String, Map<ChestKey, String>> entry : tagsByScope.entrySet()) {
            String scopeId = normalizeScopeId(entry.getKey());
            if (scopeId == null) {
                continue;
            }
            Map<ChestKey, String> scopeTags = entry.getValue();
            if (scopeTags == null || scopeTags.isEmpty()) {
                this.tagsByScope.put(scopeId, new HashMap<>());
                continue;
            }
            this.tagsByScope.put(scopeId, new HashMap<>(scopeTags));
        }

        this.lastUsedCategoryIdByScope.clear();
        for (Map.Entry<String, String> entry : lastUsedCategoryIdByScope.entrySet()) {
            String scopeId = normalizeScopeId(entry.getKey());
            if (scopeId == null) {
                continue;
            }
            String categoryId = entry.getValue();
            if (categoryId == null || categoryId.isBlank()) {
                continue;
            }
            this.lastUsedCategoryIdByScope.put(scopeId, categoryId);
        }

        this.activeScopeId = normalizeScopeId(activeScopeId);
        if (this.activeScopeId == null) {
            this.activeScopeId = DEFAULT_SCOPE_ID;
        }
        this.tagsByScope.computeIfAbsent(this.activeScopeId, unused -> new HashMap<>());
        this.activeReadScopeIds = List.of(this.activeScopeId);
        notifyChanged();
    }

    public synchronized Map<String, Map<ChestKey, String>> snapshotAllTagsByScope() {
        Map<String, Map<ChestKey, String>> result = new HashMap<>();
        for (Map.Entry<String, Map<ChestKey, String>> entry : tagsByScope.entrySet()) {
            result.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    public synchronized Map<String, String> snapshotLastUsedCategoryIdByScope() {
        return Map.copyOf(lastUsedCategoryIdByScope);
    }

    private Map<ChestKey, String> tagsForActiveScope() {
        return tagsByScope.computeIfAbsent(activeScopeId, unused -> new HashMap<>());
    }

    private static String normalizeScopeId(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            return null;
        }
        return scopeId;
    }

    private void notifyChanged() {
        changeListener.run();
    }
}
