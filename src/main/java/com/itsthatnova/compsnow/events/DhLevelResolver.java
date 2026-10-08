package com.itsthatnova.compsnow.events;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the active Distant Horizons level wrapper for the current client world.
 *
 * Singleplayer and multiplayer need different retrieval branches:
 *  - singleplayer: use worldProxy.getSinglePlayerLevel()
 *  - multiplayer: inspect loaded DH level wrappers and pick the best match for the
 *    current client dimension.
 */
public final class DhLevelResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.dhlevel");

    private static volatile String lastResolvedDescriptor = null;
    private static volatile String lastFailureDescriptor = null;

    private DhLevelResolver() {}

    public static boolean isDhModPresent() {
        return FabricLoader.getInstance().isModLoaded("distanthorizons");
    }

    public static boolean isSingleplayerSession() {
        return Minecraft.getInstance().getSingleplayerServer() != null;
    }

    public static boolean isDhApiReady() {
        return isDhModPresent()
                && DhApi.Delayed.worldProxy != null
                && DhApi.Delayed.terrainRepo != null;
    }


    public static int resolveConfiguredDhChunkRenderDistance(int fallback) {
        if (!isDhApiReady()) {
            return fallback;
        }

        try {
            if (DhApi.Delayed.configs == null || DhApi.Delayed.configs.graphics() == null) {
                return fallback;
            }

            IDhApiConfigValue<Integer> value = DhApi.Delayed.configs.graphics().chunkRenderDistance();
            if (value == null) {
                return fallback;
            }

            Integer active = value.getValue();
            if (active == null || active <= 0) {
                return fallback;
            }
            return active;
        } catch (Exception e) {
            logFailure("Unable to query DH chunk render distance: " + e.getMessage());
            return fallback;
        }
    }

    public static IDhApiLevelWrapper resolveActiveLevelWrapper(ClientLevel world) {
        if (world == null || !isDhApiReady()) {
            return null;
        }

        try {
            if (!DhApi.Delayed.worldProxy.worldLoaded()) {
                logFailure("DH worldProxy reports that no world is loaded yet");
                return null;
            }
        } catch (Exception e) {
            logFailure("Unable to query DH worldLoaded(): " + e.getMessage());
            return null;
        }

        if (isSingleplayerSession()) {
            return resolveSingleplayer(world);
        }
        return resolveMultiplayer(world);
    }

    private static IDhApiLevelWrapper resolveSingleplayer(ClientLevel world) {
        try {
            IDhApiLevelWrapper wrapper = DhApi.Delayed.worldProxy.getSinglePlayerLevel();
            if (wrapper == null) {
                logFailure("Singleplayer DH wrapper is null");
                return null;
            }
            logResolved(wrapper, "singleplayer");
            return wrapper;
        } catch (Exception e) {
            logFailure("Unable to obtain singleplayer DH wrapper: " + e.getMessage());
            return null;
        }
    }

    private static IDhApiLevelWrapper resolveMultiplayer(ClientLevel world) {
        Iterable<IDhApiLevelWrapper> wrappers;
        try {
            wrappers = DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers();
        } catch (Exception e) {
            logFailure("Unable to enumerate loaded DH level wrappers: " + e.getMessage());
            return null;
        }

        if (wrappers == null) {
            logFailure("DH returned null for loaded level wrappers");
            return null;
        }

        ResourceKey<Level> key = world.dimension();
        String targetFull = key.identifier().toString().toLowerCase();
        String targetPath = key.identifier().getPath().toLowerCase();

        IDhApiLevelWrapper best = null;
        int bestScore = Integer.MIN_VALUE;
        int wrapperCount = 0;
        IDhApiLevelWrapper onlyWrapper = null;

        for (IDhApiLevelWrapper wrapper : wrappers) {
            if (wrapper == null) continue;
            wrapperCount++;
            onlyWrapper = wrapper;

            int score = scoreWrapper(wrapper, world, targetFull, targetPath);
            if (score > bestScore) {
                bestScore = score;
                best = wrapper;
            }
        }

        if (best != null && bestScore > 0) {
            logResolved(best, "multiplayer-score=" + bestScore);
            return best;
        }

        if (wrapperCount == 1 && onlyWrapper != null) {
            logResolved(onlyWrapper, "multiplayer-fallback-single-wrapper");
            return onlyWrapper;
        }

        logFailure("Could not match current dimension '" + targetFull + "' to a DH level wrapper (count=" + wrapperCount + ")");
        return null;
    }

    private static int scoreWrapper(IDhApiLevelWrapper wrapper, ClientLevel world, String targetFull, String targetPath) {
        int score = 0;

        try {
            Object wrapped = wrapper.getWrappedMcObject();
            if (wrapped == world) {
                score += 1000;
            }
        } catch (Exception ignored) {
        }

        score += scoreString(safeLower(wrapper.getDimensionName()), targetFull, targetPath, 120);
        score += scoreString(safeLower(wrapper.getDhIdentifier()), targetFull, targetPath, 100);

        if (Level.OVERWORLD.equals(world.dimension())) {
            String dimName = safeLower(wrapper.getDimensionName());
            String dhId = safeLower(wrapper.getDhIdentifier());
            if (dimName.contains("overworld") || dhId.contains("overworld")) {
                score += 50;
            }
        }

        return score;
    }

    private static int scoreString(String value, String targetFull, String targetPath, int exactWeight) {
        if (value.isEmpty()) return 0;
        int score = 0;
        if (value.equals(targetFull) || value.equals(targetPath)) {
            score += exactWeight;
        }
        if (value.contains(targetFull)) {
            score += exactWeight / 2;
        }
        if (value.contains(targetPath)) {
            score += exactWeight / 3;
        }
        return score;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private static void logResolved(IDhApiLevelWrapper wrapper, String mode) {
        String descriptor = mode + " -> dim=" + safe(wrapper.getDimensionName()) + ", id=" + safe(wrapper.getDhIdentifier());
        if (!descriptor.equals(lastResolvedDescriptor)) {
            LOGGER.warn("Resolved active DH level wrapper: {}", descriptor);
            lastResolvedDescriptor = descriptor;
            lastFailureDescriptor = null;
        }
    }

    private static void logFailure(String message) {
        if (!message.equals(lastFailureDescriptor)) {
            LOGGER.warn("DH level wrapper unavailable: {}", message);
            lastFailureDescriptor = message;
            lastResolvedDescriptor = null;
        }
    }

    private static String safe(String value) {
        return value == null ? "<null>" : value;
    }
}
