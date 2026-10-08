package com.itsthatnova.compsnow.events;

import com.itsthatnova.compsnow.config.SnowVariantConfig;
import com.itsthatnova.compsnow.integration.SeasonCacheBridge;
import com.itsthatnova.compsnow.texture.DhLodTextureResolver;
import com.itsthatnova.compsnow.texture.DhLodTextureSet;
import com.itsthatnova.compsnow.texture.ResolvedDhLodTextureSet;
import com.itsthatnova.compsnow.texture.ResolvedSnowVariantSet;
import com.itsthatnova.compsnow.texture.SnowBiomeTexture;
import com.itsthatnova.compsnow.texture.SnowVariantResolver;
import com.itsthatnova.compsnow.texture.SnowVariantTextureSet;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Central coordinator for Nova Reimagined Snow.
 */
public class SnowBiomeManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.manager");
    private static final Logger WEATHER_LOGGER = LoggerFactory.getLogger("compsnow");
    public static final SnowBiomeManager INSTANCE = new SnowBiomeManager();

    private static final float DEBUG_EPSILON = 0.000001f;
    private static final float ACTIVE_THRESHOLD = 0.001f;
    private static final int AUTHORITATIVE_PROBE_TICKS = 60;

    // Re-anchor policy constants.
    // The texture covers ANCHOR_RADIUS chunks in each direction from the anchor.
    // If the player drifts more than RE_ANCHOR_THRESHOLD chunks from the anchor,
    // the texture is recentered on the next periodic check.
    // Teleports (sudden movement > TELEPORT_THRESHOLD chunks in one tick) trigger
    // an immediate re-anchor without waiting for the periodic interval.
    private static final int ANCHOR_RADIUS           = SnowBiomeTexture.HALF_SIZE; // 128
    private static final int RE_ANCHOR_INTERVAL_TICKS = 20;  // check every second
    private static final int RE_ANCHOR_THRESHOLD      = SnowBiomeTexture.HALF_SIZE / 2; // re-anchor at halfway point
    private static final int TELEPORT_THRESHOLD       = 8;    // chunks/tick sudden jump

    private enum RuntimeMode {
        STANDALONE,
        AUTHORITATIVE_PENDING,
        AUTHORITATIVE_ACTIVE
    }

    private final SnowBiomeTexture texture = new SnowBiomeTexture();
    private final ChunkUpdateQueue queue = new ChunkUpdateQueue();
    private final CacheManager cacheManager = new CacheManager();
    private final DhChunkScanner dhScanner = new DhChunkScanner();

    private final SnowVariantResolver snowVariantResolver = new SnowVariantResolver();
    private final SnowVariantTextureSet snowVariantTextures = new SnowVariantTextureSet();
    private final DhLodTextureResolver dhLodTextureResolver = new DhLodTextureResolver();
    private final DhLodTextureSet dhLodTextures = new DhLodTextureSet();
    private SnowVariantConfig snowVariantConfig = SnowVariantConfig.defaults();

    private int lastRenderDistance = -1;
    private boolean needsUpload = false;
    private RuntimeMode runtimeMode = RuntimeMode.STANDALONE;
    private boolean seasonCacheBridgeAvailable = false;
    private int authoritativeProbeTicksRemaining = 0;
    private Boolean lastSnapshotInProgress = null;
    private Integer lastAuthoritativeEpoch = null;
    private long authoritativeChunkEventsApplied = 0;
    private long authoritativeResetsApplied = 0;

    // Player-relative anchor tracking.
    // anchorChunkX/Z: the chunk the texture is currently centered on.
    // lastPlayerChunkX/Z: player chunk last tick, for teleport detection.
    // reAnchorTickCounter: countdown to next periodic drift check.
    private int anchorChunkX = 0;
    private int anchorChunkZ = 0;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;

    private float currentRainGradient = 0.0f;
    private float currentThunderGradient = 0.0f;
    private boolean currentIsRaining = false;
    private boolean currentIsThundering = false;

    private float lastRainGradient = Float.NaN;
    private float lastThunderGradient = Float.NaN;
    private boolean lastIsRaining = false;
    private boolean lastIsThundering = false;
    private boolean lastWeatherActive = false;

    private int weatherDebugBurstTicks = 0;
    private long weatherSampleIndex = 0;

    private SnowBiomeManager() {
        LOGGER.warn("Nova Reimagined Snow manager init (DH mod present: {}, API ready: {}, Season Cache bridge: {})",
                DhLevelResolver.isDhModPresent(), DhLevelResolver.isDhApiReady(), SeasonCacheBridge.isAvailable());
    }

    public synchronized void initializeSnowVariantConfig() {
        snowVariantConfig = SnowVariantConfig.loadOrCreate();
    }

    public void refreshSnowVariantTextures(String reason) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            LOGGER.warn("Skipping snow variant refresh [{}] because Minecraft is null", reason);
            return;
        }

        ResourceManager resourceManager = client.getResourceManager();
        if (resourceManager == null) {
            LOGGER.warn("Skipping snow variant refresh [{}] because ResourceManager is null", reason);
            return;
        }

        refreshSnowVariantTextures(reason, resourceManager);
    }

    public void refreshSnowVariantTextures(String reason, ResourceManager resourceManager) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || resourceManager == null) {
            LOGGER.warn("Skipping snow variant refresh [{}] because client or resource manager was null", reason);
            return;
        }

        client.execute(() -> refreshSnowVariantTexturesNow(reason, resourceManager));
    }

    private synchronized void refreshSnowVariantTexturesNow(String reason, ResourceManager resourceManager) {
        try {
            snowVariantConfig = SnowVariantConfig.loadOrCreate();
            ResolvedSnowVariantSet resolvedSnow = snowVariantResolver.resolve(resourceManager, snowVariantConfig);
            snowVariantTextures.replace(resolvedSnow, reason);

            ResolvedDhLodTextureSet resolvedDh = dhLodTextureResolver.resolve(resourceManager, snowVariantConfig);
            dhLodTextures.replace(resolvedDh, reason);
        } catch (Exception e) {
            LOGGER.warn("Failed to rebuild resolved snow variants / DH LOD textures [{}]; keeping previous textures. {}",
                    reason, e.getMessage());
        }
    }

    public void onWorldJoin(ClientLevel world, String worldKey) {
        int vanillaRenderDist = getVanillaRenderDistance();
        boolean dhPresent = DhLevelResolver.isDhModPresent();
        seasonCacheBridgeAvailable = SeasonCacheBridge.isAvailable();
        runtimeMode = seasonCacheBridgeAvailable ? RuntimeMode.AUTHORITATIVE_PENDING : RuntimeMode.STANDALONE;
        authoritativeProbeTicksRemaining = seasonCacheBridgeAvailable ? AUTHORITATIVE_PROBE_TICKS : 0;
        lastSnapshotInProgress = null;
        lastAuthoritativeEpoch = null;
        authoritativeChunkEventsApplied = 0;
        authoritativeResetsApplied = 0;

        // Capture player chunk as the initial anchor so the texture is centered
        // on the player from the very first allocation and meta upload.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            anchorChunkX = (int) Math.floor(mc.player.getX()) >> 4;
            anchorChunkZ = (int) Math.floor(mc.player.getZ()) >> 4;
        } else {
            anchorChunkX = 0;
            anchorChunkZ = 0;
        }
        lastPlayerChunkX = anchorChunkX;
        lastPlayerChunkZ = anchorChunkZ;
        reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;

        // Set anchor on the texture BEFORE allocate so the first meta upload
        // carries the correct anchor chunk coordinates.
        texture.setAnchor(anchorChunkX, anchorChunkZ);

        int texSize = dhPresent ? SnowBiomeTexture.MAX_SIZE : computeTextureSize(vanillaRenderDist);
        lastRenderDistance = vanillaRenderDist;

        texture.allocate(texSize);
        cacheManager.onWorldJoin(worldKey);
        resetWeatherDebugState();
        refreshSnowVariantTextures("world join");

        queue.clear();

        if (runtimeMode == RuntimeMode.STANDALONE) {
            restoreCachedTexture("world join");
            queue.enqueueAll(texture);
            startDhServicesIfNeeded();
        } else {
            texture.clear();
            markTextureDirty("awaiting authoritative season cache snapshot");
            LOGGER.warn("Season Cache bridge detected; delaying DH startup while awaiting authoritative session");
        }

        LOGGER.warn("Level joined — texture {}x{} chunks, {} in cache, runtimeMode={}, DH mod present: {}, API ready: {}, anchor=[{},{}]",
                texSize, texSize, cacheManager.cacheSize(), runtimeMode, dhPresent, DhLevelResolver.isDhApiReady(),
                anchorChunkX, anchorChunkZ);
    }

    public void onWorldLeave() {
        stopDhServicesIfNeeded();
        queue.clear();
        texture.release();
        cacheManager.onWorldLeave();
        resetWeatherDebugState();
        lastRenderDistance = -1;
        needsUpload = false;
        runtimeMode = RuntimeMode.STANDALONE;
        seasonCacheBridgeAvailable = false;
        authoritativeProbeTicksRemaining = 0;
        lastSnapshotInProgress = null;
        lastAuthoritativeEpoch = null;
        authoritativeChunkEventsApplied = 0;
        authoritativeResetsApplied = 0;
        anchorChunkX = 0;
        anchorChunkZ = 0;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;
        LOGGER.warn("Level left — biome textures released, cache flushed");
    }

    public void onDimensionChange(ClientLevel newWorld) {
        stopDhServicesIfNeeded();
        queue.clear();
        resetWeatherDebugState();

        if (!texture.isAllocated()) {
            return;
        }

        seasonCacheBridgeAvailable = SeasonCacheBridge.isAvailable();
        runtimeMode = seasonCacheBridgeAvailable ? RuntimeMode.AUTHORITATIVE_PENDING : RuntimeMode.STANDALONE;
        authoritativeProbeTicksRemaining = seasonCacheBridgeAvailable ? AUTHORITATIVE_PROBE_TICKS : 0;
        lastSnapshotInProgress = null;
        lastAuthoritativeEpoch = null;
        authoritativeChunkEventsApplied = 0;
        authoritativeResetsApplied = 0;

        // Re-anchor to the player's new position on dimension change.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            anchorChunkX = (int) Math.floor(mc.player.getX()) >> 4;
            anchorChunkZ = (int) Math.floor(mc.player.getZ()) >> 4;
        }
        lastPlayerChunkX = anchorChunkX;
        lastPlayerChunkZ = anchorChunkZ;
        reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;
        texture.setAnchor(anchorChunkX, anchorChunkZ);

        texture.clear();
        markTextureDirty("dimension change clear");

        if (runtimeMode == RuntimeMode.STANDALONE) {
            restoreCachedTexture("dimension change");
            queue.enqueueAll(texture);
            startDhServicesIfNeeded();
        } else {
            cacheManager.resetForAuthoritativeSession();
            LOGGER.warn("Dimension change — awaiting authoritative Season Cache refresh");
        }
    }

    public void onClientTick(Minecraft mc) {
        if (!texture.isAllocated()) return;
        if (mc.level == null || mc.player == null) return;

        processAuthoritativeBridge(mc.level);

        // Track player chunk position for teleport detection and periodic drift check.
        int playerChunkX = (int) Math.floor(mc.player.getX()) >> 4;
        int playerChunkZ = (int) Math.floor(mc.player.getZ()) >> 4;

        if (lastPlayerChunkX != Integer.MIN_VALUE) {
            int moveDx = Math.abs(playerChunkX - lastPlayerChunkX);
            int moveDz = Math.abs(playerChunkZ - lastPlayerChunkZ);
            if (moveDx > TELEPORT_THRESHOLD || moveDz > TELEPORT_THRESHOLD) {
                // Sudden large position jump — re-anchor immediately.
                reAnchor(playerChunkX, playerChunkZ, "teleport detected");
            } else {
                // Periodic drift check: re-anchor if player has moved far from anchor.
                reAnchorTickCounter--;
                if (reAnchorTickCounter <= 0) {
                    reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;
                    int driftX = Math.abs(playerChunkX - anchorChunkX);
                    int driftZ = Math.abs(playerChunkZ - anchorChunkZ);
                    if (driftX >= RE_ANCHOR_THRESHOLD || driftZ >= RE_ANCHOR_THRESHOLD) {
                        reAnchor(playerChunkX, playerChunkZ, "periodic drift check");
                    }
                }
            }
        } else {
            // First tick — initialise tracking counters.
            reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;
        }

        lastPlayerChunkX = playerChunkX;
        lastPlayerChunkZ = playerChunkZ;

        if (runtimeMode == RuntimeMode.STANDALONE && !DhLevelResolver.isDhModPresent()) {
            int currentDist = getVanillaRenderDistance();
            if (currentDist != lastRenderDistance) {
                LOGGER.warn("Render distance changed {} -> {} — reallocating",
                        lastRenderDistance, currentDist);
                queue.clear();
                texture.allocate(computeTextureSize(currentDist));
                restoreCachedTexture("render distance reallocation");
                queue.enqueueAll(texture);
                lastRenderDistance = currentDist;
            }
        }

        if (runtimeMode == RuntimeMode.STANDALONE && processVanillaQueue(mc.level)) {
            markTextureDirty("vanilla queue");
        }

        // DH scanner only runs in STANDALONE — authoritative mode handles DH via onDhChunkModified
        if (runtimeMode == RuntimeMode.STANDALONE && DhLevelResolver.isDhModPresent() && dhScanner.isActive()) {
            if (dhScanner.tick(mc.level, texture, cacheManager, mc)) {
                markTextureDirty("dh worker results");
            }
        }

        cacheManager.tickFlush();
        sampleAndLogWeather(mc);

        if (needsUpload) {
            LOGGER.debug("Uploading snow biome textures to GPU");
            texture.upload();
            needsUpload = false;
        }
    }

    public void onChunkLoad(int chunkX, int chunkZ) {
        if (runtimeMode == RuntimeMode.STANDALONE) {
            queue.enqueue(chunkX, chunkZ);
        }
    }

    public void onDhChunkModified(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = mc.level;
        if (world == null || !texture.isAllocated()) return;
        if (!Level.OVERWORLD.equals(world.dimension())) return;

        if (runtimeMode == RuntimeMode.AUTHORITATIVE_ACTIVE) {
            // In authoritative mode, apply SC's known state for this chunk directly
            // when DH regenerates its LOD data (e.g. after SC removes snow blocks).
            // This gives instant DH LOD snow updates without waiting for the coverage
            // builder event stream. If SC hasn't sent this chunk's state yet, we skip
            // and wait for the event stream to fill it in.
            Boolean snowy = SeasonCacheBridge.getChunkSnowState(world.dimension(), chunkX, chunkZ);
            if (snowy != null) {
                int dx = chunkX - anchorChunkX;
                int dz = chunkZ - anchorChunkZ;
                if (Math.abs(dx) < ANCHOR_RADIUS && Math.abs(dz) < ANCHOR_RADIUS) {
                    if (cacheManager.writeAuthoritativeToTexture(chunkX, chunkZ, snowy, texture)) {
                        markTextureDirty("dh-listener-authoritative");
                    }
                } else {
                    cacheManager.cacheAuthoritativeOnly(chunkX, chunkZ, snowy);
                }
            }
            return;
        }

        IDhApiLevelWrapper resolvedWrapper = levelWrapper != null ? levelWrapper
                : DhLevelResolver.resolveActiveLevelWrapper(world);
        if (resolvedWrapper == null) {
            LOGGER.debug("Ignoring DH chunk ({}, {}) because no active level wrapper was available", chunkX, chunkZ);
            return;
        }
        dhScanner.enqueueDhChunk(resolvedWrapper, chunkX, chunkZ, "dh-listener");
    }

    public boolean isChunkCached(int chunkX, int chunkZ) {
        return cacheManager.hasCached(chunkX, chunkZ);
    }

    public boolean isActive() {
        return texture.isAllocated();
    }

    public float getCurrentRainGradient() {
        return currentRainGradient;
    }

    public float getCurrentThunderGradient() {
        return currentThunderGradient;
    }

    public boolean isRainActive() {
        return currentRainGradient > ACTIVE_THRESHOLD || currentIsRaining;
    }

    public boolean isStormActive() {
        return currentThunderGradient > ACTIVE_THRESHOLD || currentIsThundering;
    }

    private void processAuthoritativeBridge(ClientLevel world) {
        if (!seasonCacheBridgeAvailable) {
            return;
        }

        if (SeasonCacheBridge.isAuthoritativeSessionActive() && runtimeMode != RuntimeMode.AUTHORITATIVE_ACTIVE) {
            activateAuthoritativeMode("season cache session active");
        }

        List<SeasonCacheBridge.AuthoritativeEvent> events = SeasonCacheBridge.drainEvents();
        if (!events.isEmpty() && runtimeMode != RuntimeMode.AUTHORITATIVE_ACTIVE) {
            activateAuthoritativeMode("season cache events received");
        }

        ResourceKey<Level> currentDimension = world.dimension();
        boolean snapshotInProgress = SeasonCacheBridge.isSnapshotInProgress(currentDimension);
        Integer authoritativeEpoch = SeasonCacheBridge.currentEpoch(currentDimension);
        if (lastSnapshotInProgress == null || lastSnapshotInProgress != snapshotInProgress ||
                (authoritativeEpoch != null && !authoritativeEpoch.equals(lastAuthoritativeEpoch))) {
            LOGGER.warn("Season Cache authoritative status — dimension={}, snapshotInProgress={}, epoch={}, runtimeMode={}",
                    currentDimension.identifier(), snapshotInProgress, authoritativeEpoch, runtimeMode);
            lastSnapshotInProgress = snapshotInProgress;
            lastAuthoritativeEpoch = authoritativeEpoch;
        }

        boolean anyTextureWrites = false;
        int resetEventsAppliedThisTick = 0;
        int chunkEventsAppliedThisTick = 0;
        for (SeasonCacheBridge.AuthoritativeEvent event : events) {
            if (event.dimension() == null || !event.dimension().equals(currentDimension)) {
                continue;
            }
            switch (event.type()) {
                case RESET -> {
                    texture.clear();
                    cacheManager.resetForAuthoritativeSession();
                    anyTextureWrites = true;
                    resetEventsAppliedThisTick++;
                    authoritativeResetsApplied++;
                    LOGGER.warn("Applied authoritative reset for {} epoch {}", event.dimension().identifier(), event.epoch());
                }
                case CHUNK_STATE -> {
                    // Always cache authoritative state regardless of anchor window position.
                    // This ensures re-anchor repaints have complete data for any area the
                    // player flies to, without needing a new Season Cache snapshot.
                    int dx = event.chunkX() - anchorChunkX;
                    int dz = event.chunkZ() - anchorChunkZ;
                    boolean inWindow = Math.abs(dx) < ANCHOR_RADIUS && Math.abs(dz) < ANCHOR_RADIUS;

                    if (inWindow) {
                        // In-window: write to both cache and texture.
                        cacheManager.writeAuthoritativeToTexture(event.chunkX(), event.chunkZ(), event.snowy(), texture);
                        anyTextureWrites = true;
                        chunkEventsAppliedThisTick++;
                        authoritativeChunkEventsApplied++;
                    } else {
                        // Out-of-window: cache only — writing to the texture would alias
                        // distant chunks into texels used by nearby chunks.
                        cacheManager.cacheAuthoritativeOnly(event.chunkX(), event.chunkZ(), event.snowy());
                    }
                }
                case UNKNOWN -> LOGGER.debug("Ignoring unknown Season Cache authoritative event type");
            }
        }

        if (chunkEventsAppliedThisTick > 0 || resetEventsAppliedThisTick > 0) {
            LOGGER.warn("Season Cache authoritative apply — dimension={}, resetEvents={}, chunkEvents={}, totalResets={}, totalChunkEvents={}",
                    currentDimension.identifier(), resetEventsAppliedThisTick, chunkEventsAppliedThisTick,
                    authoritativeResetsApplied, authoritativeChunkEventsApplied);
        }

        if (anyTextureWrites) {
            markTextureDirty("authoritative season cache events");
        }

        if (runtimeMode == RuntimeMode.AUTHORITATIVE_PENDING && !SeasonCacheBridge.isAuthoritativeSessionActive() && !snapshotInProgress) {
            authoritativeProbeTicksRemaining--;
            if (authoritativeProbeTicksRemaining <= 0) {
                activateStandaloneModeFromPending(world, "season cache probe timed out");
            }
        }
    }

    /**
     * Re-centers the texture on a new player chunk position.
     *
     * Sequence:
     *   1. Update anchor fields and push new anchor to the texture (updates meta).
     *   2. Clear the texture (all previous texel positions are now stale).
     *   3. Repaint from the in-memory cache, writing only chunks within the new
     *      anchor window to avoid aliasing.
     *   4. Mark dirty so the GPU sees the repainted texture on the next tick.
     *
     * In authoritative mode the cache contains all previously-received authoritative
     * chunk states (writeAuthoritativeToTexture now persists them). The windowed
     * repaint instantly restores the correct snow state for the new view area
     * without requiring a new Season Cache snapshot.
     */
    private synchronized void reAnchor(int playerChunkX, int playerChunkZ, String reason) {
        if (!texture.isAllocated()) return;
        anchorChunkX = playerChunkX;
        anchorChunkZ = playerChunkZ;
        reAnchorTickCounter = RE_ANCHOR_INTERVAL_TICKS;

        texture.setAnchor(playerChunkX, playerChunkZ);
        texture.clear();
        restoreCachedTexture("re-anchor: " + reason);
        markTextureDirty("re-anchor: " + reason);

        LOGGER.warn("Re-anchored texture to chunk [{},{}] — {}", playerChunkX, playerChunkZ, reason);
    }

    private void activateAuthoritativeMode(String reason) {
        if (runtimeMode == RuntimeMode.AUTHORITATIVE_ACTIVE) {
            return;
        }
        stopDhServicesIfNeeded();
        queue.clear();
        cacheManager.resetForAuthoritativeSession();
        texture.clear();
        markTextureDirty("authoritative mode activation");
        runtimeMode = RuntimeMode.AUTHORITATIVE_ACTIVE;
        authoritativeProbeTicksRemaining = 0;
        LOGGER.warn("Authoritative Season Cache mode active ({})", reason);
    }

    private void activateStandaloneModeFromPending(ClientLevel world, String reason) {
        if (runtimeMode == RuntimeMode.STANDALONE) {
            return;
        }
        runtimeMode = RuntimeMode.STANDALONE;
        authoritativeProbeTicksRemaining = 0;
        LOGGER.warn("Falling back to standalone Nova mode ({})", reason);
        restoreCachedTexture("authoritative fallback");
        queue.enqueueAll(texture);
        startDhServicesIfNeeded();
    }

    private void startDhServicesIfNeeded() {
        if (!DhLevelResolver.isDhModPresent()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int vanillaRenderDist = getVanillaRenderDistance();
        int texSize = texture.isAllocated() ? texture.getSize() : SnowBiomeTexture.MAX_SIZE;
        int playerChunkX = mc.player != null ? (int) mc.player.getX() >> 4 : 0;
        int playerChunkZ = mc.player != null ? (int) mc.player.getZ() >> 4 : 0;
        boolean singleplayer = DhLevelResolver.isSingleplayerSession();

        dhScanner.start(playerChunkX, playerChunkZ, vanillaRenderDist, texSize, singleplayer);
        dhScanner.startVerificationSweep(cacheManager.getNonVanillaCachedChunks());
        DhChunkListener.register();
    }

    private void stopDhServicesIfNeeded() {
        if (!DhLevelResolver.isDhModPresent()) {
            return;
        }
        DhChunkListener.unregister();
        dhScanner.drainCompleted(cacheManager, texture);
        dhScanner.stop();
    }

    private void resetWeatherDebugState() {
        currentRainGradient = 0.0f;
        currentThunderGradient = 0.0f;
        currentIsRaining = false;
        currentIsThundering = false;

        lastRainGradient = Float.NaN;
        lastThunderGradient = Float.NaN;
        lastIsRaining = false;
        lastIsThundering = false;
        lastWeatherActive = false;

        weatherDebugBurstTicks = 0;
        weatherSampleIndex = 0;
    }

    private void sampleAndLogWeather(Minecraft mc) {
        float rainGradient = mc.level.getRainLevel(1.0f);
        float thunderGradient = mc.level.getThunderLevel(1.0f);
        boolean isRaining = mc.level.isRaining();
        boolean isThundering = mc.level.isThundering();

        currentRainGradient = rainGradient;
        currentThunderGradient = thunderGradient;
        currentIsRaining = isRaining;
        currentIsThundering = isThundering;

        boolean firstSample = Float.isNaN(lastRainGradient) || Float.isNaN(lastThunderGradient);

        float rainDelta = firstSample ? 0.0f : (rainGradient - lastRainGradient);
        float thunderDelta = firstSample ? 0.0f : (thunderGradient - lastThunderGradient);

        boolean rainGradientChanged = firstSample || Math.abs(rainDelta) > DEBUG_EPSILON;
        boolean thunderGradientChanged = firstSample || Math.abs(thunderDelta) > DEBUG_EPSILON;
        boolean rainFlagChanged = firstSample || (isRaining != lastIsRaining);
        boolean thunderFlagChanged = firstSample || (isThundering != lastIsThundering);

        boolean weatherActive =
                rainGradient > ACTIVE_THRESHOLD ||
                thunderGradient > ACTIVE_THRESHOLD ||
                isRaining ||
                isThundering;

        boolean activityStateChanged = firstSample || (weatherActive != lastWeatherActive);
        boolean anythingChanged =
                rainGradientChanged ||
                thunderGradientChanged ||
                rainFlagChanged ||
                thunderFlagChanged ||
                activityStateChanged;

        if (anythingChanged) {
            weatherDebugBurstTicks = Math.max(weatherDebugBurstTicks, 100);
        }

        if (weatherDebugBurstTicks > 0) {
            weatherDebugBurstTicks--;

            if (snowVariantConfig.isDebugLogging() && (anythingChanged || (weatherDebugBurstTicks % 5) == 0)) {
                String msg = String.format(
                        "CompSnow weather #%d | rain=%.6f (d=%+.6f) thunder=%.6f (d=%+.6f) flags: rain %s->%s thunder %s->%s active %s->%s burst=%d",
                        weatherSampleIndex,
                        rainGradient, rainDelta,
                        thunderGradient, thunderDelta,
                        lastIsRaining, isRaining,
                        lastIsThundering, isThundering,
                        lastWeatherActive, weatherActive,
                        weatherDebugBurstTicks
                );

                WEATHER_LOGGER.info(msg);

                if (activityStateChanged || rainFlagChanged || thunderFlagChanged) {
                    mc.player.sendSystemMessage(Component.literal(msg));
                }
            }
        }

        lastRainGradient = rainGradient;
        lastThunderGradient = thunderGradient;
        lastIsRaining = isRaining;
        lastIsThundering = isThundering;
        lastWeatherActive = weatherActive;
        weatherSampleIndex++;
    }

    private boolean processVanillaQueue(ClientLevel world) {
        if (!texture.isAllocated() || world == null) return false;

        boolean anyWritten = false;
        int processed = 0;

        while (!queue.isEmpty() && processed < 64) {
            long[] entry = queue.poll();
            if (entry == null) break;

            int chunkX = (int) entry[0];
            int chunkZ = (int) entry[1];
            if (applyChunkUpdateFromWorld(world, chunkX, chunkZ, "vanilla-chunk-load")) {
                anyWritten = true;
            }
            processed++;
        }
        return anyWritten;
    }

    private boolean applyChunkUpdateFromWorld(ClientLevel world, int chunkX, int chunkZ, String source) {
        Float previous = cacheManager.getCachedSnowState(chunkX, chunkZ);
        float intensity = cacheManager.sampleAndCache(world, chunkX, chunkZ, texture, source + "-refine");
        dhScanner.markVanillaConfirmed(chunkX, chunkZ);
        if (previous == null) {
            LOGGER.debug("{} populated previously-uncached chunk ({}, {}) -> intensity={}", source, chunkX, chunkZ, intensity);
        } else if (Math.abs(previous - intensity) >= 0.004f) {
            LOGGER.debug("{} refined chunk ({}, {}) from intensity={} -> {}", source, chunkX, chunkZ, previous, intensity);
        } else {
            LOGGER.debug("{} confirmed cached chunk ({}, {}) intensity={}", source, chunkX, chunkZ, intensity);
        }
        return true;
    }

    private void restoreCachedTexture(String source) {
        int restored = cacheManager.writeWindowedCacheToTexture(
                texture, anchorChunkX, anchorChunkZ, ANCHOR_RADIUS);
        if (restored > 0) {
            LOGGER.warn("Cache restore for {} wrote {} chunk texels and queued an upload", source, restored);
            markTextureDirty("cache restore: " + source);
        }
    }

    private void markTextureDirty(String reason) {
        if (!needsUpload) {
            LOGGER.debug("Queued texture upload ({})", reason);
        }
        needsUpload = true;
    }

    private int getVanillaRenderDistance() {
        return Minecraft.getInstance().options.renderDistance().get();
    }

    public static int computeTextureSize(int renderDistanceChunks) {
        int diameter = renderDistanceChunks * 2;
        int size = 256;
        while (size < diameter) size <<= 1;
        return Math.min(size, SnowBiomeTexture.MAX_SIZE);
    }
}
