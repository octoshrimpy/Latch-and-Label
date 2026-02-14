package com.latchandlabel.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.LatchLabelClientState;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigProfileManager {
    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path configDir;
    private final Path profilesDir;

    public ConfigProfileManager() {
        this.configDir = FabricLoader.getInstance().getConfigDir().resolve(LatchLabel.MOD_ID);
        this.profilesDir = configDir.resolve("profiles");
    }

    public Path exportProfile(String requestedName) {
        flushAll();
        ensureDirectories();

        Path outputPath = resolveProfilePath(requestedName, true);
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("exportedAt", Instant.now().toString());

        JsonObject filesObject = new JsonObject();
        for (Map.Entry<String, Path> entry : configFiles().entrySet()) {
            filesObject.add(entry.getKey(), readJsonObject(entry.getValue()));
        }
        root.add("files", filesObject);

        writeJsonObject(outputPath, root);
        return outputPath;
    }

    public Path importProfile(String requestedName) {
        ensureDirectories();
        Path inputPath = resolveProfilePath(requestedName, false);
        JsonObject root = readJsonObject(inputPath);

        JsonElement filesElement = root.get("files");
        if (filesElement == null || !filesElement.isJsonObject()) {
            throw new IllegalStateException("Profile is missing 'files' object: " + inputPath);
        }

        JsonObject filesObject = filesElement.getAsJsonObject();
        if (filesObject.isEmpty()) {
            throw new IllegalStateException("Profile 'files' object is empty: " + inputPath);
        }

        for (Map.Entry<String, Path> entry : configFiles().entrySet()) {
            JsonElement fileContent = filesObject.get(entry.getKey());
            if (fileContent == null || !fileContent.isJsonObject()) {
                continue;
            }
            writeJsonObject(entry.getValue(), fileContent.getAsJsonObject());
        }

        reloadAll();
        return inputPath;
    }

    private void flushAll() {
        LatchLabelClientState.clientConfigManager().flushNow();
        LatchLabelClientState.dataManager().flushNow();
        LatchLabelClientState.itemCategoryMappingService().flushNow();
    }

    private void reloadAll() {
        LatchLabelClientState.clientConfigManager().reload();
        LatchLabelClientState.dataManager().reloadFromDisk();
        LatchLabelClientState.itemCategoryMappingService().reload();
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(configDir);
            Files.createDirectories(profilesDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create profile directory: " + profilesDir, e);
        }
    }

    private Map<String, Path> configFiles() {
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("client_config.json", configDir.resolve("client_config.json"));
        files.put("categories.json", configDir.resolve("categories.json"));
        files.put("tags.json", configDir.resolve("tags.json"));
        files.put("item_categories_overrides.json", configDir.resolve("item_categories_overrides.json"));
        return files;
    }

    private Path resolveProfilePath(String requestedName, boolean forExport) {
        String fileName = sanitizeFileName(requestedName);
        Path candidate = Path.of(fileName);
        if (candidate.isAbsolute()) {
            throw new IllegalStateException("Absolute profile paths are not allowed: " + candidate);
        }

        Path normalizedProfilesDir = profilesDir.normalize();
        Path resolved = normalizedProfilesDir.resolve(candidate).normalize();
        if (!resolved.startsWith(normalizedProfilesDir)) {
            throw new IllegalStateException("Profile path must stay under " + normalizedProfilesDir);
        }

        if (!resolved.getFileName().toString().endsWith(".json")) {
            resolved = resolved.resolveSibling(resolved.getFileName() + ".json");
        }
        if (!forExport && !Files.exists(resolved)) {
            throw new IllegalStateException("Profile file not found: " + resolved);
        }
        return resolved;
    }

    private static String sanitizeFileName(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return "profile-" + FILE_STAMP.format(Instant.now()) + ".json";
        }
        return requestedName.trim();
    }

    private static JsonObject readJsonObject(Path path) {
        if (!Files.exists(path)) {
            return new JsonObject();
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                throw new IllegalStateException("Expected JSON object at root: " + path);
            }
            return element.getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading JSON: " + path, e);
        }
    }

    private static void writeJsonObject(Path path, JsonObject root) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create directory for " + path, e);
        }

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            throw new IllegalStateException("Failed writing JSON: " + path, e);
        }
    }
}
