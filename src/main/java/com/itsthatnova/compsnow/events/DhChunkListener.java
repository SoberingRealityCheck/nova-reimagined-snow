package com.itsthatnova.compsnow.events;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiChunkModifiedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to DH's chunk-modified event and forwards chunk coords + saved level wrapper
 * to SnowBiomeManager on the main client thread.
 */
public class DhChunkListener extends DhApiChunkModifiedEvent {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.dhlistener");

    private static DhChunkListener activeListener = null;

    private DhChunkListener() {}

    public static void register() {
        if (activeListener != null) {
            unregister();
        }
        try {
            activeListener = new DhChunkListener();
            DhApi.events.bind(DhApiChunkModifiedEvent.class, activeListener);
            LOGGER.warn("DH chunk listener registered");
        } catch (Exception e) {
            LOGGER.warn("Failed to register DH chunk listener: {}", e.getMessage());
            activeListener = null;
        }
    }

    public static void unregister() {
        if (activeListener == null) return;
        try {
            DhApi.events.unbind(DhApiChunkModifiedEvent.class, activeListener.getClass());
            LOGGER.warn("DH chunk listener unregistered");
        } catch (Exception e) {
            LOGGER.warn("Failed to unregister DH chunk listener: {}", e.getMessage());
        } finally {
            activeListener = null;
        }
    }

    @Override
    public void onChunkModified(DhApiEventParam<DhApiChunkModifiedEvent.EventParam> input) {
        if (input == null || input.value == null) return;

        int chunkX = input.value.chunkX;
        int chunkZ = input.value.chunkZ;
        IDhApiLevelWrapper levelWrapper = input.value.levelWrapper;

        LOGGER.debug("DH listener received chunk ({}, {}) with levelWrapper={}",
                chunkX, chunkZ, levelWrapper != null ? levelWrapper.getClass().getName() : "null");

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SnowBiomeManager.INSTANCE.onDhChunkModified(levelWrapper, chunkX, chunkZ));
    }
}
