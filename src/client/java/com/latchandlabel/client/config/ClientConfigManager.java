package com.latchandlabel.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.find.FindResultState;
import com.latchandlabel.client.find.FindSettings;
import com.latchandlabel.client.LatchLabel;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
            LatchLabel.LOGGER.warn("Failed reading client config {}, rewriting defaults", configPath, e);
            writeDefaults();
            return;
        }

        int version = asInt(root.get("version"), -1);
        if (version != CURRENT_VERSION) {
            LatchLabel.LOGGER.warn("Unsupported client config version {} in {}", version, configPath);
        }

        InspectSettings.setInspectRange(asInt(root.get("inspectRange"), 8));
        InspectSettings.setActivationMode(InspectActivationMode.fromConfigValue(asString(root.get("inspectActivationMode"), "alt_or_shift")));
        FindSettings.setDefaultFindRadius(asInt(root.get("defaultFindRadius"), 24));
        FindResultState.setHighlightDurationSeconds(asInt(root.get("highlightDurationSeconds"), 10));
        FindSettings.setVariantMatchingEnabled(asBoolean(root.get("variantMatchingEnabled"), true));
        FindSettings.setEnableFindOverlayList(asBoolean(root.get("enableFindOverlayList"), false));
        FindSettings.setAllowSlashFCommand(asBoolean(root.get("allowSlashFCommand"), true));
        FindSettings.setAllowFindKeybind(asBoolean(root.get("allowFindKeybind"), true));
        TransferSettings.setMoveSourceMode(MoveSourceMode.fromConfigValue(asString(root.get("moveSourceMode"), "inventory")));
        KeybindSettings.setOpenPickerKeyCode(asInt(root.get("openPickerKeyCode"), 66));
        KeybindSettings.setFindShortcutKeyCode(asInt(root.get("findShortcutKeyCode"), -1));
        ClientInputHandler.reloadFromSettings();

        LatchLabel.LOGGER.info("Reloaded client config from {}", configPath);
    }

    public Path configPath() {
        return configPath;
    }

    public void flushNow() {
        writeCurrentSettings();
    }

    public void setHighlightDurationSeconds(int seconds) {
        int normalized = Math.max(1, seconds);
        FindResultState.setHighlightDurationSeconds(normalized);
        writeCurrentSettings();
    }

    private void writeDefaults() {
        InspectSettings.setInspectRange(8);
        InspectSettings.setActivationMode(InspectActivationMode.ALT_OR_SHIFT);
        FindSettings.setDefaultFindRadius(24);
        FindResultState.setHighlightDurationSeconds(10);
        FindSettings.setVariantMatchingEnabled(true);
        FindSettings.setEnableFindOverlayList(false);
        FindSettings.setAllowSlashFCommand(true);
        FindSettings.setAllowFindKeybind(true);
        TransferSettings.setMoveSourceMode(MoveSourceMode.INVENTORY);
        KeybindSettings.setOpenPickerKeyCode(66);
        KeybindSettings.setFindShortcutKeyCode(-1);
        ClientInputHandler.reloadFromSettings();
        writeCurrentSettings();

        LatchLabel.LOGGER.info("Created default client config at {}", configPath);
    }

    private void writeCurrentSettings() {
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("inspectRange", InspectSettings.inspectRange());
        root.addProperty("inspectActivationMode", InspectSettings.activationMode().toConfigValue());
        root.addProperty("defaultFindRadius", FindSettings.defaultFindRadius());
        root.addProperty("highlightDurationSeconds", FindResultState.getHighlightDurationSeconds());
        root.addProperty("enableFindOverlayList", FindSettings.enableFindOverlayList());
        root.addProperty("variantMatchingEnabled", FindSettings.variantMatchingEnabled());
        root.addProperty("allowSlashFCommand", FindSettings.allowSlashFCommand());
        root.addProperty("allowFindKeybind", FindSettings.allowFindKeybind());
        root.addProperty("moveSourceMode", TransferSettings.moveSourceMode().toConfigValue());
        root.addProperty("openPickerKeyCode", KeybindSettings.openPickerKeyCode());
        root.addProperty("findShortcutKeyCode", KeybindSettings.findShortcutKeyCode());

        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing client config: " + configPath, e);
        }
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
}
