package com.latchandlabel.client.data;

import com.latchandlabel.client.store.TagStore;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.util.Locale;

public final class TagScopeResolver {
    private static final String MULTIPLAYER_PREFIX = "mp_";
    private static final String SINGLEPLAYER_PREFIX = "sp_";

    private TagScopeResolver() {
    }

    public static String resolveCurrentScopeId(MinecraftClient client) {
        if (client == null) {
            return TagStore.DEFAULT_SCOPE_ID;
        }

        ServerInfo serverEntry = client.getCurrentServerEntry();
        if (serverEntry != null && serverEntry.address != null && !serverEntry.address.isBlank()) {
            return MULTIPLAYER_PREFIX + normalize(serverEntry.address);
        }

        if (client.isInSingleplayer()) {
            IntegratedServer integratedServer = client.getServer();
            if (integratedServer != null) {
                Path rootPath = integratedServer.getSavePath(WorldSavePath.ROOT);
                if (rootPath != null && rootPath.getFileName() != null) {
                    return SINGLEPLAYER_PREFIX + normalize(rootPath.getFileName().toString());
                }
            }
            return SINGLEPLAYER_PREFIX + "unknown";
        }

        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler != null && networkHandler.getConnection() != null && networkHandler.getConnection().getAddress() != null) {
            return MULTIPLAYER_PREFIX + normalize(networkHandler.getConnection().getAddress().toString());
        }

        return TagStore.DEFAULT_SCOPE_ID;
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
}
