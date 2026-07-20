package com.latchandlabel.client.find;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.LatchLabelClientState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Holds the current {@code /find} results. Results persist until dismissed, a new search
 * replaces them, the player changes dimension, or the configurable highlight timeout elapses
 * ({@link FindSettings#findHighlightTimeoutSeconds()}, 0 = never). Also tracks the query label,
 * the category it belongs to (for the HUD), and a target cursor the player can cycle through.
 */
public final class FindResultState {

    private record Snapshot(
            List<FindScanService.FindMatch> results,
            long generation,
            Set<ChestKey> focusedKeys,
            SlotHighlight slotHighlight,
            Component queryLabel,
            String categoryId,
            int targetIndex,
            long publishedAtMs
    ) {
        static final Snapshot EMPTY = new Snapshot(List.of(), 0L, Set.of(), SlotHighlight.EMPTY, null, null, 0, 0L);

        Snapshot copy(List<FindScanService.FindMatch> results, Set<ChestKey> focusedKeys, SlotHighlight slotHighlight,
                      Component queryLabel, String categoryId, int targetIndex, long publishedAtMs) {
            return new Snapshot(results, generation, focusedKeys, slotHighlight, queryLabel, categoryId, targetIndex, publishedAtMs);
        }
    }

    private record SlotHighlight(Item exactItem, Set<Item> variantItems, String categoryId) {
        static final SlotHighlight EMPTY = new SlotHighlight(null, Set.of(), null);

        boolean isEmpty() {
            return exactItem == null && variantItems.isEmpty() && categoryId == null;
        }
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private FindResultState() {
    }

    public static synchronized void publish(List<FindScanService.FindMatch> matches) {
        Snapshot prev = snapshot;
        snapshot = new Snapshot(List.copyOf(matches), prev.generation() + 1, Set.of(), prev.slotHighlight(),
                prev.queryLabel(), prev.categoryId(), 0, System.currentTimeMillis());
    }

    public static synchronized void setQueryLabel(Component label) {
        Snapshot p = snapshot;
        snapshot = p.copy(p.results(), p.focusedKeys(), p.slotHighlight(), label, p.categoryId(), p.targetIndex(), p.publishedAtMs());
    }

    public static synchronized void setQueryCategory(String categoryId) {
        Snapshot p = snapshot;
        snapshot = p.copy(p.results(), p.focusedKeys(), p.slotHighlight(), p.queryLabel(), categoryId, p.targetIndex(), p.publishedAtMs());
    }

    public static Optional<Component> queryLabel() {
        return Optional.ofNullable(snapshot.queryLabel());
    }

    public static Optional<String> queryCategoryId() {
        return Optional.ofNullable(snapshot.categoryId());
    }

    public static List<FindScanService.FindMatch> getActiveResults() {
        return snapshot.results();
    }

    public static boolean hasActiveResults() {
        Snapshot s = snapshot;
        return !s.results().isEmpty() || !s.slotHighlight().isEmpty();
    }

    public static void clear() {
        snapshot = Snapshot.EMPTY;
    }

    /** Clears everything once the configured highlight timeout elapses (0 = never). */
    public static void expireIfDue() {
        Snapshot s = snapshot;
        if (s == Snapshot.EMPTY) {
            return;
        }
        int timeoutSeconds = FindSettings.findHighlightTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return;
        }
        if (System.currentTimeMillis() - s.publishedAtMs() > timeoutSeconds * 1000L) {
            clear();
        }
    }

    public static long currentGeneration() {
        return snapshot.generation();
    }

    public static synchronized void cycleTarget() {
        Snapshot p = snapshot;
        if (p.results().isEmpty()) {
            return;
        }
        int next = (p.targetIndex() + 1) % p.results().size();
        snapshot = p.copy(p.results(), p.focusedKeys(), p.slotHighlight(), p.queryLabel(), p.categoryId(), next, p.publishedAtMs());
    }

    public static Optional<FindScanService.FindMatch> currentTarget() {
        Snapshot s = snapshot;
        if (s.results().isEmpty()) {
            return Optional.empty();
        }
        int index = Math.min(s.targetIndex(), s.results().size() - 1);
        return Optional.of(s.results().get(index));
    }

    public static synchronized void focusAll(Collection<ChestKey> chestKeys) {
        Snapshot p = snapshot;
        snapshot = p.copy(p.results(), new LinkedHashSet<>(chestKeys), p.slotHighlight(), p.queryLabel(), p.categoryId(), p.targetIndex(), p.publishedAtMs());
    }

    public static boolean isFocused(ChestKey chestKey) {
        Snapshot s = snapshot;
        return !s.focusedKeys().isEmpty() && s.focusedKeys().contains(chestKey);
    }

    public static synchronized void highlightItems(Item exactItem, Set<Item> variantItems) {
        Snapshot p = snapshot;
        snapshot = p.copy(p.results(), p.focusedKeys(),
                new SlotHighlight(exactItem, variantItems == null ? Set.of() : Set.copyOf(variantItems), null),
                p.queryLabel(), p.categoryId(), p.targetIndex(), p.publishedAtMs());
    }

    public static synchronized void highlightCategory(String categoryId) {
        Snapshot p = snapshot;
        snapshot = p.copy(p.results(), p.focusedKeys(), new SlotHighlight(null, Set.of(), categoryId),
                p.queryLabel(), p.categoryId(), p.targetIndex(), p.publishedAtMs());
    }

    public static OptionalInt slotHighlightColor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OptionalInt.empty();
        }

        SlotHighlight highlight = snapshot.slotHighlight();
        Item item = stack.getItem();
        if (highlight.exactItem() != null && item == highlight.exactItem()) {
            return OptionalInt.of(0xAAFFD84A);
        }
        if (highlight.variantItems().contains(item)) {
            return OptionalInt.of(0x88FFA53D);
        }
        if (highlight.categoryId() != null && LatchLabelClientState.itemCategoryMappingService()
                .categoryIdFor(stack)
                .filter(highlight.categoryId()::equals)
                .isPresent()) {
            return OptionalInt.of(0x88FFD84A);
        }

        return OptionalInt.empty();
    }
}
