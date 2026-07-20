package com.latchandlabel.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.dump.DumpSettings;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.find.FindSettings;
import com.latchandlabel.client.LatchLabel;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads and writes the mod's JSON configuration file ({@code client_config.json}).
 * On startup, loads persisted settings into the various settings holders; writes
 * defaults if the file is missing or corrupt.
 */
public final class ClientConfigManager {
    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path configPath;

    public ClientConfigManager() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(LatchLabel.MOD_ID);
        this.configPath = configDir.resolve("client_config.json");
    }

    public void initialize() {
        try {
            Files.createDirectories(configPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create client config directory", e);
        }

        if (!Files.exists(configPath)) {
            writeDefaults();
        }

        reload();
    }

    public void reload() {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IllegalStateException("client_config.json root must be an object");
            }
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            Path backup = backupCorruptFile(configPath);
            LatchLabel.LOGGER.warn("Failed reading client config {}, backed up to {}, rewriting defaults", configPath, backup, e);
            writeDefaults();
            return;
        }

        int version = asInt(root.get("version"), -1);
        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn("Unsupported client config version {} in {}", version, configPath);
        }

        InspectSettings.setInspectRange(asInt(root.get("inspectRange"), 8));
        InspectSettings.setBordersAlwaysVisible(asBoolean(root.get("bordersAlwaysVisible"), false));
        InspectSettings.setLabelsOnLook(asBoolean(root.get("labelsOnLook"), false));
        FindSettings.setDefaultFindRadius(asInt(root.get("defaultFindRadius"), 24));
        FindSettings.setVariantMatchingEnabled(asBoolean(root.get("variantMatchingEnabled"), true));
        FindSettings.setAllowSlashFCommand(asBoolean(root.get("allowSlashFCommand"), true));
        FindSettings.setAllowFindKeybind(asBoolean(root.get("allowFindKeybind"), true));
        FindSettings.setFindHighlightTimeoutSeconds(asInt(root.get("findHighlightTimeoutSeconds"), 60));
        TransferSettings.setMoveSourceMode(MoveSourceMode.fromConfigValue(asString(root.get("moveSourceMode"), "inventory")));
        TransferSettings.setPullDropsOnGround(asBoolean(root.get("pullDropsOnGround"), false));
        SortSettings.setSortMethod(com.latchandlabel.client.sort.SortMethod.fromConfigValue(asString(root.get("sortMethod"), "registry_id")));
        SortSettings.setDropOverflowAtFeet(asBoolean(root.get("sortDropOverflowAtFeet"), false));
        ContainerDetectionSettings.setDetectedCategoryThresholdPercent(asInt(
                root.get("detectedCategoryThresholdPercent"),
                ContainerDetectionSettings.defaultDetectedCategoryThresholdPercent()
        ));
        DumpSettings.setQueueMode(asBoolean(root.get("dumpQueueMode"), false));
        DumpSettings.setDumpRange(asInt(root.get("dumpRange"), 16));
        KeybindSettings.setOpenPickerKeyCode(asInt(root.get("openPickerKeyCode"), 66));
        KeybindSettings.setFindShortcutKeyCode(asInt(root.get("findShortcutKeyCode"), -1));
        KeybindSettings.setMoveToStorageKeyCode(asInt(root.get("moveToStorageKeyCode"), -1));
        KeybindSettings.setFindCycleKeyCode(asInt(root.get("findCycleKeyCode"), -1));
        MultiplayerWorldProfileSettings.replaceProfilesByServerScope(parseProfiles(root.get("multiplayerWorldProfiles")));
        ClientInputHandler.reloadFromSettings();

        LatchLabel.LOGGER.info("Reloaded client config from {}", configPath);
    }

    public Path configPath() {
        return configPath;
    }

    public void flushNow() {
        writeCurrentSettings();
    }

    public void setMultiplayerWorldProfile(String serverScopeId, String profileName) {
        MultiplayerWorldProfileSettings.setProfileForServerScope(serverScopeId, profileName);
        writeCurrentSettings();
    }

    public boolean clearMultiplayerWorldProfile(String serverScopeId) {
        boolean cleared = MultiplayerWorldProfileSettings.clearProfileForServerScope(serverScopeId);
        writeCurrentSettings();
        return cleared;
    }

    private void writeDefaults() {
        InspectSettings.setInspectRange(8);
        InspectSettings.setBordersAlwaysVisible(false);
        InspectSettings.setLabelsOnLook(false);
        FindSettings.setDefaultFindRadius(24);
        FindSettings.setVariantMatchingEnabled(true);
        FindSettings.setAllowSlashFCommand(true);
        FindSettings.setAllowFindKeybind(true);
        FindSettings.setFindHighlightTimeoutSeconds(60);
        TransferSettings.setMoveSourceMode(MoveSourceMode.INVENTORY);
        TransferSettings.setPullDropsOnGround(false);
        SortSettings.setSortMethod(com.latchandlabel.client.sort.SortMethod.REGISTRY_ID);
        SortSettings.setDropOverflowAtFeet(false);
        ContainerDetectionSettings.setDetectedCategoryThresholdPercent(ContainerDetectionSettings.defaultDetectedCategoryThresholdPercent());
        DumpSettings.setQueueMode(false);
        DumpSettings.setDumpRange(16);
        KeybindSettings.setOpenPickerKeyCode(66);
        KeybindSettings.setFindShortcutKeyCode(-1);
        KeybindSettings.setMoveToStorageKeyCode(-1);
        KeybindSettings.setFindCycleKeyCode(-1);
        MultiplayerWorldProfileSettings.replaceProfilesByServerScope(Map.of());
        ClientInputHandler.reloadFromSettings();
        writeCurrentSettings();

        LatchLabel.LOGGER.info("Created default client config at {}", configPath);
    }

    private void writeCurrentSettings() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("inspectRange", InspectSettings.inspectRange());
        root.addProperty("bordersAlwaysVisible", InspectSettings.bordersAlwaysVisible());
        root.addProperty("labelsOnLook", InspectSettings.labelsOnLook());
        root.addProperty("defaultFindRadius", FindSettings.defaultFindRadius());
        root.addProperty("variantMatchingEnabled", FindSettings.variantMatchingEnabled());
        root.addProperty("allowSlashFCommand", FindSettings.allowSlashFCommand());
        root.addProperty("allowFindKeybind", FindSettings.allowFindKeybind());
        root.addProperty("findHighlightTimeoutSeconds", FindSettings.findHighlightTimeoutSeconds());
        root.addProperty("moveSourceMode", TransferSettings.moveSourceMode().toConfigValue());
        root.addProperty("pullDropsOnGround", TransferSettings.pullDropsOnGround());
        root.addProperty("sortMethod", SortSettings.sortMethod().toConfigValue());
        root.addProperty("sortDropOverflowAtFeet", SortSettings.dropOverflowAtFeet());
        root.addProperty("detectedCategoryThresholdPercent", ContainerDetectionSettings.detectedCategoryThresholdPercent());
        root.addProperty("dumpQueueMode", DumpSettings.queueMode());
        root.addProperty("dumpRange", DumpSettings.dumpRange());
        root.addProperty("openPickerKeyCode", KeybindSettings.openPickerKeyCode());
        root.addProperty("findShortcutKeyCode", KeybindSettings.findShortcutKeyCode());
        root.addProperty("moveToStorageKeyCode", KeybindSettings.moveToStorageKeyCode());
        root.addProperty("findCycleKeyCode", KeybindSettings.findCycleKeyCode());
        JsonObject multiplayerWorldProfiles = new JsonObject();
        for (Map.Entry<String, String> entry : MultiplayerWorldProfileSettings.snapshotProfilesByServerScope().entrySet()) {
            multiplayerWorldProfiles.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("multiplayerWorldProfiles", multiplayerWorldProfiles);

        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing client config to temp file: " + tmp, e);
        }
        try {
            Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("Failed renaming temp config file to: " + configPath, e);
            }
        }
    }

    private static Path backupCorruptFile(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis() + ".bak");
        try {
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            LatchLabel.LOGGER.warn("Could not back up corrupt config {}: {}", path, ex.getMessage());
            return path;
        }
        return backup;
    }

    private static int asInt(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return element.getAsInt();
    }

    private static boolean asBoolean(JsonElement element, boolean fallback) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return element.getAsBoolean();
    }

    private static String asString(JsonElement element, String fallback) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return element.getAsString();
    }

    private static Map<String, String> parseProfiles(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }

        Map<String, String> parsed = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String profileName = asString(entry.getValue(), null);
            if (profileName == null || profileName.isBlank()) {
                continue;
            }
            parsed.put(entry.getKey(), profileName);
        }
        return parsed;
    }
}
