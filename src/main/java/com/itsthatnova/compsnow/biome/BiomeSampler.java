package com.itsthatnova.compsnow.biome;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queries biome data to determine snow eligibility and intensity for block columns and chunks.
 *
 * Snow intensity is a float in [0.0, 1.0] derived from biome temperature at the sample Y:
 *
 *   raw       = clamp((SNOW_THRESHOLD - tempAtY) / COLD_RANGE, 0.0, 1.0)
 *   intensity = pow(raw, CURVE_EXPONENT)
 *
 * The curve pushes values toward 1.0 quickly once a biome crosses the snow threshold,
 * so always-snowy biomes (snowy plains, frozen peaks, etc.) produce intensity ≈ 1.0,
 * while transitional biomes (taiga at their snow line, windswept hills) produce lower
 * values that rise with altitude. This drives the shader's snowDriver uniform as a
 * continuous weight rather than a binary gate, giving LOD-to-vanilla visual continuity.
 *
 * Vanilla chunk classification uses four quarter-point samples; the intensity is the
 * average of whichever samples qualify as snowy, with 2-of-4 required to produce
 * a non-zero result.
 *
 * DH chunk classification samples the highest-Y data point at the chunk midpoint.
 */
public class BiomeSampler {
    /** 26.x Biome.getPrecipitationAt needs a sea level. Overworld default. HACK: not read from the level. */
    private static final int SEA_LEVEL = 63;


    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.biomesampler");

    // Temperature at or below which snow forms in vanilla.
    private static final float SNOW_THRESHOLD = 0.15f;
    // Temperature range from the threshold down to fully-snowy territory (temp = 0.0).
    // Biomes colder than 0.0 (frozen peaks, ice spikes) saturate at intensity 1.0.
    private static final float COLD_RANGE = 0.15f;
    // Power-curve exponent applied to the normalised cold fraction. Values below 1.0
    // push mid-range biomes toward higher intensity (faster rise off the snow line).
    private static final float CURVE_EXPONENT = 0.4f;

    private static final int SAMPLE_Y = 64;
    private static final int REQUIRED_SNOW_SAMPLES = 2;
    private static final int[][] CHUNK_SAMPLE_OFFSETS = {
            {4, 4},
            {12, 4},
            {4, 12},
            {12, 12}
    };
    private static final int DH_MIDPOINT_X = 8;
    private static final int DH_MIDPOINT_Z = 8;

    private static final Set<String> DH_TIER1_SNOWY_REGISTRY_KEYS = Set.of(
            "minecraft:snowy_plains",
            "minecraft:ice_spikes",
            "minecraft:snowy_taiga",
            "minecraft:taiga",
            "minecraft:snowy_beach",
            "minecraft:snowy_slopes",
            "minecraft:grove",
            "minecraft:jagged_peaks",
            "minecraft:frozen_peaks",
            "minecraft:frozen_river",
            "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean"
    );

    private static final Set<String> LOGGED_UNKNOWN_DH_BIOME_TYPES = ConcurrentHashMap.newKeySet();
    private static final int MAX_DH_MIDPOINT_BIOME_LOGS = 80;
    private static final AtomicInteger DH_MIDPOINT_BIOME_LOGS_EMITTED = new AtomicInteger();

    private static final AtomicLong DH_REPO_CALLS = new AtomicLong();
    private static final AtomicLong DH_REPO_NULL_RESULT = new AtomicLong();
    private static final AtomicLong DH_REPO_UNUSABLE_RESULT = new AtomicLong();
    private static final AtomicLong DH_REPO_EMPTY_CHUNK = new AtomicLong();
    private static final AtomicLong DH_REPO_NO_USABLE_BIOME = new AtomicLong();
    private static final AtomicLong DH_COLUMN_CHECKS = new AtomicLong();
    private static final AtomicLong DH_COLUMN_USABLE = new AtomicLong();
    private static final AtomicLong DH_CLASSIFIED_REGISTRY_ENTRY = new AtomicLong();
    private static final AtomicLong DH_CLASSIFIED_BIOME_OBJECT = new AtomicLong();
    private static final AtomicLong DH_CLASSIFIED_NAME_TRUE = new AtomicLong();
    private static final AtomicLong DH_CLASSIFIED_NAME_FALSE = new AtomicLong();
    private static final AtomicLong DH_NAME_ONLY_WOULD_BE_TRUE = new AtomicLong();
    private static final AtomicLong DH_NAME_ONLY_WOULD_BE_FALSE = new AtomicLong();
    private static final AtomicLong DH_NAME_ONLY_WOULD_BE_UNKNOWN = new AtomicLong();
    private static final AtomicLong DH_CLASSIFIED_UNKNOWN = new AtomicLong();
    private static final AtomicLong DH_CHUNK_SNOWY_TRUE = new AtomicLong();
    private static final AtomicLong DH_CHUNK_SNOWY_FALSE = new AtomicLong();

    public static final class DiagnosticsSnapshot {
        public final long repoCalls;
        public final long repoNullResult;
        public final long repoUnusableResult;
        public final long repoEmptyChunk;
        public final long repoNoUsableBiome;
        public final long columnChecks;
        public final long columnUsable;
        public final long classifiedRegistryEntry;
        public final long classifiedBiomeObject;
        public final long classifiedNameTrue;
        public final long classifiedNameFalse;
        public final long nameOnlyWouldBeTrue;
        public final long nameOnlyWouldBeFalse;
        public final long nameOnlyWouldBeUnknown;
        public final long classifiedUnknown;
        public final long chunkSnowyTrue;
        public final long chunkSnowyFalse;

        private DiagnosticsSnapshot(long repoCalls, long repoNullResult, long repoUnusableResult,
                                    long repoEmptyChunk, long repoNoUsableBiome, long columnChecks,
                                    long columnUsable, long classifiedRegistryEntry,
                                    long classifiedBiomeObject, long classifiedNameTrue,
                                    long classifiedNameFalse, long nameOnlyWouldBeTrue,
                                    long nameOnlyWouldBeFalse, long nameOnlyWouldBeUnknown,
                                    long classifiedUnknown, long chunkSnowyTrue, long chunkSnowyFalse) {
            this.repoCalls = repoCalls;
            this.repoNullResult = repoNullResult;
            this.repoUnusableResult = repoUnusableResult;
            this.repoEmptyChunk = repoEmptyChunk;
            this.repoNoUsableBiome = repoNoUsableBiome;
            this.columnChecks = columnChecks;
            this.columnUsable = columnUsable;
            this.classifiedRegistryEntry = classifiedRegistryEntry;
            this.classifiedBiomeObject = classifiedBiomeObject;
            this.classifiedNameTrue = classifiedNameTrue;
            this.classifiedNameFalse = classifiedNameFalse;
            this.nameOnlyWouldBeTrue = nameOnlyWouldBeTrue;
            this.nameOnlyWouldBeFalse = nameOnlyWouldBeFalse;
            this.nameOnlyWouldBeUnknown = nameOnlyWouldBeUnknown;
            this.classifiedUnknown = classifiedUnknown;
            this.chunkSnowyTrue = chunkSnowyTrue;
            this.chunkSnowyFalse = chunkSnowyFalse;
        }

        public boolean hasAnyData() {
            return repoCalls > 0 || columnChecks > 0 || classifiedRegistryEntry > 0
                    || classifiedBiomeObject > 0 || classifiedNameTrue > 0
                    || classifiedNameFalse > 0 || nameOnlyWouldBeTrue > 0
                    || nameOnlyWouldBeFalse > 0 || nameOnlyWouldBeUnknown > 0
                    || classifiedUnknown > 0;
        }
    }

    public static DiagnosticsSnapshot snapshotAndResetDhDiagnostics() {
        return new DiagnosticsSnapshot(
                DH_REPO_CALLS.getAndSet(0),
                DH_REPO_NULL_RESULT.getAndSet(0),
                DH_REPO_UNUSABLE_RESULT.getAndSet(0),
                DH_REPO_EMPTY_CHUNK.getAndSet(0),
                DH_REPO_NO_USABLE_BIOME.getAndSet(0),
                DH_COLUMN_CHECKS.getAndSet(0),
                DH_COLUMN_USABLE.getAndSet(0),
                DH_CLASSIFIED_REGISTRY_ENTRY.getAndSet(0),
                DH_CLASSIFIED_BIOME_OBJECT.getAndSet(0),
                DH_CLASSIFIED_NAME_TRUE.getAndSet(0),
                DH_CLASSIFIED_NAME_FALSE.getAndSet(0),
                DH_NAME_ONLY_WOULD_BE_TRUE.getAndSet(0),
                DH_NAME_ONLY_WOULD_BE_FALSE.getAndSet(0),
                DH_NAME_ONLY_WOULD_BE_UNKNOWN.getAndSet(0),
                DH_CLASSIFIED_UNKNOWN.getAndSet(0),
                DH_CHUNK_SNOWY_TRUE.getAndSet(0),
                DH_CHUNK_SNOWY_FALSE.getAndSet(0)
        );
    }

    private BiomeSampler() {}

    // -------------------------------------------------------------------------
    // Intensity helper
    // -------------------------------------------------------------------------

    /**
     * Converts a biome temperature value (already adjusted for altitude) into a
     * snow intensity in [0.0, 1.0].
     *
     * - Temperatures at or above SNOW_THRESHOLD (0.15) produce 0.0.
     * - Temperature 0.0 (snowy_plains, etc.) produces 1.0.
     * - Temperatures below 0.0 are clamped to 1.0.
     * - A power curve (CURVE_EXPONENT = 0.4) biases values toward 1.0 so that
     *   biomes near the snow line look fully covered rather than sparsely dusted.
     */
    public static float computeSnowIntensity(float temperatureAtY) {
        float raw = Math.min(1.0f, (SNOW_THRESHOLD - temperatureAtY) / COLD_RANGE);
        if (raw <= 0.0f) return 0.0f;
        return (float) Math.pow(raw, CURVE_EXPONENT);
    }

    // -------------------------------------------------------------------------
    // Vanilla chunk sampling
    // -------------------------------------------------------------------------

    /**
     * Returns the snow intensity [0.0, 1.0] for the chunk. Uses four quarter-point
     * samples; at least REQUIRED_SNOW_SAMPLES must be snowy for a non-zero result.
     * Intensity is the average of the qualifying samples' temperature-derived values.
     * Must be called from the main thread.
     */
    public static float isSnowEligibleChunk(ClientLevel world, int chunkX, int chunkZ) {
        if (world == null || !isOverworld(world)) return 0.0f;

        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel serverWorld = server != null ? server.getLevel(Level.OVERWORLD) : null;

        float totalIntensity = 0.0f;
        int snowySamples = 0;

        for (int[] offset : CHUNK_SAMPLE_OFFSETS) {
            int worldX = (chunkX << 4) + offset[0];
            int worldZ = (chunkZ << 4) + offset[1];
            float intensity = serverWorld != null
                    ? snowIntensityAt(serverWorld, worldX, worldZ)
                    : snowIntensityAt(world, worldX, worldZ);
            if (intensity > 0.0f) {
                totalIntensity += intensity;
                snowySamples++;
            }
        }

        if (snowySamples < REQUIRED_SNOW_SAMPLES) return 0.0f;
        return totalIntensity / snowySamples;
    }

    /**
     * Reads chunk snow intensity directly from DH's terrain repo.
     *
     * Samples the chunk midpoint, resolves the biome, and returns an intensity
     * in [0.0, 1.0] derived from the biome's temperature at the terrain surface Y.
     * Returns null when DH could not provide a usable biome for that midpoint yet.
     */
    public static Float isSnowEligibleDhChunk(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ) {
        if (levelWrapper == null || DhApi.Delayed.terrainRepo == null) {
            return null;
        }

        DH_REPO_CALLS.incrementAndGet();
        DhApiResult<DhApiTerrainDataPoint[][][]> result;
        try {
            result = DhApi.Delayed.terrainRepo.getAllTerrainDataAtChunkPos(levelWrapper, chunkX, chunkZ, null /* no DH cache */);
        } catch (Exception e) {
            LOGGER.debug("DH terrain repo threw while reading chunk ({}, {}): {}", chunkX, chunkZ, e.getMessage());
            return null;
        }

        if (result == null) {
            DH_REPO_NULL_RESULT.incrementAndGet();
            return null;
        }
        if (!result.success || result.payload == null) {
            DH_REPO_UNUSABLE_RESULT.incrementAndGet();
            return null;
        }

        DhApiTerrainDataPoint[][][] chunkData = result.payload;
        if (chunkData.length == 0) {
            DH_REPO_EMPTY_CHUNK.incrementAndGet();
            return null;
        }

        int relX = clamp(DH_MIDPOINT_X, 0, chunkData.length - 1);
        DhApiTerrainDataPoint[][] zSlice = chunkData[relX];
        if (zSlice == null || zSlice.length == 0) {
            DH_REPO_NO_USABLE_BIOME.incrementAndGet();
            return null;
        }

        int relZ = clamp(DH_MIDPOINT_Z, 0, zSlice.length - 1);
        DhApiTerrainDataPoint[] column = zSlice[relZ];
        if (column == null || column.length == 0) {
            DH_REPO_NO_USABLE_BIOME.incrementAndGet();
            return null;
        }

        DH_COLUMN_CHECKS.incrementAndGet();
        Float intensity = intensityForDhMidpointColumn(chunkX, chunkZ, column);
        if (intensity == null) {
            DH_REPO_NO_USABLE_BIOME.incrementAndGet();
            return null;
        }

        DH_COLUMN_USABLE.incrementAndGet();
        if (intensity > 0.0f) {
            DH_CHUNK_SNOWY_TRUE.incrementAndGet();
        } else {
            DH_CHUNK_SNOWY_FALSE.incrementAndGet();
        }
        return intensity;
    }

    /**
     * Server-world variant retained for fallback use.
     */
    public static float isSnowEligibleServer(ServerLevel serverWorld, int chunkX, int chunkZ) {
        if (serverWorld == null) return 0.0f;
        float totalIntensity = 0.0f;
        int snowySamples = 0;
        for (int[] offset : CHUNK_SAMPLE_OFFSETS) {
            int worldX = (chunkX << 4) + offset[0];
            int worldZ = (chunkZ << 4) + offset[1];
            float intensity = snowIntensityAt(serverWorld, worldX, worldZ);
            if (intensity > 0.0f) {
                totalIntensity += intensity;
                if (++snowySamples >= REQUIRED_SNOW_SAMPLES && totalIntensity > 0) {
                    // Early out once threshold met — return running average
                    return totalIntensity / snowySamples;
                }
            }
        }
        if (snowySamples < REQUIRED_SNOW_SAMPLES) return 0.0f;
        return totalIntensity / snowySamples;
    }

    // -------------------------------------------------------------------------
    // DH midpoint classification
    // -------------------------------------------------------------------------

    private static Float intensityForDhMidpointColumn(int chunkX, int chunkZ, DhApiTerrainDataPoint[] column) {
        IDhApiBiomeWrapper biomeWrapper = null;
        int bestTopY = Integer.MIN_VALUE;

        for (DhApiTerrainDataPoint point : column) {
            if (point == null || point.biomeWrapper == null) continue;
            int topY = point.topYBlockPos;
            if (biomeWrapper == null || topY > bestTopY) {
                biomeWrapper = point.biomeWrapper;
                bestTopY = topY;
            }
        }

        if (biomeWrapper == null) return null;

        // Use the actual terrain surface Y for more accurate altitude-dependent temperature.
        int sampleY = (bestTopY == Integer.MIN_VALUE) ? SAMPLE_Y : bestTopY;
        return intensityForDhBiome(chunkX, chunkZ, biomeWrapper, sampleY);
    }

    private static Float intensityForDhBiome(int chunkX, int chunkZ,
                                              IDhApiBiomeWrapper biomeWrapper, int sampleY) {
        String biomeName = null;
        try { biomeName = biomeWrapper.getName(); } catch (Exception ignored) {}

        Object wrapped = null;
        try { wrapped = biomeWrapper.getWrappedMcObject(); } catch (Exception ignored) {}

        String wrappedType = wrapped == null ? "null" : wrapped.getClass().getName();
        String registryKeyString = extractRegistryKeyString(wrapped);

        // --- Pass 1: tier1 allowlist via registry key string ---
        Boolean tier1Decision = classifyByTier1RegistryKey(registryKeyString);

        // --- Pass 2: tier1 allowlist via biome name ---
        if (tier1Decision == null && !isGenericBiomeName(biomeName)) {
            tier1Decision = classifyByTier1RegistryKey(biomeName);
        }

        // Tier1 confirmed snowy biomes get full intensity immediately — no need to
        // query precipitation or temperature for frozen_peaks, snowy_plains, etc.
        // Tier1 FALSE does NOT short-circuit: in winter, a seasons mod can force
        // SNOW precipitation on biomes like forest or plains whose temperature
        // remains warm. We fall through to the precipitation check so those
        // biomes draw snow when the seasons mod says they should.
        if (tier1Decision != null && tier1Decision) {
            DH_NAME_ONLY_WOULD_BE_TRUE.incrementAndGet();
            DH_CLASSIFIED_NAME_TRUE.incrementAndGet();
            emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, 1.0f, "tier1-registry-key");
            return 1.0f;
        }
        // Log tier1 false as a hint but continue to precipitation check below.
        if (tier1Decision != null) {
            DH_NAME_ONLY_WOULD_BE_FALSE.incrementAndGet();
        }

        // --- Pass 3: Holder value fallback ---
        // In multiplayer, biomes arrive as Holder.Direct (no key, raw Biome value).
        // registryEntry.value() gives the full Biome object with intact climate data.
        if (wrapped instanceof Holder<?> registryEntry) {
            Object value = registryEntry.value();
            if (value instanceof Biome biome) {
                boolean precipSnow = biome.getPrecipitationAt(new BlockPos(0, sampleY, 0), SEA_LEVEL) == Biome.Precipitation.SNOW;
                if (!precipSnow) {
                    DH_CLASSIFIED_REGISTRY_ENTRY.incrementAndGet();
                    emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, 0.0f, "registry-entry-fallback");
                    return 0.0f;
                }
                float temp = altitudeAdjustedTemp(biome.getBaseTemperature(), sampleY);
                float intensity = computeSnowIntensity(temp);
                // Seasons mod may force SNOW precipitation on a warm biome; fall back to 1.0.
                if (intensity <= 0.0f) intensity = 1.0f;
                DH_CLASSIFIED_REGISTRY_ENTRY.incrementAndGet();
                emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, intensity, "registry-entry-fallback");
                return intensity;
            }
        }

        // --- Pass 4: raw Biome object fallback ---
        if (wrapped instanceof Biome biome) {
            boolean precipSnow = biome.getPrecipitationAt(new BlockPos(0, sampleY, 0), SEA_LEVEL) == Biome.Precipitation.SNOW;
            if (!precipSnow) {
                DH_CLASSIFIED_BIOME_OBJECT.incrementAndGet();
                emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, 0.0f, "biome-object-fallback");
                return 0.0f;
            }
            float temp = altitudeAdjustedTemp(biome.getBaseTemperature(), sampleY);
            float intensity = computeSnowIntensity(temp);
            if (intensity <= 0.0f) intensity = 1.0f;
            DH_CLASSIFIED_BIOME_OBJECT.incrementAndGet();
            emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, intensity, "biome-object-fallback");
            return intensity;
        }

        // --- Pass 5: nothing worked ---
        if (isUnknownRegistryKey(registryKeyString)) {
            DH_NAME_ONLY_WOULD_BE_UNKNOWN.incrementAndGet();
            emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, null, "no-key-retry");
            return null;
        }

        DH_NAME_ONLY_WOULD_BE_UNKNOWN.incrementAndGet();
        DH_CLASSIFIED_UNKNOWN.incrementAndGet();
        emitDhMidpointBiomeLog(chunkX, chunkZ, biomeName, registryKeyString, wrappedType, null, "unknown");
        if (LOGGED_UNKNOWN_DH_BIOME_TYPES.add(wrappedType)) {
            LOGGER.warn("Unrecognized DH biome wrapper object type '{}' — midpoint DH snow test could not classify it cleanly", wrappedType);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String extractRegistryKeyString(Object wrapped) {
        if (wrapped instanceof Holder<?> registryEntry) {
            return registryEntry.unwrapKey().map(key -> key.identifier().toString()).orElse("<no-key>");
        }
        return null;
    }

    private static boolean isGenericBiomeName(String biomeName) {
        if (biomeName == null || biomeName.isBlank()) return true;
        return biomeName.toLowerCase(Locale.ROOT).trim().equals("minecraft:worldgen/biome");
    }

    private static boolean isUnknownRegistryKey(String registryKeyString) {
        if (registryKeyString == null) return false;
        String n = registryKeyString.toLowerCase(Locale.ROOT).trim();
        return n.equals("<no-key>") || n.equals("<null>");
    }

    private static Boolean classifyByTier1RegistryKey(String textIdentity) {
        if (textIdentity == null || textIdentity.isBlank()) return null;
        String normalized = textIdentity.toLowerCase(Locale.ROOT).trim();
        if (DH_TIER1_SNOWY_REGISTRY_KEYS.contains(normalized)) return true;
        if (normalized.contains(":")) return false;
        return null;
    }

    private static void emitDhMidpointBiomeLog(int chunkX, int chunkZ, String biomeName,
                                               String registryKeyString, String wrappedType,
                                               Float finalIntensity, String decisionSource) {
        int index = DH_MIDPOINT_BIOME_LOGS_EMITTED.getAndIncrement();
        if (index >= MAX_DH_MIDPOINT_BIOME_LOGS) return;
        LOGGER.warn(
                "DH midpoint biome [{}] chunk=({}, {}) biomeName='{}' registryKey='{}' wrappedType='{}' tier1KeyDecision={} finalIntensity={} source={} ",
                index + 1, chunkX, chunkZ,
                biomeName == null ? "<null>" : biomeName,
                registryKeyString == null ? "<null>" : registryKeyString,
                wrappedType,
                Objects.requireNonNullElseGet(classifyByTier1RegistryKey(registryKeyString),
                        () -> classifyByTier1RegistryKey(biomeName)),
                finalIntensity == null ? "<null>" : String.format("%.3f", finalIntensity),
                decisionSource
        );
    }

    /** Returns snow intensity at a single block column via ClientLevel. */
    private static float snowIntensityAt(ClientLevel world, int worldX, int worldZ) {
        try {
            BlockPos pos = new BlockPos(worldX, SAMPLE_Y, worldZ);
            Biome biome = world.getBiome(pos).value();
            if (biome.getPrecipitationAt(pos, SEA_LEVEL) != Biome.Precipitation.SNOW) return 0.0f;
            // Compute intensity from temperature. If precipitation says SNOW but
            // temperature is warm (seasons mod override), fall back to 1.0 so
            // the season-forced snow still draws at full coverage.
            float intensity = computeSnowIntensity(altitudeAdjustedTemp(biome.getBaseTemperature(), pos.getY()));
            return intensity > 0.0f ? intensity : 1.0f;
        } catch (Exception e) {
            return 0.0f;
        }
    }

    /** Returns snow intensity at a single block column via ServerLevel. */
    private static float snowIntensityAt(ServerLevel world, int worldX, int worldZ) {
        try {
            BlockPos pos = new BlockPos(worldX, SAMPLE_Y, worldZ);
            Biome biome = world.getBiome(pos).value();
            if (biome.getPrecipitationAt(pos, SEA_LEVEL) != Biome.Precipitation.SNOW) return 0.0f;
            float intensity = computeSnowIntensity(altitudeAdjustedTemp(biome.getBaseTemperature(), pos.getY()));
            return intensity > 0.0f ? intensity : 1.0f;
        } catch (Exception e) {
            return 0.0f;
        }
    }

    private static boolean isOverworld(ClientLevel world) {
        return Level.OVERWORLD.equals(world.dimension());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Replicates Biome#getTemperatureAt: adjusts the biome's base temperature
     * downward for altitude above Y=64 at the vanilla rate of 0.00166667 per block.
     * This matches exactly how vanilla determines whether snow forms at a given Y.
     */
    private static float altitudeAdjustedTemp(float baseTemp, int y) {
        return baseTemp - Math.max(0.0f, (y - 64) * 0.00166667f);
    }
}
