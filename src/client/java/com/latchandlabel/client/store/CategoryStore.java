package com.latchandlabel.client.store;

import com.latchandlabel.client.model.Category;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class CategoryStore {
    private final List<Category> categories = new ArrayList<>();
    private Runnable changeListener = () -> {
    };

    public synchronized Optional<Category> getById(String categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");

        return categories.stream()
                .filter(category -> category.id().equals(categoryId))
                .findFirst();
    }

    public synchronized List<Category> listAll() {
        return List.copyOf(categories);
    }

    public synchronized void replaceAll(List<Category> nextCategories) {
        Objects.requireNonNull(nextCategories, "nextCategories");

        categories.clear();
        categories.addAll(nextCategories.stream()
                .sorted(Comparator.comparingInt(Category::order))
                .toList());
        notifyChanged();
    }

    public synchronized boolean updateCategoryDetails(String categoryId, String name, int color, Identifier iconItemId) {
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(iconItemId, "iconItemId");

        String normalizedName = name.trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }

        int normalizedColor = color & 0x00FFFFFF;
        for (int i = 0; i < categories.size(); i++) {
            Category current = categories.get(i);
            if (!current.id().equals(categoryId)) {
                continue;
            }

            if (current.name().equals(normalizedName)
                    && current.color() == normalizedColor
                    && current.iconItemId().equals(iconItemId)) {
                return false;
            }

            categories.set(i, new Category(
                    current.id(),
                    normalizedName,
                    normalizedColor,
                    iconItemId,
                    current.order(),
                    current.visible()
            ));
            notifyChanged();
            return true;
        }

        return false;
    }

    public synchronized Category createCategory(String preferredName, int color, Identifier iconItemId) {
        Objects.requireNonNull(preferredName, "preferredName");
        Objects.requireNonNull(iconItemId, "iconItemId");

        String normalizedName = preferredName.trim();
        if (normalizedName.isBlank()) {
            normalizedName = "New Category";
        }

        String baseId = normalizeCategoryId(normalizedName);
        if (baseId.isBlank()) {
            baseId = "new_category";
        }

        String uniqueId = baseId;
        int suffix = 2;
        while (containsId(uniqueId)) {
            uniqueId = baseId + "_" + suffix;
            suffix++;
        }

        int nextOrder = categories.stream()
                .mapToInt(Category::order)
                .max()
                .orElse(-1) + 1;
        Category created = new Category(uniqueId, normalizedName, color & 0x00FFFFFF, iconItemId, nextOrder, true);
        categories.add(created);
        categories.sort(Comparator.comparingInt(Category::order));
        notifyChanged();
        return created;
    }

    public synchronized boolean deleteCategory(String categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");

        boolean removed = categories.removeIf(category -> category.id().equals(categoryId));
        if (removed) {
            notifyChanged();
        }
        return removed;
    }

    public synchronized void setChangeListener(Runnable changeListener) {
        this.changeListener = Objects.requireNonNull(changeListener, "changeListener");
    }

    private boolean containsId(String categoryId) {
        return categories.stream().anyMatch(category -> category.id().equals(categoryId));
    }

    private static String normalizeCategoryId(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            boolean allowed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            if (allowed) {
                sb.append(ch);
                previousUnderscore = false;
                continue;
            }
            if (!previousUnderscore) {
                sb.append('_');
                previousUnderscore = true;
            }
        }

        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) == '_') {
            start++;
        }
        while (end > start && sb.charAt(end - 1) == '_') {
            end--;
        }
        return sb.substring(start, end);
    }

    private void notifyChanged() {
        changeListener.run();
    }
}
