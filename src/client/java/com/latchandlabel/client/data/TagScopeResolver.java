package com.latchandlabel.client.data;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.config.MultiplayerWorldProfileSettings;
import com.latchandlabel.client.store.TagStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Derives the current data scope from the active server address or singleplayer world name.
 * Produces a primary scope ID for writes and a prioritized list of fallback scopes for
 * reads, enabling backward-compatible migration from older scope naming conventions.
 */
public final class TagScopeResolver {
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final String MULTIPLAYER_PREFIX = "mp_";
    private static final String LEGACY_MULTIPLAYER_PREFIX = "mp:";
    private static final String SINGLEPLAYER_PREFIX = "sp_";
    private static final String LEGACY_SINGLEPLAYER_PREFIX = "sp:";
    private static final String WORLD_SCOPE_SUFFIX_PREFIX = "_w1_";
    private static final String LEGACY_WORLD_SCOPE_SUFFIX_PREFIX = "|w1:";
    private static final String PROFILE_SCOPE_SUFFIX_PREFIX = "_profile_";

    /** Cached primary scope ID from last logged resolution, to avoid per-tick log spam. */
    private static volatile String lastLoggedScopeId = null;

    private TagScopeResolver() {
    }

    public static String resolveCurrentScopeId(Minecraft client) {
        return resolveCurrentScope(client).primaryScopeId();
    }

    public static ResolvedScope resolveCurrentScope(Minecraft client) {
        if (client == null) {
            return new ResolvedScope(TagStore.DEFAULT_SCOPE_ID, List.of());
        }

        BaseScope baseScope = resolveBaseScope(client);
        String primaryScopeId = baseScope.primaryScopeId();
        LinkedHashSet<String> fallbacks = new LinkedHashSet<>();

        if (baseScope.multiplayer()) {
            String profileName = MultiplayerWorldProfileSettings.profileForServerScope(baseScope.primaryScopeId()).orElse(null);
            if (profileName != null) {
                fallbacks.add(baseScope.primaryScopeId());
                primaryScopeId = baseScope.primaryScopeId() + PROFILE_SCOPE_SUFFIX_PREFIX + profileName;
            }
        }

        String worldDiscriminator = resolveWorldDiscriminator(client);
        ResolvedScope result;
        if (worldDiscriminator == null) {
            fallbacks.addAll(baseScope.fallbackScopeIds());
            fallbacks.remove(primaryScopeId);
            result = new ResolvedScope(primaryScopeId, List.copyOf(fallbacks));
        } else {
            // Backward-compatibility: older releases used world-discriminated scope ids.
            fallbacks.add(baseScope.primaryScopeId() + LEGACY_WORLD_SCOPE_SUFFIX_PREFIX + worldDiscriminator);
            for (String fallbackBaseScopeId : baseScope.fallbackScopeIds()) {
                fallbacks.add(fallbackBaseScopeId + WORLD_SCOPE_SUFFIX_PREFIX + worldDiscriminator);
                fallbacks.add(fallbackBaseScopeId + LEGACY_WORLD_SCOPE_SUFFIX_PREFIX + worldDiscriminator);
            }
            fallbacks.addAll(baseScope.fallbackScopeIds());
            fallbacks.remove(primaryScopeId);
            result = new ResolvedScope(primaryScopeId, List.copyOf(fallbacks));
        }

        if (!result.primaryScopeId().equals(lastLoggedScopeId)) {
            LatchLabel.LOGGER.debug("[ScopeResolver] scope changed to primary={} fallbacks={}",
                    result.primaryScopeId(), result.fallbackReadScopeIds().size());
            lastLoggedScopeId = result.primaryScopeId();
        }
        return result;
    }

    public static String resolveCurrentMultiplayerServerScopeId(Minecraft client) {
        if (client == null || client.isInSingleplayer()) {
            return null;
        }
        BaseScope multiplayer = resolveMultiplayerBaseScope(client);
        return multiplayer == null ? null : multiplayer.primaryScopeId();
    }

    public record ResolvedScope(String primaryScopeId, List<String> fallbackReadScopeIds) {
        public ResolvedScope {
            if (primaryScopeId == null || primaryScopeId.isBlank()) {
                primaryScopeId = TagStore.DEFAULT_SCOPE_ID;
            }
            fallbackReadScopeIds = fallbackReadScopeIds == null ? List.of() : List.copyOf(fallbackReadScopeIds);
        }
    }

    private static BaseScope resolveBaseScope(Minecraft client) {
        if (client == null) {
            return new BaseScope(TagStore.DEFAULT_SCOPE_ID, List.of(), false);
        }
        if (client.isInSingleplayer()) {
            return resolveSingleplayerBaseScope(client);
        }

        BaseScope multiplayer = resolveMultiplayerBaseScope(client);
        if (multiplayer != null) {
            return multiplayer;
        }
        return new BaseScope(TagStore.DEFAULT_SCOPE_ID, List.of(), false);
    }

    private static BaseScope resolveSingleplayerBaseScope(Minecraft client) {
        String worldId = "unknown";
        IntegratedServer integratedServer = client.getServer();
        if (integratedServer != null) {
            Path rootPath = integratedServer.getSavePath(WorldSavePath.ROOT);
            if (rootPath != null && rootPath.getFileName() != null) {
                worldId = normalize(rootPath.getFileName().toString());
            }
        }

        String primary = SINGLEPLAYER_PREFIX + worldId;
        LinkedHashSet<String> fallbacks = new LinkedHashSet<>();
        fallbacks.add(LEGACY_SINGLEPLAYER_PREFIX + worldId);
        fallbacks.add(TagStore.DEFAULT_SCOPE_ID);
        return new BaseScope(primary, List.copyOf(fallbacks), false);
    }

    private static BaseScope resolveMultiplayerBaseScope(Minecraft client) {
        List<String> rawCandidates = new ArrayList<>();

        ServerData serverEntry = client.getCurrentServerEntry();
        if (serverEntry != null && serverEntry.address != null && !serverEntry.address.isBlank()) {
            rawCandidates.add(serverEntry.address);
        }

        ClientPacketListener networkHandler = client.getConnection();
        if (networkHandler != null && networkHandler.getConnection() != null && networkHandler.getConnection().getAddress() != null) {
            rawCandidates.add(networkHandler.getConnection().getAddress().toString());
        }

        if (rawCandidates.isEmpty()) {
            return null;
        }

        AddressParts canonicalAddress = null;
        for (String candidate : rawCandidates) {
            canonicalAddress = parseAddress(candidate);
            if (canonicalAddress != null) {
                break;
            }
        }
        if (canonicalAddress == null) {
            canonicalAddress = new AddressParts("unknown", DEFAULT_MINECRAFT_PORT);
        }

        String primary = MULTIPLAYER_PREFIX + normalize(canonicalAddress.host()) + "_" + canonicalAddress.port();
        LinkedHashSet<String> fallbacks = new LinkedHashSet<>();
        for (String candidate : rawCandidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String normalized = normalize(candidate);
            fallbacks.add(LEGACY_MULTIPLAYER_PREFIX + normalized);
            fallbacks.add(MULTIPLAYER_PREFIX + normalized);
        }
        fallbacks.add(TagStore.DEFAULT_SCOPE_ID);
        fallbacks.remove(primary);
        return new BaseScope(primary, List.copyOf(fallbacks), true);
    }

    private static String normalize(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current >= 'a' && current <= 'z') || (current >= '0' && current <= '9') || current == '.' || current == '-' || current == '_') {
                normalized.append(current);
                continue;
            }

            if (current >= 'A' && current <= 'Z') {
                normalized.append(Character.toLowerCase(current));
                continue;
            }
            normalized.append('_');
        }

        String result = normalized.toString().toLowerCase(Locale.ROOT);
        if (result.isBlank()) {
            return "unknown";
        }
        return result;
    }

    private static AddressParts parseAddress(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank()) {
            return null;
        }

        String candidate = rawAddress.trim();
        int slashIndex = candidate.indexOf('/');
        if (slashIndex >= 0) {
            String left = candidate.substring(0, slashIndex).trim();
            String right = candidate.substring(slashIndex + 1).trim();
            candidate = left.isEmpty() ? right : left;
        }
        if (candidate.isEmpty()) {
            return null;
        }

        String host = candidate;
        int port = DEFAULT_MINECRAFT_PORT;

        if (candidate.startsWith("[")) {
            int endBracket = candidate.indexOf(']');
            if (endBracket > 1) {
                host = candidate.substring(1, endBracket);
                if (candidate.length() > endBracket + 2 && candidate.charAt(endBracket + 1) == ':') {
                    port = parsePort(candidate.substring(endBracket + 2), DEFAULT_MINECRAFT_PORT);
                }
            }
        } else {
            int lastColon = candidate.lastIndexOf(':');
            if (lastColon > 0 && candidate.indexOf(':') == lastColon) {
                host = candidate.substring(0, lastColon);
                port = parsePort(candidate.substring(lastColon + 1), DEFAULT_MINECRAFT_PORT);
            }
        }

        if (host == null || host.isBlank()) {
            host = "unknown";
        }
        return new AddressParts(host, port);
    }

    private static int parsePort(String rawPort, int fallback) {
        if (rawPort == null || rawPort.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(rawPort.trim());
            if (parsed <= 0 || parsed > 65535) {
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String resolveWorldDiscriminator(Minecraft client) {
        if (client == null) {
            return null;
        }
        ClientLevel world = client.level;
        if (world == null) {
            return null;
        }

        StringBuilder raw = new StringBuilder(96);
        raw.append("dim=").append(world.dimension().location());
        raw.append(";bottom=").append(world.getBottomY());
        raw.append(";height=").append(world.getHeight());
        raw.append(";sea=").append(world.getSeaLevel());

        try {
            raw.append(";sky=").append(world.getDimension().hasSkyLight());
            raw.append(";ceiling=").append(world.getDimension().hasCeiling());
            raw.append(";coord=").append(world.getDimension().coordinateScale());
        } catch (Exception e) {
            LatchLabel.LOGGER.warn("[ScopeResolver] Partial dimension fingerprint due to: {}", e.getMessage());
        }

        return normalize(raw.toString());
    }

    private record BaseScope(String primaryScopeId, List<String> fallbackScopeIds, boolean multiplayer) {
    }

    private record AddressParts(String host, int port) {
    }
}
