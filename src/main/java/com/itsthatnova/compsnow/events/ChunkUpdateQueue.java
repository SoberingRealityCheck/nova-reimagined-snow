package com.itsthatnova.compsnow.events;

import com.itsthatnova.compsnow.texture.SnowBiomeTexture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Queue of chunk positions needing biome data written to the texture.
 * Used for vanilla-range chunks loaded via ClientChunkEvents.
 * Processing (sampling vs cache lookup) is handled by SnowBiomeManager.
 */
public class ChunkUpdateQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.queue");

    private final Deque<long[]> queue  = new ArrayDeque<>();
    private final Set<Long>     queued = new HashSet<>();

    public void enqueue(int chunkX, int chunkZ) {
        long key = packKey(chunkX, chunkZ);
        if (queued.add(key)) {
            queue.addLast(new long[]{chunkX, chunkZ, key});
        }
    }

    /**
     * Enqueues all chunks within vanilla render distance for initial population.
     */
    public void enqueueAll(SnowBiomeTexture tex) {
        if (!tex.isAllocated()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int playerChunkX = (int) mc.player.getX() >> 4;
        int playerChunkZ = (int) mc.player.getZ() >> 4;
        int vanillaRadius = mc.options.renderDistance().get();

        for (int cx = playerChunkX - vanillaRadius; cx <= playerChunkX + vanillaRadius; cx++)
            for (int cz = playerChunkZ - vanillaRadius; cz <= playerChunkZ + vanillaRadius; cz++)
                enqueue(cx, cz);

        LOGGER.warn("Queued {} vanilla chunks for population", queue.size());
    }

    /**
     * Polls the next chunk entry from the queue.
     * Returns [chunkX, chunkZ, key] or null if empty.
     */
    public long[] poll() {
        long[] entry = queue.pollFirst();
        if (entry != null) queued.remove(entry[2]);
        return entry;
    }

    public boolean isEmpty() { return queue.isEmpty(); }
    public int size()        { return queue.size(); }

    public void clear() {
        queue.clear();
        queued.clear();
    }

    private static long packKey(int x, int z) {
        return ((long) z << 32) | ((long) x & 0xFFFFFFFFL);
    }
}
