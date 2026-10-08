package com.itsthatnova.compsnow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * JSON-backed config for resolved snow variant textures and DH LOD material textures.
 */
public final class SnowVariantConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "compsnow_snow_variants.json";
    public static final int VARIANT_SLOTS = 4;
    public static final int DH_GRASS_VARIANT_SLOTS = 8;

    public static final List<String> DEFAULT_DH_GRASS_VARIANTS = List.of(
            "minecraft:textures/block/nature/grass/gt_1.png",
            "minecraft:textures/block/nature/grass/gt_2.png",
            "minecraft:textures/block/nature/grass/gt_3.png",
            "minecraft:textures/block/nature/grass/gt_4.png",
            "minecraft:textures/block/nature/grass/gt_5.png",
            "minecraft:textures/block/nature/grass/gt_6.png",
            "minecraft:textures/block/nature/grass/gt_7.png",
            "minecraft:textures/block/nature/grass/gt_8.png"
    );
    public static final String DEFAULT_DH_SNOW_PATH = "minecraft:textures/block/nature/snow/snow_1.png";
    public static final String DEFAULT_DH_DIRT_PATH = "minecraft:textures/block/soils/dirt/dirt.png";
    public static final String DEFAULT_DH_STONE_PATH = "minecraft:textures/block/stone/stone_top_1.png";
    public static final String DEFAULT_DH_DEEPSLATE_PATH = "minecraft:textures/block/stone/deepslate/top.png";
    public static final String DEFAULT_DH_SAND_PATH = "minecraft:textures/block/soils/sand/sand.png";

    public enum Mode {
        @SerializedName("Auto")
        AUTO,
        @SerializedName("Manual_Override")
        MANUAL_OVERRIDE;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) return AUTO;
            String normalized = raw.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if ("MANUAL_OVERRIDE".equals(normalized)) {
                return MANUAL_OVERRIDE;
            }
            return AUTO;
        }

        public String configValue() {
            return this == MANUAL_OVERRIDE ? "Manual_Override" : "Auto";
        }
    }

    public static final class DhLodTextures {
        private final boolean enabled;
        private final List<String> grassVariants;
        private final String snow;
        private final String dirt;
        private final String stone;
        private final String deepslate;
        private final String sand;

        public DhLodTextures(boolean enabled, List<String> grassVariants, String snow, String dirt,
                             String stone, String deepslate, String sand) {
            this.enabled = enabled;
            this.grassVariants = List.copyOf(grassVariants);
            this.snow = snow;
            this.dirt = dirt;
            this.stone = stone;
            this.deepslate = deepslate;
            this.sand = sand;
        }

        public static DhLodTextures defaults() {
            return new DhLodTextures(
                    true,
                    new ArrayList<>(DEFAULT_DH_GRASS_VARIANTS),
                    DEFAULT_DH_SNOW_PATH,
                    DEFAULT_DH_DIRT_PATH,
                    DEFAULT_DH_STONE_PATH,
                    DEFAULT_DH_DEEPSLATE_PATH,
                    DEFAULT_DH_SAND_PATH
            );
        }

        public boolean isEnabled() { return enabled; }
        public List<String> getGrassVariants() { return grassVariants; }
        public String getSnow() { return snow; }
        public String getDirt() { return dirt; }
        public String getStone() { return stone; }
        public String getDeepslate() { return deepslate; }
        public String getSand() { return sand; }
    }

    private static final class Data {
        String mode = Mode.AUTO.configValue();
        List<String> manualVariants = new ArrayList<>(Arrays.asList("", "", "", ""));
        boolean debugLogging = false;
        DhLodTexturesData dhLodTextures = DhLodTexturesData.defaults();
    }

    private static final class DhLodTexturesData {
        boolean enabled = true;
        String grass = DEFAULT_DH_GRASS_VARIANTS.get(0); // legacy single-slot compatibility
        List<String> grassVariants = new ArrayList<>(DEFAULT_DH_GRASS_VARIANTS);
        String snow = DEFAULT_DH_SNOW_PATH;
        String dirt = DEFAULT_DH_DIRT_PATH;
        String stone = DEFAULT_DH_STONE_PATH;
        String deepslate = DEFAULT_DH_DEEPSLATE_PATH;
        String sand = DEFAULT_DH_SAND_PATH;

        static DhLodTexturesData defaults() {
            return new DhLodTexturesData();
        }
    }

    private final Mode mode;
    private final List<String> manualVariants;
    private final boolean debugLogging;
    private final DhLodTextures dhLodTextures;

    private SnowVariantConfig(Mode mode, List<String> manualVariants, boolean debugLogging, DhLodTextures dhLodTextures) {
        this.mode = mode;
        this.manualVariants = manualVariants;
        this.debugLogging = debugLogging;
        this.dhLodTextures = dhLodTextures;
    }

    public static SnowVariantConfig loadOrCreate() {
        Path path = getConfigPath();

        if (Files.notExists(path)) {
            SnowVariantConfig defaults = defaults();
            defaults.save();
            LOGGER.warn("Created default snow variant config at {}", path.toAbsolutePath());
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            SnowVariantConfig config = fromData(data);
            config.save();
            LOGGER.warn("Loaded snow variant config: mode={}, manualVariants={}, dhLodTexturesEnabled={}, dhGrassVariants={}",
                    config.mode.configValue(), config.manualVariants, config.dhLodTextures.isEnabled(), config.dhLodTextures.getGrassVariants().size());
            return config;
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("Failed to read snow variant config at {}: {}. Recreating defaults.",
                    path.toAbsolutePath(), e.getMessage());
            SnowVariantConfig defaults = defaults();
            defaults.save();
            return defaults;
        }
    }

    public void save() {
        Path path = getConfigPath();
        Data data = new Data();
        data.mode = mode.configValue();
        data.manualVariants = new ArrayList<>(manualVariants);
        data.debugLogging = debugLogging;
        data.dhLodTextures.enabled = dhLodTextures.isEnabled();
        data.dhLodTextures.grassVariants = new ArrayList<>(dhLodTextures.getGrassVariants());
        data.dhLodTextures.grass = !dhLodTextures.getGrassVariants().isEmpty()
                ? dhLodTextures.getGrassVariants().get(0)
                : DEFAULT_DH_GRASS_VARIANTS.get(0);
        data.dhLodTextures.snow = dhLodTextures.getSnow();
        data.dhLodTextures.dirt = dhLodTextures.getDirt();
        data.dhLodTextures.stone = dhLodTextures.getStone();
        data.dhLodTextures.deepslate = dhLodTextures.getDeepslate();
        data.dhLodTextures.sand = dhLodTextures.getSand();

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save snow variant config at {}: {}", path.toAbsolutePath(), e.getMessage());
        }
    }

    private static SnowVariantConfig fromData(Data data) {
        if (data == null) {
            return defaults();
        }

        Mode mode = Mode.parse(data.mode);
        List<String> variants = new ArrayList<>(VARIANT_SLOTS);
        List<String> source = data.manualVariants != null ? data.manualVariants : List.of();

        for (int i = 0; i < VARIANT_SLOTS; i++) {
            String value = i < source.size() && source.get(i) != null ? source.get(i).trim() : "";
            variants.add(value);
        }

        DhLodTextures dhLodTextures = fromDhData(data.dhLodTextures);
        return new SnowVariantConfig(mode, variants, data.debugLogging, dhLodTextures);
    }

    private static DhLodTextures fromDhData(DhLodTexturesData data) {
        DhLodTextures defaults = DhLodTextures.defaults();
        if (data == null) return defaults;

        List<String> grassVariants = normalizeGrassVariants(data, defaults.getGrassVariants());
        return new DhLodTextures(
                data.enabled,
                grassVariants,
                sanitizePath(data.snow, defaults.getSnow()),
                sanitizePath(data.dirt, defaults.getDirt()),
                sanitizePath(data.stone, defaults.getStone()),
                sanitizePath(data.deepslate, defaults.getDeepslate()),
                sanitizePath(data.sand, defaults.getSand())
        );
    }

    private static List<String> normalizeGrassVariants(DhLodTexturesData data, List<String> defaults) {
        List<String> normalized = new ArrayList<>(DH_GRASS_VARIANT_SLOTS);
        List<String> source = data.grassVariants != null ? data.grassVariants : List.of();

        for (int i = 0; i < DH_GRASS_VARIANT_SLOTS; i++) {
            String fallback = i < defaults.size() ? defaults.get(i) : defaults.get(0);
            String value = i < source.size() && source.get(i) != null ? source.get(i).trim() : "";
            if (value.isEmpty()) {
                if (i == 0 && data.grass != null && !data.grass.trim().isEmpty()) {
                    value = data.grass.trim();
                } else {
                    value = fallback;
                }
            }
            normalized.add(value);
        }

        return normalized;
    }

    private static String sanitizePath(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    public static SnowVariantConfig defaults() {
        return new SnowVariantConfig(
                Mode.AUTO,
                new ArrayList<>(Arrays.asList("", "", "", "")),
                false,
                DhLodTextures.defaults()
        );
    }

    public static Path getConfigPath() {
        Minecraft client = Minecraft.getInstance();
        Path baseDir = client != null ? client.gameDirectory.toPath() : Path.of(".");
        return baseDir.resolve("config").resolve(FILE_NAME);
    }

    public Mode getMode() { return mode; }
    public List<String> getManualVariants() { return manualVariants; }
    public boolean isDebugLogging() { return debugLogging; }
    public DhLodTextures getDhLodTextures() { return dhLodTextures; }
}
