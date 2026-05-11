package com.latchandlabel.client.find;

import com.latchandlabel.client.model.ChestKey;
import com.latchandlabel.client.LatchLabelClientState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

public final class FindResultState {
    private static volatile int highlightDurationSeconds = 10;

    private record Snapshot(
            List<ActiveFindResult> results,
            long generation,
            Set<ChestKey> focusedKeys,
            long focusExpiresAtEpochMs,
            SlotHighlight slotHighlight
    ) {
        static final Snapshot EMPTY = new Snapshot(List.of(), 0L, Set.of(), 0L, SlotHighlight.EMPTY);
    }

    private record SlotHighlight(
            Item exactItem,
            Set<Item> variantItems,
            String categoryId,
            long expiresAtEpochMs
    ) {
        static final SlotHighlight EMPTY = new SlotHighlight(null, Set.of(), null, 0L);
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private FindResultState() {
    }

    public static void publish(List<FindScanService.FindMatch> matches) {
        long expiresAt = System.currentTimeMillis() + getHighlightDurationMs();
        List<ActiveFindResult> results = matches.stream()
                .map(m -> new ActiveFindResult(m, expiresAt))
                .toList();
        Snapshot prev = snapshot;
        snapshot = new Snapshot(results, prev.generation() + 1, Set.of(), 0L, prev.slotHighlight());
    }

    public static List<ActiveFindResult> getActiveResults() {
        long now = System.currentTimeMillis();
        return snapshot.results().stream()
                .filter(r -> r.expiresAtEpochMs() > now)
                .toList();
    }

    public static void setHighlightDurationSeconds(int seconds) {
        highlightDurationSeconds = Math.max(1, seconds);
    }

    public static int getHighlightDurationSeconds() {
        return highlightDurationSeconds;
    }

    public static long getHighlightDurationMs() {
        return highlightDurationSeconds * 1000L;
    }

    public static long currentGeneration() {
        return snapshot.generation();
    }

    public static void focus(ChestKey chestKey, long durationMs) {
        Snapshot prev = snapshot;
        snapshot = new Snapshot(prev.results(), prev.generation(),
                Set.of(chestKey), System.currentTimeMillis() + Math.max(100L, durationMs), prev.slotHighlight());
    }

    public static void focusAll(Collection<ChestKey> chestKeys, long durationMs) {
        Set<ChestKey> keys = new LinkedHashSet<>(chestKeys);
        Snapshot prev = snapshot;
        snapshot = new Snapshot(prev.results(), prev.generation(),
                keys, System.currentTimeMillis() + Math.max(100L, durationMs), prev.slotHighlight());
    }

    public static boolean isFocused(ChestKey chestKey) {
        Snapshot s = snapshot;
        return !s.focusedKeys().isEmpty()
                && s.focusExpiresAtEpochMs() > System.currentTimeMillis()
                && s.focusedKeys().contains(chestKey);
    }

    public static void highlightItems(Item exactItem, Set<Item> variantItems, long durationMs) {
        Snapshot prev = snapshot;
        snapshot = new Snapshot(
                prev.results(),
                prev.generation(),
                prev.focusedKeys(),
                prev.focusExpiresAtEpochMs(),
                new SlotHighlight(
                        exactItem,
                        variantItems == null ? Set.of() : Set.copyOf(variantItems),
                        null,
                        System.currentTimeMillis() + Math.max(100L, durationMs)
                )
        );
    }

    public static void highlightCategory(String categoryId, long durationMs) {
        Snapshot prev = snapshot;
        snapshot = new Snapshot(
                prev.results(),
                prev.generation(),
                prev.focusedKeys(),
                prev.focusExpiresAtEpochMs(),
                new SlotHighlight(
                        null,
                        Set.of(),
                        categoryId,
                        System.currentTimeMillis() + Math.max(100L, durationMs)
                )
        );
    }

    public static OptionalInt slotHighlightColor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OptionalInt.empty();
        }

        SlotHighlight highlight = snapshot.slotHighlight();
        if (highlight.expiresAtEpochMs() <= System.currentTimeMillis()) {
            return OptionalInt.empty();
        }

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

    public record ActiveFindResult(FindScanService.FindMatch match, long expiresAtEpochMs) {
    }
}
