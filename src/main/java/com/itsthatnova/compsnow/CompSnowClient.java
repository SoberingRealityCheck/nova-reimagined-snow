package com.itsthatnova.compsnow;

import com.itsthatnova.compsnow.events.SnowBiomeManager;
import com.itsthatnova.compsnow.texture.SnowVariantReloadListener;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side entrypoint for Nova Reimagined Snow.
 */
@Environment(EnvType.CLIENT)
public class CompSnowClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("compsnow");
    public static final String BUILD_MARKER = "snow-variant-resolver-2026-03-27";

    private ClientLevel lastWorld = null;

    @Override
    public void onInitializeClient() {
        LOGGER.warn("Nova Reimagined Snow initialising [{}]", BUILD_MARKER);

        SnowBiomeManager.INSTANCE.initializeSnowVariantConfig();
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                Identifier.fromNamespaceAndPath("compsnow", "snow_variant_reload_listener"),
                new SnowVariantReloadListener());
        SnowBiomeManager.INSTANCE.refreshSnowVariantTextures("client init");

        // Level join
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(() -> {
                lastWorld = client.level;
                String worldKey = resolveWorldKey(client);
                SnowBiomeManager.INSTANCE.onWorldJoin(client.level, worldKey);
            })
        );

        // Level leave / disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            client.execute(() -> {
                lastWorld = null;
                SnowBiomeManager.INSTANCE.onWorldLeave();
            })
        );

        // Chunk load
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
            SnowBiomeManager.INSTANCE.onChunkLoad(chunk.getPos().x(), chunk.getPos().z())
        );

        // Chunk unload — no-op now since cache keeps data valid
        // ClientChunkEvents.CHUNK_UNLOAD kept for future use

        // Per-tick
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) return;

            // Detect dimension change
            if (lastWorld != null && lastWorld != client.level) {
                LOGGER.warn("Dimension change detected [{}]", BUILD_MARKER);
                lastWorld = client.level;
                SnowBiomeManager.INSTANCE.onDimensionChange(client.level);
            }

            SnowBiomeManager.INSTANCE.onClientTick(client);
        });

        LOGGER.warn("Nova Reimagined Snow ready [{}]", BUILD_MARKER);
    }

    /**
     * Resolves a unique world key for cache file naming.
     * Singleplayer: levelName_seed
     * Multiplayer:  serverAddress
     */
    private String resolveWorldKey(Minecraft client) {
        // Multiplayer
        ServerData serverInfo = client.getCurrentServer();
        if (serverInfo != null) {
            return "server_" + serverInfo.ip;
        }

        // Singleplayer — get level name and seed
        try {
            if (client.getSingleplayerServer() != null) {
                long seed = client.getSingleplayerServer().overworld().getSeed();
                String levelName = client.getSingleplayerServer().getWorldData().getLevelName();
                return levelName + "_" + seed;
            }
        } catch (Exception e) {
            LOGGER.warn("Could not resolve singleplayer world key: {}", e.getMessage());
        }

        // Fallback
        return "unknown_world";
    }

}
