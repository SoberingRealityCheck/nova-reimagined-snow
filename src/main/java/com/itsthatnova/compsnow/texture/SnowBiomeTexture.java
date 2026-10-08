package com.itsthatnova.compsnow.texture;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages two textures used by the Nova Reimagined Snow shader.
 *
 * The texture is chunk-granularity: one texel per chunk column (XZ).
 * Texel layout is player-relative — the player's anchor chunk always maps
 * to texel (HALF_SIZE, HALF_SIZE). This prevents chunk coordinate aliasing
 * on large worlds where absolute modular indexing would cause distant chunks
 * to overwrite nearby chunks sharing the same texel.
 *
 * 1. snowBiomeMap (compsnow:snow_biome_map)
 *    Square texture, one texel per chunk.
 *    Written at floorMod(chunkX - anchorX + HALF_SIZE, size).
 *    Red channel: 0xFF = snow eligible, 0x00 = not eligible.
 *
 * 2. snowBiomeMeta (compsnow:snow_biome_meta)
 *    1x1 RGBA:
 *      R = (log2(size) - 5) / 5.0  for sizes 32-256
 *      G = floorMod(anchorChunkX, 256) / 255.0  (anchor X, mod 256)
 *      B = floorMod(anchorChunkZ, 256) / 255.0  (anchor Z, mod 256)
 *      A = 255 (signals mod is active)
 *
 *    The shader decodes anchorX as round(meta.g * 255) and uses it to
 *    apply the same player-relative offset when sampling the map.
 */
public class SnowBiomeTexture {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.texture");

    public static final Identifier MAP_ID  = Identifier.fromNamespaceAndPath("compsnow", "snow_biome_map");
    public static final Identifier META_ID = Identifier.fromNamespaceAndPath("compsnow", "snow_biome_meta");

    // Texture size in chunks. 256 gives a 128-chunk radius around the player anchor,
    // covering any practical DH render distance (192 chunks) with margin to spare.
    // At 256x256 the upload cost is ~256KB -- effectively free even when dirty every tick.
    public static final int MAX_SIZE = 2048;

    // Half the texture size. The player anchor always sits at texel (HALF_SIZE, HALF_SIZE).
    public static final int HALF_SIZE = MAX_SIZE / 2;

    private DynamicTexture mapTexture;
    private DynamicTexture metaTexture;

    private int size;

    // Player-relative anchor. Chunk (cx, cz) maps to texel:
    //   tx = floorMod(cx - anchorChunkX + HALF_SIZE, size)
    //   tz = floorMod(cz - anchorChunkZ + HALF_SIZE, size)
    // The anchor is encoded in the G and B channels of the meta texture so the
    // shader applies the same offset. Only the mod-256 value is needed since the
    // shader's modular arithmetic is periodic with the same period.
    private int anchorChunkX = 0;
    private int anchorChunkZ = 0;

    public SnowBiomeTexture() {}

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void allocate(int newSize) {
        release();
        this.size = newSize;

        NativeImage mapImage = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        mapTexture = new DynamicTexture(() -> "compsnow", mapImage);
        Minecraft.getInstance().getTextureManager()
                .register(MAP_ID, mapTexture);

        NativeImage metaImage = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        metaTexture = new DynamicTexture(() -> "compsnow", metaImage);
        Minecraft.getInstance().getTextureManager()
                .register(META_ID, metaTexture);

        updateMeta();

        // Upload immediately so the shader sees meta.a = 1.0 (mod active) from the
        // very first frame rather than waiting for the first dirty-chunk tick.
        upload();

        LOGGER.warn("Allocated snow biome textures ({}x{} chunks)", size, size);
    }

    public void release() {
        if (mapTexture != null) {
            Minecraft.getInstance().getTextureManager().release(MAP_ID);
            mapTexture.close();
            mapTexture = null;
        }
        if (metaTexture != null) {
            Minecraft.getInstance().getTextureManager().release(META_ID);
            metaTexture.close();
            metaTexture = null;
        }
        size = 0;
        LOGGER.warn("Released snow biome textures");
    }

    public boolean isAllocated() { return mapTexture != null; }

    public void clear() {
        if (!isAllocated()) return;
        NativeImage image = mapTexture.getPixels();
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                image.setPixelABGR(x, z, 0xFF000000);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Anchor
    // -------------------------------------------------------------------------

    /**
     * Updates the player-relative anchor to the given chunk position.
     * All subsequent chunk reads and writes use this anchor to compute texel
     * coordinates. The meta texture is updated immediately so the shader sees
     * the new anchor on the next upload cycle.
     *
     * Called on world join (before allocate so the initial upload carries the
     * correct anchor) and on re-anchor events (teleport, periodic drift check).
     */
    public void setAnchor(int chunkX, int chunkZ) {
        this.anchorChunkX = chunkX;
        this.anchorChunkZ = chunkZ;
        updateMeta();
    }

    public int getAnchorX() { return anchorChunkX; }
    public int getAnchorZ() { return anchorChunkZ; }

    // -------------------------------------------------------------------------
    // Chunk writes
    // -------------------------------------------------------------------------

    /**
     * Maps a world chunk X coordinate to a texture X coordinate using the
     * current player-relative anchor. The player anchor maps to HALF_SIZE.
     */
    public int texXForChunk(int chunkX) {
        return size <= 0 ? 0 : Math.floorMod(chunkX - anchorChunkX + HALF_SIZE, size);
    }

    /**
     * Maps a world chunk Z coordinate to a texture Z coordinate using the
     * current player-relative anchor. The player anchor maps to HALF_SIZE.
     */
    public int texZForChunk(int chunkZ) {
        return size <= 0 ? 0 : Math.floorMod(chunkZ - anchorChunkZ + HALF_SIZE, size);
    }

    public int getChunkColor(int chunkX, int chunkZ) {
        if (!isAllocated()) return 0;
        return Pix.getAbgr(mapTexture.getPixels(), texXForChunk(chunkX), texZForChunk(chunkZ));
    }

    public boolean setChunk(int chunkX, int chunkZ, float intensity) {
        if (!isAllocated()) return false;
        int tx = texXForChunk(chunkX);
        int tz = texZForChunk(chunkZ);
        // NativeImage ABGR: A=255, B=0, G=0, R=intensityByte (0-255)
        int intensityByte = Math.max(0, Math.min(255, Math.round(intensity * 255.0f)));
        int color = 0xFF000000 | intensityByte;
        int previous = Pix.getAbgr(mapTexture.getPixels(), tx, tz);
        mapTexture.getPixels().setPixelABGR(tx, tz, color);
        return previous != color;
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    /**
     * Encodes texture size and player anchor into the 1x1 meta texture.
     *
     * Channel layout (ABGR in NativeImage int, RGBA in shader):
     *   R = (log2(size) - 5) / 6.0   size encoding, 32-2048 -> 0.0-1.0
     *   G = (floorMod(anchorChunkX, 2048) & 0xFF) / 255.0   anchor X low 8 bits
     *   B = (floorMod(anchorChunkZ, 2048) & 0xFF) / 255.0   anchor Z low 8 bits
     *   A = 0x80 | (anchorX_high3 << 4) | (anchorZ_high3 << 1)
     *       bit7 = active flag (always 1 when active)
     *       bits4-6 = high 3 bits of anchorX mod 2048
     *       bits1-3 = high 3 bits of anchorZ mod 2048
     *
     * Together G + A[4-6] give 11 bits of anchor X precision (mod 2048).
     * Together B + A[1-3] give 11 bits of anchor Z precision (mod 2048).
     * This is required for textures larger than 256 chunks — the old mod-256
     * encoding caused incorrect texel lookups at texture sizes >= 512.
     */
    private void updateMeta() {
        if (metaTexture == null) return;
        float sizeEncoded = (log2(size) - 5.0f) / 6.0f;
        int r = clamp255(Math.round(sizeEncoded * 255));
        int anchorXMod = Math.floorMod(anchorChunkX, 2048); // 0-2047, 11 bits
        int anchorZMod = Math.floorMod(anchorChunkZ, 2048);
        int g = anchorXMod & 0xFF;                          // low 8 bits of X
        int b = anchorZMod & 0xFF;                          // low 8 bits of Z
        int aHighBits = (((anchorXMod >> 8) & 0x7) << 4)   // bits 4-6 = X high 3
                      | (((anchorZMod >> 8) & 0x7) << 1);  // bits 1-3 = Z high 3
        int a = 0x80 | aHighBits;                          // bit 7 = active flag
        // NativeImage ABGR: A=a, B=b, G=g, R=r
        int packed = (a << 24) | (b << 16) | (g << 8) | r;
        metaTexture.getPixels().setPixelABGR(0, 0, packed);
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    public void upload() {
        if (!isAllocated()) return;
        mapTexture.upload();
        metaTexture.upload();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getSize() { return size; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static float log2(int value) {
        return (float)(Math.log(value) / Math.log(2));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
