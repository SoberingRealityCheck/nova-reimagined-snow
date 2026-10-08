package com.itsthatnova.compsnow.texture;

import com.itsthatnova.compsnow.events.SnowBiomeManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rebuilds resolved snow textures whenever the active resource pack stack reloads.
 */
public final class SnowVariantReloadListener implements ResourceManagerReloadListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.variants");

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        LOGGER.warn("Resource reload detected, rebuilding resolved snow variants");
        SnowBiomeManager.INSTANCE.refreshSnowVariantTextures("resource reload", manager);
    }
}
