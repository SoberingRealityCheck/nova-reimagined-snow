package com.itsthatnova.compsnow.events;

import com.itsthatnova.compsnow.biome.BiomeSampler;
import com.itsthatnova.compsnow.events.CacheManager.DiagnosticsSnapshot;
import com.itsthatnova.compsnow.texture.SnowBiomeTexture;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simpler public-API DH worker service.
 *
 * Search order:
 *  1) DH listener chunks
 *  2) orthogonal neighbors of chunks that classified snowy=true
 *  3) outward ring scan starting at vanilla+1 and resuming where it left off
 *  4) retries for chunks that had no usable DH data yet
 */
public class DhChunkScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.dhscanner");

    private static final int THREAD_COUNT = 4;
    // Throughput is bottlenecked by the DH terrain repo, not by our submit rate
    // or thread count. Running at full rate all the time costs nothing extra on
    // the client side and ensures the fastest possible fill.
    private static final int SUBMIT_PER_TICK = 64;
    private static final int MAX_IN_FLIGHT = 160;
    private static final int MAX_LISTENER_PENDING = 256;
    private static final int MAX_POSITIVE_PENDING = 512;
    private static final int MAX_RETRY_PENDING = 256;
    private static final int MAX_VERIFY_PENDING = 4096;
    // Minimum ring submissions per tick guaranteed even when the positive queue
    // has work. Prevents the ring from being permanently starved in worlds where
    // everything is snowy, ensuring systematic outward coverage continues.
    private static final int RING_RESERVED_PER_TICK = 8;
    private static final int RECENTRE_THRESHOLD = 12;
    private static final int WARMUP_TICKS = 20;
    private static final int MAX_RETRIES = 3;
    private static final int DIAGNOSTIC_LOG_INTERVAL_TICKS = 100;

    private enum ChunkState {
        RESOLVED_DH,
        CONFIRMED_VANILLA
    }

    private enum Lane {
        LISTENER,
        POSITIVE,
        RING,
        RETRY,
        VERIFY   // re-checks previously classified DH chunks for seasonal/biome changes
    }

    private static final class DhRequest {
        final IDhApiLevelWrapper preferredWrapper;
        final int chunkX;
        final int chunkZ;
        final String source;
        final int attempt;
        final Lane lane;

        DhRequest(IDhApiLevelWrapper preferredWrapper, int chunkX, int chunkZ, String source, int attempt, Lane lane) {
            this.preferredWrapper = preferredWrapper;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.source = source;
            this.attempt = attempt;
            this.lane = lane;
        }
    }

    private static final class ScanResult {
        final int chunkX;
        final int chunkZ;
        final Float snowy;  // null=no data, 0.0-1.0=intensity
        final String source;
        final int attempt;
        final Lane lane;
        final IDhApiLevelWrapper levelWrapper;

        ScanResult(int chunkX, int chunkZ, Float snowy, String source, int attempt, Lane lane, IDhApiLevelWrapper levelWrapper) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.snowy = snowy;
            this.source = source;
            this.attempt = attempt;
            this.lane = lane;
            this.levelWrapper = levelWrapper;
        }
    }

    private int scanCentreX;
    private int scanCentreZ;
    private int minRadius;
    private int maxRadius;
    private int scanRadius;
    private int scanAngle;
    private int warmupTicksRemaining;
    private int wrapperMissTicks;

    private boolean serviceActive;
    private boolean scanEnabled;
    private boolean singleplayerSession;

    private ExecutorService threadPool;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Long> listenerQueuedKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> positiveQueuedKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> retryQueuedKeys = ConcurrentHashMap.newKeySet();
    private final Set<Long> verifyQueuedKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<DhRequest> listenerQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DhRequest> positiveQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DhRequest> retryQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DhRequest> verifyQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ScanResult> completedResults = new ConcurrentLinkedQueue<>();
    private final Map<Long, ChunkState> chunkStates = new ConcurrentHashMap<>();

    private CacheManager cacheDiagnosticsProvider;

    private int totalRingGenerated;
    private int totalListenerQueued;
    private int totalPositiveQueued;
    private int totalRetryQueued;
    private int totalVerifyQueued;
    private int totalDropped;
    private int ticksSinceDiagnosticLog;
    private final AtomicInteger totalSampled = new AtomicInteger(0);
    private final AtomicInteger totalDhMisses = new AtomicInteger(0);
    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final AtomicInteger totalSubmitted = new AtomicInteger(0);
    private final AtomicInteger totalCompleted = new AtomicInteger(0);
    private final AtomicInteger totalApplied = new AtomicInteger(0);
    private final AtomicInteger resolvedDhCount = new AtomicInteger(0);
    private final AtomicInteger confirmedVanillaCount = new AtomicInteger(0);
    private final AtomicInteger totalVerifyCorrections = new AtomicInteger(0);

    public void start(int playerChunkX, int playerChunkZ, int vanillaRadius, int textureSize, boolean singleplayerSession) {
        stopPool();

        int textureRadius = Math.max(1, textureSize / 2);
        int configuredDhRadius = DhLevelResolver.resolveConfiguredDhChunkRenderDistance(textureRadius);
        int effectiveDhRadius = Math.max(vanillaRadius + 1, Math.min(configuredDhRadius, textureRadius));

        serviceActive = true;
        scanEnabled = true;
        this.singleplayerSession = singleplayerSession;
        warmupTicksRemaining = WARMUP_TICKS;
        wrapperMissTicks = 0;
        totalRingGenerated = 0;
        totalListenerQueued = 0;
        totalPositiveQueued = 0;
        totalRetryQueued = 0;
        totalVerifyQueued = 0;
        totalDropped = 0;
        ticksSinceDiagnosticLog = 0;
        totalSampled.set(0);
        totalDhMisses.set(0);
        totalRetries.set(0);
        totalSubmitted.set(0);
        totalCompleted.set(0);
        totalApplied.set(0);
        resolvedDhCount.set(0);
        confirmedVanillaCount.set(0);

        inFlight.clear();
        listenerQueuedKeys.clear();
        positiveQueuedKeys.clear();
        retryQueuedKeys.clear();
        listenerQueue.clear();
        positiveQueue.clear();
        retryQueue.clear();
        completedResults.clear();
        chunkStates.clear();

        final AtomicInteger idx = new AtomicInteger(0);
        threadPool = Executors.newFixedThreadPool(THREAD_COUNT, r -> {
            Thread t = new Thread(r, "compsnow-dh-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        recenterInternal(playerChunkX, playerChunkZ, vanillaRadius + 1, effectiveDhRadius);

        LOGGER.warn("DH worker service started: centre ({}, {}), mode={}, radius {} to {} (dhConfigured={}, textureCap={}), {} workers, warmup {} ticks",
                playerChunkX, playerChunkZ, singleplayerSession ? "singleplayer" : "multiplayer",
                minRadius, maxRadius, configuredDhRadius, textureRadius, THREAD_COUNT, WARMUP_TICKS);
    }

    public void enqueueDhChunk(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ, String source) {
        if (!serviceActive || levelWrapper == null) {
            return;
        }
        long key = packKey(chunkX, chunkZ);
        if (inFlight.contains(key) || listenerQueuedKeys.contains(key)) {
            return;
        }
        if (listenerQueuedKeys.size() >= MAX_LISTENER_PENDING) {
            totalDropped++;
            if ((totalDropped % 25) == 1) {
                LOGGER.warn("Dropping listener DH request for chunk ({}, {}) from {} because listener queue is full ({}/{})",
                        chunkX, chunkZ, source, listenerQueuedKeys.size(), MAX_LISTENER_PENDING);
            }
            return;
        }
        if (listenerQueuedKeys.add(key)) {
            listenerQueue.offer(new DhRequest(levelWrapper, chunkX, chunkZ, source, 0, Lane.LISTENER));
            totalListenerQueued++;
        }
    }

    /**
     * Enqueues all provided chunks for background verification against the DH
     * terrain repo. Called on world join and dimension re-entry to catch state
     * changes (e.g. seasonal transitions) that occurred since the cache was last
     * written. Verify runs at lowest priority — after listener, positive, ring,
     * and retry — so it never interferes with fresh chunk discovery.
     *
     * Chunks that vanilla has already confirmed are skipped: they self-correct
     * whenever they load inside render distance.
     */
    public void startVerificationSweep(java.util.List<long[]> chunks) {
        if (!serviceActive || chunks.isEmpty()) return;
        int queued = 0;
        for (long[] pos : chunks) {
            int cx = (int) pos[0];
            int cz = (int) pos[1];
            long key = packKey(cx, cz);
            ChunkState state = chunkStates.get(key);
            if (state == ChunkState.CONFIRMED_VANILLA) continue;
            if (inFlight.contains(key) || verifyQueuedKeys.contains(key)) continue;
            if (verifyQueuedKeys.size() >= MAX_VERIFY_PENDING) break;
            if (verifyQueuedKeys.add(key)) {
                // preferredWrapper=null — active wrapper is resolved at submit time
                verifyQueue.offer(new DhRequest(null, cx, cz, "dh-verify", 0, Lane.VERIFY));
                queued++;
            }
        }
        totalVerifyQueued += queued;
        if (queued > 0) {
            LOGGER.warn("Verification sweep queued {} cached DH chunks for re-classification", queued);
        }
    }

    public void markVanillaConfirmed(int chunkX, int chunkZ) {
        ChunkState prev = chunkStates.put(packKey(chunkX, chunkZ), ChunkState.CONFIRMED_VANILLA);
        if (prev == ChunkState.RESOLVED_DH) {
            resolvedDhCount.decrementAndGet();
            confirmedVanillaCount.incrementAndGet();
        } else if (prev != ChunkState.CONFIRMED_VANILLA) {
            confirmedVanillaCount.incrementAndGet();
        }
    }

    public void drainCompleted(CacheManager cache, SnowBiomeTexture tex) {
        cacheDiagnosticsProvider = cache;
        if (!tex.isAllocated()) return;
        int drained = 0;
        ScanResult result;
        while ((result = completedResults.poll()) != null) {
            inFlight.remove(packKey(result.chunkX, result.chunkZ));
            if (result.snowy != null) {
                cache.writeSampledToTexture(result.chunkX, result.chunkZ, result.snowy, tex, result.source + "-drain");
                drained++;
            }
        }
        if (drained > 0) {
            LOGGER.warn("Drained {} pending DH results before shutdown", drained);
        }
    }

    public void stop() {
        maybeLogDiagnostics(true);
        serviceActive = false;
        scanEnabled = false;
        stopPool();
        LOGGER.warn("DH worker service stopped");
    }

    private void stopPool() {
        if (threadPool != null && !threadPool.isShutdown()) {
            threadPool.shutdownNow();
            threadPool = null;
        }
        inFlight.clear();
        listenerQueuedKeys.clear();
        positiveQueuedKeys.clear();
        retryQueuedKeys.clear();
        verifyQueuedKeys.clear();
        listenerQueue.clear();
        positiveQueue.clear();
        retryQueue.clear();
        verifyQueue.clear();
        completedResults.clear();
        chunkStates.clear();
        resolvedDhCount.set(0);
        confirmedVanillaCount.set(0);
        totalVerifyCorrections.set(0);
    }

    public boolean isActive() {
        return serviceActive;
    }

    public boolean isScanEnabled() {
        return serviceActive && scanEnabled;
    }

    public String describeMode() {
        return singleplayerSession ? "singleplayer" : "multiplayer";
    }

    public boolean tick(ClientLevel world, SnowBiomeTexture tex, CacheManager cache, Minecraft mc) {
        cacheDiagnosticsProvider = cache;
        if (!serviceActive || world == null || !tex.isAllocated()) return false;

        ticksSinceDiagnosticLog++;
        boolean anyWritten = drainCompletedResults(cache, tex);

        int playerChunkX = (int) mc.player.getX() >> 4;
        int playerChunkZ = (int) mc.player.getZ() >> 4;
        if (Math.abs(playerChunkX - scanCentreX) >= RECENTRE_THRESHOLD ||
                Math.abs(playerChunkZ - scanCentreZ) >= RECENTRE_THRESHOLD) {
            int textureRadius = Math.max(1, tex.getSize() / 2);
            int configuredDhRadius = DhLevelResolver.resolveConfiguredDhChunkRenderDistance(textureRadius);
            int nextMinRadius = mc.options.renderDistance().get() + 1;
            int nextMaxRadius = Math.max(nextMinRadius, Math.min(configuredDhRadius, textureRadius));
            LOGGER.warn("Player moved — recentring DH scanner window (radius {} to {}, dhConfigured={}, textureCap={})",
                    nextMinRadius, nextMaxRadius, configuredDhRadius, textureRadius);
            recenterInternal(playerChunkX, playerChunkZ, nextMinRadius, nextMaxRadius);
            warmupTicksRemaining = WARMUP_TICKS;
        }

        IDhApiLevelWrapper activeWrapper = DhLevelResolver.resolveActiveLevelWrapper(world);
        if (activeWrapper == null) {
            wrapperMissTicks++;
            if (wrapperMissTicks == 1 || wrapperMissTicks % 100 == 0) {
                LOGGER.warn("DH scanner tick skipped — no active DH level wrapper available yet (mode={}, missTicks={})",
                        describeMode(), wrapperMissTicks);
            }
            maybeLogDiagnostics(false);
            return anyWritten;
        }
        wrapperMissTicks = 0;

        if (warmupTicksRemaining > 0) {
            warmupTicksRemaining--;
            maybeLogDiagnostics(false);
            return anyWritten;
        }

        submitWork(activeWrapper, SUBMIT_PER_TICK, MAX_IN_FLIGHT);
        maybeLogDiagnostics(false);
        return anyWritten;
    }

    private void submitWork(IDhApiLevelWrapper activeWrapper, int submitBudget, int inFlightBudget) {
        if (threadPool == null) {
            return;
        }

        // Reserve a slice of the budget for the ring scan so it cannot be
        // completely starved by the positive queue (e.g. in a fully-snowy world
        // where every classified chunk generates four new neighbors).
        int ringReserved = scanEnabled ? Math.min(RING_RESERVED_PER_TICK, submitBudget / 4) : 0;
        int normalBudget = submitBudget - ringReserved;

        int submitted = 0;

        // Priority pass: listener > positive > ring, up to the non-reserved budget.
        while (submitted < normalBudget && inFlight.size() < inFlightBudget && serviceActive) {
            DhRequest request = pollListenerRequest();
            if (request == null) {
                request = pollPositiveRequest();
            }
            if (request == null && scanEnabled) {
                request = nextRingRequest(activeWrapper);
            }
            if (request == null) {
                request = pollRetryRequest();
            }
            if (request == null) {
                request = pollVerifyRequest();
            }
            if (request == null) {
                break;
            }
            submitRequest(request, activeWrapper);
            submitted++;
        }

        // Reserved ring pass: spend the reserved budget on ring-only work so the
        // ring always makes forward progress regardless of positive queue depth.
        if (scanEnabled && ringReserved > 0) {
            int ringSubmitted = 0;
            while (ringSubmitted < ringReserved && inFlight.size() < inFlightBudget && serviceActive) {
                DhRequest request = nextRingRequest(activeWrapper);
                if (request == null) {
                    break;
                }
                submitRequest(request, activeWrapper);
                ringSubmitted++;
            }
        }
    }

    private DhRequest pollListenerRequest() {
        while (true) {
            DhRequest request = listenerQueue.poll();
            if (request == null) {
                return null;
            }
            long key = packKey(request.chunkX, request.chunkZ);
            listenerQueuedKeys.remove(key);
            if (inFlight.contains(key)) {
                continue;
            }
            return request;
        }
    }

    private DhRequest pollPositiveRequest() {
        while (true) {
            DhRequest request = positiveQueue.poll();
            if (request == null) {
                return null;
            }
            long key = packKey(request.chunkX, request.chunkZ);
            positiveQueuedKeys.remove(key);
            if (inFlight.contains(key)) {
                continue;
            }
            ChunkState state = chunkStates.get(key);
            if (state == ChunkState.RESOLVED_DH || state == ChunkState.CONFIRMED_VANILLA) {
                continue;
            }
            return request;
        }
    }

    private DhRequest pollRetryRequest() {
        while (true) {
            DhRequest request = retryQueue.poll();
            if (request == null) {
                return null;
            }
            long key = packKey(request.chunkX, request.chunkZ);
            retryQueuedKeys.remove(key);
            if (inFlight.contains(key)) {
                continue;
            }
            ChunkState state = chunkStates.get(key);
            if (state == ChunkState.RESOLVED_DH || state == ChunkState.CONFIRMED_VANILLA) {
                continue;
            }
            return request;
        }
    }

    private DhRequest pollVerifyRequest() {
        while (true) {
            DhRequest request = verifyQueue.poll();
            if (request == null) return null;
            long key = packKey(request.chunkX, request.chunkZ);
            verifyQueuedKeys.remove(key);
            if (inFlight.contains(key)) continue;
            // Skip if vanilla has since confirmed this chunk — no re-query needed
            if (chunkStates.get(key) == ChunkState.CONFIRMED_VANILLA) continue;
            return request;
        }
    }

    private DhRequest nextRingRequest(IDhApiLevelWrapper activeWrapper) {
        while (scanRadius <= maxRadius) {
            int[] pos = getNextRingPosition();
            int cx = scanCentreX + pos[0];
            int cz = scanCentreZ + pos[1];
            totalRingGenerated++;

            long key = packKey(cx, cz);
            if (listenerQueuedKeys.contains(key) || positiveQueuedKeys.contains(key) || retryQueuedKeys.contains(key) || inFlight.contains(key)) {
                continue;
            }
            ChunkState state = chunkStates.get(key);
            if (state == ChunkState.RESOLVED_DH || state == ChunkState.CONFIRMED_VANILLA) {
                continue;
            }
            return new DhRequest(activeWrapper, cx, cz, "dh-scanner", 0, Lane.RING);
        }

        scanEnabled = false;
        return null;
    }

    private void submitRequest(DhRequest request, IDhApiLevelWrapper activeWrapper) {
        IDhApiLevelWrapper wrapper = request.preferredWrapper != null ? request.preferredWrapper : activeWrapper;
        if (wrapper == null || threadPool == null) {
            return;
        }

        long key = packKey(request.chunkX, request.chunkZ);
        if (!inFlight.add(key)) {
            return;
        }

        totalSubmitted.incrementAndGet();
        threadPool.submit(() -> {
            try {
                if (!serviceActive) {
                    return;
                }
                Float snowy = BiomeSampler.isSnowEligibleDhChunk(wrapper, request.chunkX, request.chunkZ);
                totalSampled.incrementAndGet();
                totalCompleted.incrementAndGet();
                completedResults.offer(new ScanResult(request.chunkX, request.chunkZ, snowy, request.source, request.attempt, request.lane, wrapper));
            } catch (Exception e) {
                LOGGER.debug("DH worker failed for {} chunk ({}, {}) attempt {}: {}",
                        request.source, request.chunkX, request.chunkZ, request.attempt + 1, e.getMessage());
                totalCompleted.incrementAndGet();
                completedResults.offer(new ScanResult(request.chunkX, request.chunkZ, null, request.source, request.attempt, request.lane, wrapper));
            }
        });
    }

    private boolean drainCompletedResults(CacheManager cache, SnowBiomeTexture tex) {
        boolean anyWritten = false;
        ScanResult result;
        while ((result = completedResults.poll()) != null) {
            long key = packKey(result.chunkX, result.chunkZ);
            inFlight.remove(key);
            if (result.snowy != null) {
                if (result.lane == Lane.VERIFY) {
                    // Verification path: only write if the state changed and vanilla
                    // hasn't since confirmed the chunk. Uses the dedicated verify
                    // write path that allows DH false to overwrite previous DH true.
                    if (chunkStates.get(key) != ChunkState.CONFIRMED_VANILLA) {
                        boolean corrected = cache.writeVerifyResultToTexture(
                                result.chunkX, result.chunkZ, result.snowy, tex);
                        if (corrected) {
                            totalVerifyCorrections.incrementAndGet();
                            anyWritten = true;
                        }
                    }
                } else {
                    // Normal discovery path
                    cache.writeSampledToTexture(result.chunkX, result.chunkZ, result.snowy, tex, result.source + "-result");
                    totalApplied.incrementAndGet();
                    if (chunkStates.get(key) != ChunkState.CONFIRMED_VANILLA) {
                        ChunkState prev = chunkStates.put(key, ChunkState.RESOLVED_DH);
                        if (prev != ChunkState.RESOLVED_DH && prev != ChunkState.CONFIRMED_VANILLA) {
                            resolvedDhCount.incrementAndGet();
                        }
                    }
                    if (result.snowy > 0.0f) {
                        enqueuePositiveNeighbors(result.levelWrapper, result.chunkX, result.chunkZ, result.source);
                    }
                    anyWritten = true;
                }
            } else {
                totalDhMisses.incrementAndGet();
                if (result.attempt + 1 < MAX_RETRIES && serviceActive) {
                    totalRetries.incrementAndGet();
                    enqueueRetry(result.levelWrapper, result.chunkX, result.chunkZ, result.source, result.attempt + 1);
                    LOGGER.debug("{} had no terrain-repo data yet for chunk ({}, {}) — retrying ({}/{})",
                            result.source, result.chunkX, result.chunkZ, result.attempt + 2, MAX_RETRIES);
                } else {
                    LOGGER.debug("{} had no terrain-repo data yet for chunk ({}, {}) after {} attempts",
                            result.source, result.chunkX, result.chunkZ, result.attempt + 1);
                }
            }
        }
        return anyWritten;
    }

    private void enqueuePositiveNeighbors(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ, String source) {
        enqueuePositive(levelWrapper, chunkX + 1, chunkZ, source);
        enqueuePositive(levelWrapper, chunkX - 1, chunkZ, source);
        enqueuePositive(levelWrapper, chunkX, chunkZ + 1, source);
        enqueuePositive(levelWrapper, chunkX, chunkZ - 1, source);
    }

    private void enqueuePositive(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ, String source) {
        if (!serviceActive || levelWrapper == null || !isInsideActiveRadius(chunkX, chunkZ)) {
            return;
        }
        long key = packKey(chunkX, chunkZ);
        if (listenerQueuedKeys.contains(key) || positiveQueuedKeys.contains(key) || retryQueuedKeys.contains(key) || inFlight.contains(key)) {
            return;
        }
        ChunkState state = chunkStates.get(key);
        if (state == ChunkState.RESOLVED_DH || state == ChunkState.CONFIRMED_VANILLA) {
            return;
        }
        if (positiveQueuedKeys.size() >= MAX_POSITIVE_PENDING) {
            totalDropped++;
            return;
        }
        if (positiveQueuedKeys.add(key)) {
            positiveQueue.offer(new DhRequest(levelWrapper, chunkX, chunkZ, source + "-neighbor", 0, Lane.POSITIVE));
            totalPositiveQueued++;
        }
    }

    private void enqueueRetry(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ, String source, int attempt) {
        if (!serviceActive || levelWrapper == null) {
            return;
        }
        long key = packKey(chunkX, chunkZ);
        if (listenerQueuedKeys.contains(key) || positiveQueuedKeys.contains(key) || retryQueuedKeys.contains(key) || inFlight.contains(key)) {
            return;
        }
        ChunkState state = chunkStates.get(key);
        if (state == ChunkState.RESOLVED_DH || state == ChunkState.CONFIRMED_VANILLA) {
            return;
        }
        if (retryQueuedKeys.size() >= MAX_RETRY_PENDING) {
            totalDropped++;
            return;
        }
        if (retryQueuedKeys.add(key)) {
            retryQueue.offer(new DhRequest(levelWrapper, chunkX, chunkZ, source, attempt, Lane.RETRY));
            totalRetryQueued++;
        }
    }

    private void maybeLogDiagnostics(boolean force) {
        if (!force && ticksSinceDiagnosticLog < DIAGNOSTIC_LOG_INTERVAL_TICKS) {
            return;
        }
        ticksSinceDiagnosticLog = 0;

        BiomeSampler.DiagnosticsSnapshot snap = BiomeSampler.snapshotAndResetDhDiagnostics();
        DiagnosticsSnapshot cacheSnap = cacheDiagnosticsProvider != null
                ? cacheDiagnosticsProvider.snapshotAndResetDiagnostics()
                : new DiagnosticsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        long resolvedDh = resolvedDhCount.get();
        long confirmedVanilla = confirmedVanillaCount.get();

        LOGGER.warn(
                "DH diagnostic summary{}: mode={}, active={}, scanEnabled={}, listenerQueued={}, positiveQueued={}, retryQueued={}, verifyQueued={}, verifyCorrections={}, submitted={}, completed={}, applied={}, ringGenerated={}, repoMisses={}, retries={}, dropped={}, inflight={}, pendingTotal={}, resolvedDh={}, confirmedVanilla={}, repoCalls={}, repoNull={}, repoUnusable={}, repoEmpty={}, noUsableBiome={}, columnChecks={}, columnUsable={}, byRegistryEntry={}, byBiomeObj={}, byTier1KeyTrue={}, byTier1KeyFalse={}, tier1KeyWouldBeTrue={}, tier1KeyWouldBeFalse={}, tier1KeyWouldBeUnknown={}, byUnknown={}, snowyTrue={}, snowyFalse={}, texChanged={}, texSame={}, texSnowyTrue={}, texSnowyFalse={}, dhFalseBlocked={}, dhFalseAllowed={}, uniqueDhChunks={}, uniqueDhSnowyChunks={}, uniqueDhTexels={}, dhTexelCollisions={}, dhDuplicateChunkApplies={}",
                force ? " [final]" : "",
                describeMode(), serviceActive, scanEnabled,
                totalListenerQueued, totalPositiveQueued, totalRetryQueued,
                totalVerifyQueued, totalVerifyCorrections.get(),
                totalSubmitted.get(), totalCompleted.get(), totalApplied.get(), totalRingGenerated,
                totalDhMisses.get(), totalRetries.get(), totalDropped,
                inFlight.size(), (listenerQueue.size() + positiveQueue.size() + retryQueue.size() + verifyQueue.size()), resolvedDh, confirmedVanilla,
                snap.repoCalls, snap.repoNullResult, snap.repoUnusableResult, snap.repoEmptyChunk,
                snap.repoNoUsableBiome, snap.columnChecks, snap.columnUsable,
                snap.classifiedRegistryEntry, snap.classifiedBiomeObject, snap.classifiedNameTrue,
                snap.classifiedNameFalse, snap.nameOnlyWouldBeTrue, snap.nameOnlyWouldBeFalse,
                snap.nameOnlyWouldBeUnknown, snap.classifiedUnknown, snap.chunkSnowyTrue, snap.chunkSnowyFalse,
                cacheSnap.textureChanged, cacheSnap.textureSame, cacheSnap.textureSnowyTrue, cacheSnap.textureSnowyFalse,
                cacheSnap.dhFalseBlocked, cacheSnap.dhFalseAllowed, cacheSnap.uniqueDhChunksApplied,
                cacheSnap.uniqueDhSnowyChunks, cacheSnap.uniqueDhTexelsTouched, cacheSnap.dhTexelCollisions,
                cacheSnap.dhDuplicateChunkApplies
        );
    }

    private void recenterInternal(int playerChunkX, int playerChunkZ, int nextMinRadius, int nextMaxRadius) {
        scanCentreX = playerChunkX;
        scanCentreZ = playerChunkZ;
        minRadius = nextMinRadius;
        maxRadius = nextMaxRadius;
        scanRadius = nextMinRadius;
        scanAngle = 0;
        scanEnabled = true;
    }

    private boolean isInsideActiveRadius(int chunkX, int chunkZ) {
        int radius = Math.max(Math.abs(chunkX - scanCentreX), Math.abs(chunkZ - scanCentreZ));
        return radius >= minRadius && radius <= maxRadius;
    }

    private int[] getNextRingPosition() {
        while (scanRadius <= maxRadius) {
            int r = scanRadius;
            int perimeter = r == 0 ? 1 : 8 * r;

            if (r == 0) {
                advanceRing(perimeter);
                return new int[]{0, 0};
            }

            int a = scanAngle % perimeter;
            int sideLen = 2 * r;

            int[] result;
            if (a < sideLen) {
                result = new int[]{-r + a, -r};
            } else if (a < sideLen * 2) {
                int t = a - sideLen;
                result = new int[]{r, -r + t};
            } else if (a < sideLen * 3) {
                int t = a - sideLen * 2;
                result = new int[]{r - t, r};
            } else {
                int t = a - sideLen * 3;
                result = new int[]{-r, r - t};
            }

            advanceRing(perimeter);
            return result;
        }
        return new int[]{0, 0};
    }

    private void advanceRing(int perimeter) {
        scanAngle++;
        if (scanAngle >= perimeter) {
            scanAngle = 0;
            scanRadius++;
        }
    }

    private static long packKey(int x, int z) {
        return ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
    }
}
