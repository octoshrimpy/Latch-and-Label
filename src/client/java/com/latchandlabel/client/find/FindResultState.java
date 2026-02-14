package com.latchandlabel.client.find;

import com.latchandlabel.client.model.ChestKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class FindResultState {
    private static int highlightDurationSeconds = 10;
    private static final List<ActiveFindResult> ACTIVE_RESULTS = new ArrayList<>();
    private static long generation;
    private static final Set<ChestKey> focusedChestKeys = new LinkedHashSet<>();
    private static long focusExpiresAtEpochMs;

    private FindResultState() {
    }

    public static void publish(List<FindScanService.FindMatch> matches) {
        long expiresAt = System.currentTimeMillis() + getHighlightDurationMs();
        ACTIVE_RESULTS.clear();
        for (FindScanService.FindMatch match : matches) {
            ACTIVE_RESULTS.add(new ActiveFindResult(match, expiresAt));
        }
        generation++;
        focusedChestKeys.clear();
        focusExpiresAtEpochMs = 0L;
    }

    public static List<ActiveFindResult> getActiveResults() {
        pruneExpired();
        return List.copyOf(ACTIVE_RESULTS);
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
        return generation;
    }

    public static void focus(ChestKey chestKey, long durationMs) {
        focusedChestKeys.clear();
        focusedChestKeys.add(chestKey);
        focusExpiresAtEpochMs = System.currentTimeMillis() + Math.max(100L, durationMs);
    }

    public static void focusAll(Collection<ChestKey> chestKeys, long durationMs) {
        focusedChestKeys.clear();
        focusedChestKeys.addAll(chestKeys);
        focusExpiresAtEpochMs = System.currentTimeMillis() + Math.max(100L, durationMs);
    }

    public static boolean isFocused(ChestKey chestKey) {
        pruneExpired();
        return !focusedChestKeys.isEmpty()
                && focusExpiresAtEpochMs > System.currentTimeMillis()
                && focusedChestKeys.contains(chestKey);
    }

    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        ACTIVE_RESULTS.removeIf(result -> result.expiresAtEpochMs() <= now);
        if (!focusedChestKeys.isEmpty() && focusExpiresAtEpochMs <= now) {
            focusedChestKeys.clear();
            focusExpiresAtEpochMs = 0L;
        }
    }

    public record ActiveFindResult(FindScanService.FindMatch match, long expiresAtEpochMs) {
    }
}
