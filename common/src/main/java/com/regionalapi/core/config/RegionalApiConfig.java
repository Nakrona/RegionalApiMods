package com.regionalapi.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.regionalapi.RegionalModConstants;
import com.regionalapi.api.RegionalApi;
import com.regionalapi.core.math.VoronoiEngine2D;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration manager for RegionalAPI.
 * <p>
 * Reads and writes {@code regionalapi.json} in the platform config directory.
 * Controls the Voronoi grid cell scale ({@code voronoiCellShift}), spatial generation mode,
 * and centralized world borders.
 */
public final class RegionalApiConfig {

    public static final String CONFIG_FILE_NAME = "regionalapi.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile boolean loaded = false;

    private int voronoiCellShift = 0; // 0 = auto-computed from region weight; 7..13 = manual shift
    private String generationMode = "INFINITE_TESSELLATION";
    private int maxFiniteRadiusBlocks = 10000;

    private RegionalApiConfig() {
    }

    /**
     * Loads {@code regionalapi.json} from the specified directory or creates a default one if absent.
     *
     * @param configDirectory The platform config directory path.
     */
    public static synchronized void loadOrCreate(Path configDirectory) {
        if (configDirectory == null) {
            return;
        }

        try {
            Files.createDirectories(configDirectory);
            Path configFile = configDirectory.resolve(CONFIG_FILE_NAME);

            RegionalApiConfig config = new RegionalApiConfig();

            if (Files.exists(configFile)) {
                try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    if (json.has("voronoiCellShift")) {
                        config.voronoiCellShift = json.get("voronoiCellShift").getAsInt();
                    }
                    if (json.has("generationMode")) {
                        config.generationMode = json.get("generationMode").getAsString();
                    }
                    if (json.has("maxFiniteRadiusBlocks")) {
                        config.maxFiniteRadiusBlocks = json.get("maxFiniteRadiusBlocks").getAsInt();
                    }
                } catch (Exception e) {
                    RegionalModConstants.LOGGER.error("Failed to parse {}, regenerating default configuration: {}",
                            CONFIG_FILE_NAME, e.getMessage());
                    saveConfig(configFile, config);
                }
            } else {
                saveConfig(configFile, config);
            }

            applyConfig(config);
            loaded = true;
        } catch (Exception e) {
            RegionalModConstants.LOGGER.error("Failed to load or initialize RegionalAPI configuration: {}", e.getMessage(), e);
        }
    }

    private static void saveConfig(Path configFile, RegionalApiConfig config) {
        try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
            JsonObject json = new JsonObject();
            json.addProperty("voronoiCellShift", config.voronoiCellShift);
            json.addProperty("generationMode", config.generationMode);
            json.addProperty("maxFiniteRadiusBlocks", config.maxFiniteRadiusBlocks);
            GSON.toJson(json, writer);
        } catch (IOException e) {
            RegionalModConstants.LOGGER.error("Failed to write {}: {}", CONFIG_FILE_NAME, e.getMessage());
        }
    }

    private static void applyConfig(RegionalApiConfig config) {
        RegionalApi.getInstance().setVoronoiCellShift(config.voronoiCellShift);

        VoronoiEngine2D.GenerationMode mode;
        try {
            mode = VoronoiEngine2D.GenerationMode.valueOf(config.generationMode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            RegionalModConstants.LOGGER.warn("Unknown generationMode '{}', defaulting to INFINITE_TESSELLATION",
                    config.generationMode);
            mode = VoronoiEngine2D.GenerationMode.INFINITE_TESSELLATION;
        }

        RegionalApi.getInstance().configureGenerationMode(mode, config.maxFiniteRadiusBlocks);
        RegionalModConstants.LOGGER.info("RegionalAPI configuration applied: voronoiCellShift={} ({}), generationMode={}, maxFiniteRadiusBlocks={}",
                config.voronoiCellShift,
                (config.voronoiCellShift <= 0 ? "AUTO" : "MANUAL"),
                mode,
                config.maxFiniteRadiusBlocks);
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
