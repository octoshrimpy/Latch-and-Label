package com.latchandlabel.client.config;

import com.latchandlabel.client.data.ScopeUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Stores the selected client-side world profile for each multiplayer server scope. */
public final class MultiplayerWorldProfileSettings {
    private static final Map<String, String> PROFILE_BY_SERVER_SCOPE = new HashMap<>();

    private MultiplayerWorldProfileSettings() {
    }

    public static synchronized Optional<String> profileForServerScope(String serverScopeId) {
        String normalizedServerScopeId = normalizeServerScopeId(serverScopeId);
        if (normalizedServerScopeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PROFILE_BY_SERVER_SCOPE.get(normalizedServerScopeId));
    }

    public static synchronized void setProfileForServerScope(String serverScopeId, String profileName) {
        String normalizedServerScopeId = normalizeServerScopeId(serverScopeId);
        String normalizedProfileName = normalizeProfileName(profileName);
        if (normalizedServerScopeId == null || normalizedProfileName == null) {
            return;
        }
        PROFILE_BY_SERVER_SCOPE.put(normalizedServerScopeId, normalizedProfileName);
    }

    public static synchronized boolean clearProfileForServerScope(String serverScopeId) {
        String normalizedServerScopeId = normalizeServerScopeId(serverScopeId);
        if (normalizedServerScopeId == null) {
            return false;
        }
        return PROFILE_BY_SERVER_SCOPE.remove(normalizedServerScopeId) != null;
    }

    public static synchronized Map<String, String> snapshotProfilesByServerScope() {
        return Map.copyOf(PROFILE_BY_SERVER_SCOPE);
    }

    public static synchronized void replaceProfilesByServerScope(Map<String, String> profilesByServerScope) {
        PROFILE_BY_SERVER_SCOPE.clear();
        if (profilesByServerScope == null || profilesByServerScope.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : profilesByServerScope.entrySet()) {
            String normalizedServerScopeId = normalizeServerScopeId(entry.getKey());
            String normalizedProfileName = normalizeProfileName(entry.getValue());
            if (normalizedServerScopeId == null || normalizedProfileName == null) {
                continue;
            }
            PROFILE_BY_SERVER_SCOPE.put(normalizedServerScopeId, normalizedProfileName);
        }
    }

    public static String normalizeProfileName(String profileName) {
        return ScopeUtil.normalizeScopeId(profileName, null);
    }

    private static String normalizeServerScopeId(String serverScopeId) {
        return ScopeUtil.normalizeScopeId(serverScopeId, null);
    }
}
