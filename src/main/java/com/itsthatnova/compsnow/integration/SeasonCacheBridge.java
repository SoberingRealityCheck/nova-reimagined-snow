package com.itsthatnova.compsnow.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Optional reflection bridge to Season Cache's client API.
 *
 * Nova must continue to run standalone when Season Cache is absent, so this
 * class deliberately avoids any compile-time dependency on Season Cache.
 */
public final class SeasonCacheBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.seasoncache");
    private static final String MOD_ID = "seasoncache";
    private static final String API_CLASS = "com.seasoncache.api.SeasonCacheClientApi";
    private static final String EVENT_CLASS = "com.seasoncache.api.SeasonCacheClientApi$ClientSnowEvent";
    private static final String EVENT_TYPE_CLASS = "com.seasoncache.api.SeasonCacheClientApi$ClientSnowEventType";

    private static final boolean MOD_PRESENT = FabricLoader.getInstance().isModLoaded(MOD_ID);
    private static final BridgeHandle HANDLE = MOD_PRESENT ? tryResolve() : null;
    private static final boolean AVAILABLE = HANDLE != null;

    private SeasonCacheBridge() {
    }

    public static boolean isModPresent() {
        return MOD_PRESENT;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean isAuthoritativeSessionActive() {
        if (!AVAILABLE) return false;
        try {
            return (Boolean) HANDLE.isAuthoritativeSessionActive.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.warn("Season Cache bridge call failed: {}", e.getMessage());
            return false;
        }
    }

    public static boolean isSnapshotInProgress(ResourceKey<Level> dimension) {
        if (!AVAILABLE || dimension == null) return false;
        try {
            return (Boolean) HANDLE.isSnapshotInProgress.invoke(null, dimension);
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.warn("Season Cache bridge snapshot query failed: {}", e.getMessage());
            return false;
        }
    }

    public static Integer currentEpoch(ResourceKey<Level> dimension) {
        if (!AVAILABLE || dimension == null) return null;
        try {
            return (Integer) HANDLE.currentEpoch.invoke(null, dimension);
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.warn("Season Cache bridge epoch query failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Returns the authoritative snow state for a specific chunk, or null if
     * Season Cache has not yet sent data for this chunk this session.
     * Used by onDhChunkModified to apply the correct state when DH regenerates LODs.
     */
    public static Boolean getChunkSnowState(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!AVAILABLE || HANDLE.getChunkSnowState == null || dimension == null) return null;
        try {
            return (Boolean) HANDLE.getChunkSnowState.invoke(null, dimension, chunkX, chunkZ);
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.warn("Season Cache bridge chunk state query failed: {}", e.getMessage());
            return null;
        }
    }

    public static List<AuthoritativeEvent> drainEvents() {
        if (!AVAILABLE) return Collections.emptyList();
        try {
            @SuppressWarnings("unchecked")
            List<Object> rawEvents = (List<Object>) HANDLE.drainEvents.invoke(null);
            if (rawEvents == null || rawEvents.isEmpty()) {
                return Collections.emptyList();
            }
            java.util.ArrayList<AuthoritativeEvent> events = new java.util.ArrayList<>(rawEvents.size());
            for (Object raw : rawEvents) {
                Object rawType = HANDLE.eventType.invoke(raw);
                String typeName = String.valueOf(rawType);
                @SuppressWarnings("unchecked")
                ResourceKey<Level> dimension = (ResourceKey<Level>) HANDLE.eventDimension.invoke(raw);
                int epoch = (Integer) HANDLE.eventEpoch.invoke(raw);
                int chunkX = (Integer) HANDLE.eventChunkX.invoke(raw);
                int chunkZ = (Integer) HANDLE.eventChunkZ.invoke(raw);
                boolean snowy = (Boolean) HANDLE.eventSnowy.invoke(raw);
                EventType type = switch (typeName) {
                    case "RESET" -> EventType.RESET;
                    case "CHUNK_STATE" -> EventType.CHUNK_STATE;
                    default -> EventType.UNKNOWN;
                };
                events.add(new AuthoritativeEvent(type, dimension, epoch, chunkX, chunkZ, snowy));
            }
            return events;
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.warn("Season Cache bridge event drain failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static BridgeHandle tryResolve() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Class<?> eventClass = Class.forName(EVENT_CLASS);
            Class.forName(EVENT_TYPE_CLASS);
            Method isAuthoritativeSessionActive = apiClass.getMethod("isAuthoritativeSessionActive");
            Method isSnapshotInProgress = apiClass.getMethod("isSnapshotInProgress", ResourceKey.class);
            Method currentEpoch = apiClass.getMethod("currentEpoch", ResourceKey.class);
            Method drainEvents = apiClass.getMethod("drainEvents");
            Method eventType = eventClass.getMethod("type");
            Method eventDimension = eventClass.getMethod("dimension");
            Method eventEpoch = eventClass.getMethod("epoch");
            Method eventChunkX = eventClass.getMethod("chunkX");
            Method eventChunkZ = eventClass.getMethod("chunkZ");
            Method eventSnowy = eventClass.getMethod("snowy");
            // getChunkSnowState is optional — older SC versions may not have it
            Method getChunkSnowState = null;
            try {
                getChunkSnowState = apiClass.getMethod("getChunkSnowState",
                        ResourceKey.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                LOGGER.warn("Season Cache bridge: getChunkSnowState not available (older SC version)");
            }
            LOGGER.warn("Season Cache bridge active via reflection");
            return new BridgeHandle(
                    isAuthoritativeSessionActive,
                    isSnapshotInProgress,
                    currentEpoch,
                    drainEvents,
                    eventType,
                    eventDimension,
                    eventEpoch,
                    eventChunkX,
                    eventChunkZ,
                    eventSnowy,
                    getChunkSnowState
            );
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Season Cache mod is present but Nova could not resolve its client API: {}", e.getMessage());
            return null;
        }
    }

    public enum EventType {
        RESET,
        CHUNK_STATE,
        UNKNOWN
    }

    public record AuthoritativeEvent(
            EventType type,
            ResourceKey<Level> dimension,
            int epoch,
            int chunkX,
            int chunkZ,
            boolean snowy
    ) {
    }

    private record BridgeHandle(
            Method isAuthoritativeSessionActive,
            Method isSnapshotInProgress,
            Method currentEpoch,
            Method drainEvents,
            Method eventType,
            Method eventDimension,
            Method eventEpoch,
            Method eventChunkX,
            Method eventChunkZ,
            Method eventSnowy,
            Method getChunkSnowState
    ) {
    }
}
