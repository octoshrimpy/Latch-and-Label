package com.latchandlabel.client.store;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.data.ScopeUtil;
import com.latchandlabel.client.model.ChestKey;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory store for container-to-category tag mappings, organized by scope.
 * Scopes allow per-world/server isolation of tags. Normal reads use the active
 * scope only; fallback scopes are loaded by the data manager for migration.
 * All public methods are synchronized for thread safety.
 */
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
        Map<ChestKey, String> tags = tagsByScope.get(activeScopeId);
        if (tags == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tags.get(chestKey));
    }

    public synchronized void setTag(ChestKey chestKey, String categoryId) {
        Objects.requireNonNull(chestKey, "chestKey");
        Objects.requireNonNull(categoryId, "categoryId");

        if (categoryId.isBlank()) {
            throw new IllegalArgumentException("categoryId must not be blank");
        }

        LatchLabel.LOGGER.debug("[TagStore] setTag key={} category={} scope={}", chestKey, categoryId, activeScopeId);
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
        LatchLabel.LOGGER.debug("[TagStore] clearTag key={}", chestKey);
        Map<ChestKey, String> tags = tagsByScope.get(activeScopeId);
        boolean removed = tags != null && tags.remove(chestKey) != null;
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    /** Returns a snapshot of tags in the active write scope only. */
    public synchronized Map<ChestKey, String> snapshotTags() {
        return snapshotActiveTags();
    }

    public synchronized Map<ChestKey, String> snapshotActiveTags() {
        return snapshotTagsForScope(activeScopeId);
    }

    public synchronized Map<ChestKey, String> snapshotTagsForScope(String scopeId) {
        Map<ChestKey, String> tags = tagsByScope.get(normalizeScopeId(scopeId));
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(tags);
    }

    public synchronized void replaceAll(Map<ChestKey, String> tags, String lastUsedCategoryId) {
        Objects.requireNonNull(tags, "tags");
        Map<String, Map<ChestKey, String>> tagsByScope = new HashMap<>();
        tagsByScope.put(activeScopeId, new HashMap<>(tags));

        Map<String, String> lastUsedByScope = new HashMap<>();
        if (lastUsedCategoryId != null && !lastUsedCategoryId.isBlank()) {
            lastUsedByScope.put(activeScopeId, lastUsedCategoryId);
        }
        replaceAllScopes(tagsByScope, lastUsedByScope, activeScopeId, activeReadScopeIds);
    }

    public synchronized Optional<String> getLastUsedCategoryId() {
        return snapshotActiveLastUsedCategoryId();
    }

    public synchronized Optional<String> snapshotActiveLastUsedCategoryId() {
        return snapshotLastUsedCategoryIdForScope(activeScopeId);
    }

    public synchronized Optional<String> snapshotLastUsedCategoryIdForScope(String scopeId) {
        String categoryId = lastUsedCategoryIdByScope.get(normalizeScopeId(scopeId));
        return categoryId == null || categoryId.isBlank() ? Optional.empty() : Optional.of(categoryId);
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

    /** Removes all tag entries and last-used references for the given category across all scopes. */
    public synchronized void clearCategoryReferences(String categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");
        LatchLabel.LOGGER.debug("[TagStore] clearCategoryReferences category={}", categoryId);

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

    /** Sets the active write scope and ordered fallback read scopes. */
    public synchronized void setActiveScopeId(String scopeId, List<String> fallbackReadScopeIds) {
        String normalizedScopeId = normalizeScopeId(scopeId);
        if (normalizedScopeId == null) {
            normalizedScopeId = DEFAULT_SCOPE_ID;
        }
        activeScopeId = normalizedScopeId;
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
            String activeScopeId,
            List<String> fallbackReadScopeIds
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

        LinkedHashSet<String> readScopes = new LinkedHashSet<>();
        readScopes.add(this.activeScopeId);
        if (fallbackReadScopeIds != null) {
            for (String fallbackScopeId : fallbackReadScopeIds) {
                String normalized = normalizeScopeId(fallbackScopeId);
                if (normalized != null) {
                    readScopes.add(normalized);
                }
            }
        }
        this.activeReadScopeIds = List.copyOf(readScopes);

        int totalTags = this.tagsByScope.values().stream().mapToInt(Map::size).sum();
        LatchLabel.LOGGER.info("[TagStore] replaceAllScopes: {} scopes, {} total tags, active={}",
                this.tagsByScope.size(), totalTags, this.activeScopeId);
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
        return ScopeUtil.normalizeScopeId(scopeId, DEFAULT_SCOPE_ID);
    }

    private void notifyChanged() {
        changeListener.run();
    }
}
