package com.itsthatnova.compsnow.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes 8 resolved grass variants plus 5 resolved DH LOD terrain textures for shader sampling.
 */
public final class DhLodTextureSet {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.dhtextures");

    public static final Identifier GRASS_0_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_0");
    public static final Identifier GRASS_1_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_1");
    public static final Identifier GRASS_2_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_2");
    public static final Identifier GRASS_3_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_3");
    public static final Identifier GRASS_4_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_4");
    public static final Identifier GRASS_5_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_5");
    public static final Identifier GRASS_6_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_6");
    public static final Identifier GRASS_7_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_grass_7");
    public static final Identifier SNOW_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_snow");
    public static final Identifier DIRT_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_dirt");
    public static final Identifier STONE_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_stone");
    public static final Identifier DEEPSLATE_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_deepslate");
    public static final Identifier SAND_ID = Identifier.fromNamespaceAndPath("compsnow", "dh_lod_sand");

    private static final List<Identifier> IDS = List.of(
            GRASS_0_ID, GRASS_1_ID, GRASS_2_ID, GRASS_3_ID,
            GRASS_4_ID, GRASS_5_ID, GRASS_6_ID, GRASS_7_ID,
            SNOW_ID, DIRT_ID, STONE_ID, DEEPSLATE_ID, SAND_ID
    );

    private final List<DynamicTexture> textures = new ArrayList<>();

    public void replace(ResolvedDhLodTextureSet textureSet, String reason) {
        release();
        Minecraft client = Minecraft.getInstance();
        for (int i = 0; i < IDS.size(); i++) {
            DynamicTexture texture = new DynamicTexture(() -> "compsnow", textureSet.slots().get(i).image());
            client.getTextureManager().register(IDS.get(i), texture);
            texture.upload();
            textures.add(texture);
        }
        LOGGER.warn("Uploaded {} resolved DH LOD textures [{}]", textures.size(), reason);
    }

    public void release() {
        Minecraft client = Minecraft.getInstance();
        for (int i = 0; i < textures.size() && i < IDS.size(); i++) {
            client.getTextureManager().release(IDS.get(i));
            textures.get(i).close();
        }
        textures.clear();
    }
}
