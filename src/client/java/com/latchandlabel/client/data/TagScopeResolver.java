package com.latchandlabel.client.data;

import com.latchandlabel.client.store.TagStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class TagScopeResolver {
    private static final String MULTIPLAYER_PREFIX = "mp:";
    private static final String SINGLEPLAYER_PREFIX = "sp:";
    private static final String WORLD_SCOPE_SUFFIX_PREFIX = "|w1:";

    private TagScopeResolver() {
    }

    public static String resolveCurrentScopeId(MinecraftClient client) {
        return resolveCurrentScope(client).primaryScopeId();
    }

    public static ResolvedScope resolveCurrentScope(MinecraftClient client) {
        if (client == null) {
            return new ResolvedScope(TagStore.DEFAULT_SCOPE_ID, List.of());
        }

        String baseScopeId = null;
        ServerInfo serverEntry = client.getCurrentServerEntry();
        if (serverEntry != null && serverEntry.address != null && !serverEntry.address.isBlank()) {
            baseScopeId = MULTIPLAYER_PREFIX + normalize(serverEntry.address);
        } else if (client.isInSingleplayer()) {
            IntegratedServer integratedServer = client.getServer();
            if (integratedServer != null) {
                Path rootPath = integratedServer.getSavePath(WorldSavePath.ROOT);
                if (rootPath != null && rootPath.getFileName() != null) {
                    baseScopeId = SINGLEPLAYER_PREFIX + normalize(rootPath.getFileName().toString());
                } else {
                    baseScopeId = SINGLEPLAYER_PREFIX + "unknown";
                }
            } else {
                baseScopeId = SINGLEPLAYER_PREFIX + "unknown";
            }
        } else {
            ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
            if (networkHandler != null && networkHandler.getConnection() != null && networkHandler.getConnection().getAddress() != null) {
                baseScopeId = MULTIPLAYER_PREFIX + normalize(networkHandler.getConnection().getAddress().toString());
            }
        }

        if (baseScopeId == null) {
            baseScopeId = TagStore.DEFAULT_SCOPE_ID;
        }

        String worldDiscriminator = resolveWorldDiscriminator(client);
        if (worldDiscriminator == null) {
            return new ResolvedScope(baseScopeId, List.of());
        }
        return new ResolvedScope(baseScopeId + WORLD_SCOPE_SUFFIX_PREFIX + worldDiscriminator, List.of(baseScopeId));
    }

    public record ResolvedScope(String primaryScopeId, List<String> fallbackReadScopeIds) {
        public ResolvedScope {
            if (primaryScopeId == null || primaryScopeId.isBlank()) {
                primaryScopeId = TagStore.DEFAULT_SCOPE_ID;
            }
            fallbackReadScopeIds = fallbackReadScopeIds == null ? List.of() : List.copyOf(fallbackReadScopeIds);
        }
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

    private static String resolveWorldDiscriminator(MinecraftClient client) {
        if (client == null) {
            return null;
        }
        ClientWorld world = client.world;
        if (world == null) {
            return null;
        }

        StringBuilder raw = new StringBuilder(96);
        raw.append("dim=").append(world.getRegistryKey().getValue());
        raw.append(";bottom=").append(world.getBottomY());
        raw.append(";height=").append(world.getHeight());
        raw.append(";sea=").append(world.getSeaLevel());

        try {
            raw.append(";sky=").append(world.getDimension().hasSkyLight());
            raw.append(";ceiling=").append(world.getDimension().hasCeiling());
            raw.append(";coord=").append(world.getDimension().coordinateScale());
        } catch (Exception ignored) {
            // Best-effort fingerprint: keep working across mapping/API differences.
        }

        return normalize(raw.toString());
    }
}
