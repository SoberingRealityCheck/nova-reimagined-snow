package com.itsthatnova.compsnow.events;

import com.itsthatnova.compsnow.biome.BiomeSampler;
import com.itsthatnova.compsnow.texture.SnowBiomeTexture;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the on-disk biome snow cache for Nova Reimagined Snow.
 *
 * Cache format v2 (binary) — chunk granularity:
 *   Header: 4 bytes magic (0x43534E57 = "CSNW"), 4 bytes version (2),
 *           8 bytes reserved
 *   Entries: repeated until EOF:
 *     4 bytes chunkX, 4 bytes chunkZ, 1 byte snowy (1=snowy, 0=not)
 */
public class CacheManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.cache");

    private static final int MAGIC = 0x43534E57;
    // Version 3: intensity stored as byte (0-255) instead of boolean (0/1).
    private static final int VERSION = 3;
    private static final long FLUSH_INTERVAL_MS = 60_000;

    private final Map<Long, Float> cache = new HashMap<>();
    private final Map<Long, Source> sourceMap = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    private Path cacheFile;
    private long lastFlushTime = 0;
    private boolean loaded = false;

    private final AtomicLong diagTextureChanged = new AtomicLong();
    private final AtomicLong diagTextureSame = new AtomicLong();
    private final AtomicLong diagTextureSnowyTrue = new AtomicLong();
    private final AtomicLong diagTextureSnowyFalse = new AtomicLong();
    private final AtomicLong diagDhFalseBlocked = new AtomicLong();
    private final AtomicLong diagDhFalseAllowed = new AtomicLong();
    private final AtomicLong diagDhTexelCollisions = new AtomicLong();
    private final AtomicLong diagDhDuplicateChunkApplies = new AtomicLong();

    private final Set<Long> diagDhUniqueAppliedChunkKeys = new HashSet<>();
    private final Set<Long> diagDhUniqueSnowyChunkKeys = new HashSet<>();
    private final Set<Long> diagDhUniqueTexelsTouched = new HashSet<>();
    private final Map<Long, Long> diagDhTexelOwner = new HashMap<>();
    private int diagDetailedLogsRemaining = 50;

    private enum Source {
        UNKNOWN,
        DH,
        VANILLA,
        AUTHORITATIVE
    }

    public static final class DiagnosticsSnapshot {
        public final long textureChanged;
        public final long textureSame;
        public final long textureSnowyTrue;
        public final long textureSnowyFalse;
        public final long dhFalseBlocked;
        public final long dhFalseAllowed;
        public final long uniqueDhChunksApplied;
        public final long uniqueDhSnowyChunks;
        public final long uniqueDhTexelsTouched;
        public final long dhTexelCollisions;
        public final long dhDuplicateChunkApplies;

        public DiagnosticsSnapshot(long textureChanged, long textureSame, long textureSnowyTrue, long textureSnowyFalse,
                                   long dhFalseBlocked, long dhFalseAllowed,
                                   long uniqueDhChunksApplied, long uniqueDhSnowyChunks, long uniqueDhTexelsTouched,
                                   long dhTexelCollisions, long dhDuplicateChunkApplies) {
            this.textureChanged = textureChanged;
            this.textureSame = textureSame;
            this.textureSnowyTrue = textureSnowyTrue;
            this.textureSnowyFalse = textureSnowyFalse;
            this.dhFalseBlocked = dhFalseBlocked;
            this.dhFalseAllowed = dhFalseAllowed;
            this.uniqueDhChunksApplied = uniqueDhChunksApplied;
            this.uniqueDhSnowyChunks = uniqueDhSnowyChunks;
            this.uniqueDhTexelsTouched = uniqueDhTexelsTouched;
            this.dhTexelCollisions = dhTexelCollisions;
            this.dhDuplicateChunkApplies = dhDuplicateChunkApplies;
        }
    }

    public DiagnosticsSnapshot snapshotAndResetDiagnostics() {
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(
                diagTextureChanged.getAndSet(0),
                diagTextureSame.getAndSet(0),
                diagTextureSnowyTrue.getAndSet(0),
                diagTextureSnowyFalse.getAndSet(0),
                diagDhFalseBlocked.getAndSet(0),
                diagDhFalseAllowed.getAndSet(0),
                diagDhUniqueAppliedChunkKeys.size(),
                diagDhUniqueSnowyChunkKeys.size(),
                diagDhUniqueTexelsTouched.size(),
                diagDhTexelCollisions.getAndSet(0),
                diagDhDuplicateChunkApplies.getAndSet(0)
        );
        diagDhUniqueAppliedChunkKeys.clear();
        diagDhUniqueSnowyChunkKeys.clear();
        diagDhUniqueTexelsTouched.clear();
        diagDhTexelOwner.clear();
        return snapshot;
    }

    public void onWorldJoin(String worldKey) {
        cache.clear();
        sourceMap.clear();
        dirty.clear();
        loaded = false;
        diagDetailedLogsRemaining = 50;
        snapshotAndResetDiagnostics();

        try {
            Path cacheDir = getCacheRoot().resolve(sanitizeKey(worldKey));
            Files.createDirectories(cacheDir);
            cacheFile = cacheDir.resolve("biomes.bin");
            loadFromDisk();
        } catch (Exception e) {
            LOGGER.error("Failed to initialise cache for world '{}': {}", worldKey, e.getMessage());
            cacheFile = null;
        }
    }

    public void onWorldLeave() {
        flushToDisk();
        cache.clear();
        sourceMap.clear();
        dirty.clear();
        loaded = false;
        cacheFile = null;
        diagDetailedLogsRemaining = 50;
        snapshotAndResetDiagnostics();
    }

    public boolean hasCached(int chunkX, int chunkZ) {
        return cache.containsKey(packKey(chunkX, chunkZ));
    }

    public Float getCachedSnowState(int chunkX, int chunkZ) {
        return cache.get(packKey(chunkX, chunkZ));
    }


    public void resetForAuthoritativeSession() {
        cache.clear();
        sourceMap.clear();
        dirty.clear();
        diagDetailedLogsRemaining = 50;
        snapshotAndResetDiagnostics();
    }

    public boolean writeAuthoritativeToTexture(int chunkX, int chunkZ, boolean snowy, SnowBiomeTexture tex) {
        float intensity = snowy ? 1.0f : 0.0f;
        // Persist authoritative data to the cache so it survives re-anchor repaints.
        // In authoritative mode the standalone path is inactive, so there is no risk
        // of mixing standalone and authoritative data in the cache.
        applyChunkState(chunkX, chunkZ, intensity, tex, true, "authoritative");
        return intensity > 0.0f;
    }

    /**
     * Persists an authoritative chunk state to the cache without writing to the texture.
     * Used for chunks outside the current anchor window — they must not be written to
     * the texture (aliasing risk) but must be cached so re-anchor repaints can restore
     * them once they fall within the new window.
     */
    public void cacheAuthoritativeOnly(int chunkX, int chunkZ, boolean snowy) {
        float intensity = snowy ? 1.0f : 0.0f;
        long key = packKey(chunkX, chunkZ);
        Float previous = cache.get(key);
        boolean changed = previous == null || Math.abs(previous - intensity) >= 0.004f;
        if (changed) {
            cache.put(key, intensity);
            dirty.add(key);
        }
        sourceMap.put(key, Source.AUTHORITATIVE);
    }

    /**
     * Canonical cached-write path for any source that already has a cache entry.
     */
    public boolean writeCachedToTexture(int chunkX, int chunkZ, SnowBiomeTexture tex, String source) {
        Float intensity = cache.get(packKey(chunkX, chunkZ));
        if (intensity == null) return false;
        applyChunkState(chunkX, chunkZ, intensity, tex, false, source);
        return true;
    }

    public boolean writeCachedToTexture(int chunkX, int chunkZ, SnowBiomeTexture tex) {
        return writeCachedToTexture(chunkX, chunkZ, tex, "cache");
    }

    /**
     * Canonical sample-and-cache path for live world sampling on the main thread.
     */
    public float sampleAndCache(ClientLevel world, int chunkX, int chunkZ,
                                 SnowBiomeTexture tex, String source) {
        float intensity = BiomeSampler.isSnowEligibleChunk(world, chunkX, chunkZ);
        LOGGER.debug("Sampled {} chunk ({}, {}) -> intensity={}", source, chunkX, chunkZ, intensity);
        applyChunkState(chunkX, chunkZ, intensity, tex, true, source);
        return intensity;
    }

    public float sampleAndCache(ClientLevel world, int chunkX, int chunkZ,
                                SnowBiomeTexture tex) {
        return sampleAndCache(world, chunkX, chunkZ, tex, "live-sample");
    }

    /**
     * Canonical final-state path for scanner/background results.
     */
    public boolean writeSampledToTexture(int chunkX, int chunkZ, float intensity,
                                          SnowBiomeTexture tex, String source) {
        applyChunkState(chunkX, chunkZ, intensity, tex, true, source);
        return intensity > 0.0f;
    }

    public boolean writeSampledToTexture(int chunkX, int chunkZ, float intensity,
                                          SnowBiomeTexture tex) {
        return writeSampledToTexture(chunkX, chunkZ, intensity, tex, "precomputed");
    }

    /** Writes all cached chunks to the texture and returns how many texels were restored. */
    public int writeAllCachedToTexture(SnowBiomeTexture tex) {
        if (!loaded) return 0;

        int count = 0;
        for (Map.Entry<Long, Float> entry : cache.entrySet()) {
            long key = entry.getKey();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            applyChunkState(chunkX, chunkZ, entry.getValue(), tex, false, "cache-restore");
            count++;
        }

        LOGGER.warn("Restored {} cached chunks into the texture", count);
        return count;
    }

    /**
     * Writes only cached chunks within a Chebyshev window around the anchor to
     * the texture. Used on re-anchor repaints to avoid writing chunks that would
     * alias into in-range texels.
     *
     * @param tex     texture to write into (anchor already updated before call)
     * @param anchorX anchor chunk X
     * @param anchorZ anchor chunk Z
     * @param radius  Chebyshev radius — chunks with |dx| >= radius or |dz| >= radius
     *                are skipped (they are outside the valid texel window)
     * @return number of chunks written
     */
    public int writeWindowedCacheToTexture(SnowBiomeTexture tex, int anchorX, int anchorZ, int radius) {
        if (!loaded && cache.isEmpty()) return 0;

        int count = 0;
        for (Map.Entry<Long, Float> entry : cache.entrySet()) {
            long key = entry.getKey();
            int chunkX = (int) (key & 0xFFFFFFFFL);
            int chunkZ = (int) (key >>> 32);
            if (Math.abs(chunkX - anchorX) < radius && Math.abs(chunkZ - anchorZ) < radius) {
                applyChunkState(chunkX, chunkZ, entry.getValue(), tex, false, "cache-restore");
                count++;
            }
        }

        LOGGER.warn("Windowed cache restore wrote {} chunks into the texture (anchor [{},{}] radius {})",
                count, anchorX, anchorZ, radius);
        return count;
    }

    public void tickFlush() {
        if (dirty.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastFlushTime > FLUSH_INTERVAL_MS) {
            flushToDisk();
        }
    }

    public int cacheSize() { return cache.size(); }
    public boolean isLoaded() { return loaded; }
    public boolean hasDirtyEntries() { return !dirty.isEmpty(); }

    private void applyChunkState(int chunkX, int chunkZ, float intensity,
                                 SnowBiomeTexture tex, boolean persistToCache,
                                 String source) {
        long key = packKey(chunkX, chunkZ);
        Float previous = cache.get(key);
        Source previousSource = sourceMap.getOrDefault(key, previous == null ? null : Source.UNKNOWN);
        Source incomingSource = classifySource(source, persistToCache);

        // Block DH from zeroing out a chunk that vanilla has confirmed as snowy.
        if (incomingSource == Source.DH && intensity <= 0.0f
                && previous != null && previous > 0.0f
                && (previousSource == Source.VANILLA || previousSource == Source.AUTHORITATIVE)) {
            diagDhFalseBlocked.incrementAndGet();
            LOGGER.debug("Blocked {} zero-intensity overwrite for chunk ({}, {}) because vanilla-confirmed intensity {} is retained",
                    source, chunkX, chunkZ, previous);
            return;
        }

        int texX = tex.texXForChunk(chunkX);
        int texZ = tex.texZForChunk(chunkZ);
        int previousColor = tex.getChunkColor(chunkX, chunkZ);
        boolean textureChanged = tex.setChunk(chunkX, chunkZ, intensity);
        int newColor = tex.getChunkColor(chunkX, chunkZ);
        if (incomingSource == Source.DH) {
            long chunkKey = key;
            long texelKey = (((long) texZ) << 32) | ((long) texX & 0xFFFFFFFFL);
            if (!diagDhUniqueAppliedChunkKeys.add(chunkKey)) {
                diagDhDuplicateChunkApplies.incrementAndGet();
            }
            if (intensity > 0.0f) {
                diagDhUniqueSnowyChunkKeys.add(chunkKey);
            }
            diagDhUniqueTexelsTouched.add(texelKey);
            Long previousOwner = diagDhTexelOwner.putIfAbsent(texelKey, chunkKey);
            if (previousOwner != null && previousOwner.longValue() != chunkKey) {
                diagDhTexelCollisions.incrementAndGet();
                LOGGER.warn("DH texel collision: chunk ({}, {}) maps to texel ({}, {}), already owned this interval by chunk ({}, {})",
                        chunkX, chunkZ, texX, texZ, unpackX(previousOwner), unpackZ(previousOwner));
            }
            if (intensity <= 0.0f) {
                diagDhFalseAllowed.incrementAndGet();
            }
            if (textureChanged) {
                diagTextureChanged.incrementAndGet();
            } else {
                diagTextureSame.incrementAndGet();
            }
            if (intensity > 0.0f) {
                diagTextureSnowyTrue.incrementAndGet();
            } else {
                diagTextureSnowyFalse.incrementAndGet();
            }
            if (intensity > 0.0f && diagDetailedLogsRemaining > 0) {
                diagDetailedLogsRemaining--;
                LOGGER.warn("DH snowy handoff: chunk=({}, {}), texel=({}, {}), previousColor=0x{}, newColor=0x{}, textureChanged={}, persist={}, previousIntensity={}, previousSource={}, incomingSource={}, source={} ",
                        chunkX, chunkZ, texX, texZ, Integer.toHexString(previousColor), Integer.toHexString(newColor),
                        textureChanged, persistToCache,
                        previous == null ? "<null>" : String.format("%.3f", previous),
                        previousSource, incomingSource, source);
            }
        }

        boolean changed = previous == null || Math.abs(previous - intensity) >= 0.004f;
        if (persistToCache) {
            if (changed) {
                cache.put(key, intensity);
                dirty.add(key);
            }
            sourceMap.put(key, incomingSource);
        } else if (previous != null && !sourceMap.containsKey(key)) {
            sourceMap.put(key, Source.UNKNOWN);
        }

        if (!"cache-restore".equals(source)) {
            LOGGER.debug("Applied {} chunk ({}, {}) -> intensity={} persist={} previousIntensity={} previousSource={} incomingSource={} changed={} textureChanged={}",
                    source, chunkX, chunkZ, String.format("%.3f", intensity), persistToCache,
                    previous == null ? "<null>" : String.format("%.3f", previous),
                    previousSource, incomingSource, changed, textureChanged);
        }
    }


    private static int unpackX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    private static int unpackZ(long key) {
        return (int) (key >>> 32);
    }

    /**
     * Returns [chunkX, chunkZ] for every cached chunk whose source is not
     * vanilla-confirmed. These are candidates for the verification sweep —
     * vanilla-confirmed chunks self-correct the next time they load, so they
     * don't need re-querying against the DH repo.
     */
    public java.util.List<long[]> getNonVanillaCachedChunks() {
        java.util.List<long[]> result = new java.util.ArrayList<>();
        for (Map.Entry<Long, Float> entry : cache.entrySet()) {
            long key = entry.getKey();
            Source src = sourceMap.getOrDefault(key, Source.UNKNOWN);
            if (src != Source.VANILLA && src != Source.AUTHORITATIVE) {
                int cx = unpackX(key);
                int cz = unpackZ(key);
                result.add(new long[]{cx, cz});
            }
        }
        return result;
    }

    /**
     * Writes a verification result for a chunk that was previously classified
     * by the DH scanner. Only updates if the new result differs from what is
     * cached and the chunk has not since been confirmed by vanilla.
     *
     * Unlike writeSampledToTexture, this path intentionally allows a DH false
     * to overwrite a previous DH true — that's the whole point of verification.
     * Vanilla-confirmed chunks are left untouched.
     *
     * Returns true if the cached state changed.
     */
    public boolean writeVerifyResultToTexture(int chunkX, int chunkZ, float intensity,
                                              SnowBiomeTexture tex) {
        long key = packKey(chunkX, chunkZ);
        Source src = sourceMap.getOrDefault(key, Source.UNKNOWN);
        if (src == Source.VANILLA || src == Source.AUTHORITATIVE) {
            return false; // vanilla confirmed — leave it alone
        }
        Float previous = cache.get(key);
        if (previous != null && Math.abs(previous - intensity) < 0.004f) {
            return false; // no meaningful change (within ~1/255)
        }
        // State has changed — update cache, texture, and dirty set
        cache.put(key, intensity);
        sourceMap.put(key, Source.DH);
        dirty.add(key);
        tex.setChunk(chunkX, chunkZ, intensity);
        LOGGER.warn("Verify corrected chunk ({}, {}) from intensity={} -> intensity={}",
                chunkX, chunkZ,
                previous == null ? "<null>" : String.format("%.3f", previous),
                String.format("%.3f", intensity));
        return true;
    }

    private void loadFromDisk() {
        if (cacheFile == null || !Files.exists(cacheFile)) {
            LOGGER.warn("No cache file found at {} — starting fresh", cacheFile);
            loaded = true;
            return;
        }

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(cacheFile)))) {

            int magic = in.readInt();
            if (magic != MAGIC) {
                LOGGER.warn("Cache file has wrong magic — ignoring");
                loaded = true;
                return;
            }

            int version = in.readInt();
            if (version != VERSION) {
                LOGGER.warn("Cache version {} is outdated (current: {}) — discarding and rescanning at chunk granularity",
                        version, VERSION);
                loaded = true;
                return;
            }

            in.readLong();

            int count = 0;
            while (true) {
                try {
                    int chunkX = in.readInt();
                    int chunkZ = in.readInt();
                    float intensity = (in.readByte() & 0xFF) / 255.0f;
                    cache.put(packKey(chunkX, chunkZ), intensity);
                    count++;
                } catch (EOFException e) {
                    break;
                }
            }

            LOGGER.warn("Loaded {} chunks from cache ({})", count, cacheFile);
            loaded = true;

        } catch (Exception e) {
            LOGGER.error("Failed to load cache: {}", e.getMessage());
            cache.clear();
            loaded = true;
        }
    }

    private void flushToDisk() {
        if (cacheFile == null || !loaded || cache.isEmpty()) return;

        try {
            Path tmp = cacheFile.resolveSibling("biomes.tmp");

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {

                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(0L);

                for (Map.Entry<Long, Float> entry : cache.entrySet()) {
                    long key = entry.getKey();
                    int chunkX = (int) (key & 0xFFFFFFFFL);
                    int chunkZ = (int) (key >>> 32);
                    out.writeInt(chunkX);
                    out.writeInt(chunkZ);
                    out.writeByte(Math.max(0, Math.min(255, Math.round(entry.getValue() * 255.0f))));
                }
            }

            Files.move(tmp, cacheFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            lastFlushTime = System.currentTimeMillis();
            int dirtyCount = dirty.size();
            dirty.clear();
            LOGGER.warn("Flushed {} cached chunks to disk", dirtyCount);

        } catch (Exception e) {
            LOGGER.error("Failed to flush cache: {}", e.getMessage());
        }
    }


    private static Source classifySource(String source, boolean persistToCache) {
        if (source == null) {
            return Source.UNKNOWN;
        }
        if (source.startsWith("dh-")) {
            return Source.DH;
        }
        if (source.startsWith("vanilla-") || source.contains("refine")) {
            return Source.VANILLA;
        }
        if (source.startsWith("authoritative")) {
            return Source.AUTHORITATIVE;
        }
        return Source.UNKNOWN;
    }

    private static Path getCacheRoot() {
        return Paths.get("complementary_snow_cache");
    }

    private static String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static long packKey(int x, int z) {
        return ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
    }
}
