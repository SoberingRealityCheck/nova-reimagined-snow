package com.itsthatnova.compsnow.texture;

import com.itsthatnova.compsnow.config.SnowVariantConfig;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves DH LOD material texture overrides, including 8 propagated grass variants.
 */
public final class DhLodTextureResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.dhtextures");
    public static final Identifier NEUTRAL_WHITE_ID = Identifier.fromNamespaceAndPath("compsnow", "neutral_white");

    public ResolvedDhLodTextureSet resolve(ResourceManager resourceManager, SnowVariantConfig config) {
        SnowVariantConfig.DhLodTextures settings = config.getDhLodTextures();
        List<ResolvedDhLodTextureSet.Slot> slots = new ArrayList<>(13);

        slots.addAll(resolveGrassVariants(resourceManager, settings));
        slots.add(resolveSingleSlot(resourceManager, settings.isEnabled(), "snow", settings.getSnow()));
        slots.add(resolveSingleSlot(resourceManager, settings.isEnabled(), "dirt", settings.getDirt()));
        slots.add(resolveSingleSlot(resourceManager, settings.isEnabled(), "stone", settings.getStone()));
        slots.add(resolveSingleSlot(resourceManager, settings.isEnabled(), "deepslate", settings.getDeepslate()));
        slots.add(resolveSingleSlot(resourceManager, settings.isEnabled(), "sand", settings.getSand()));

        LOGGER.warn("Resolved DH LOD textures: grassVariants={}, snow={}, dirt={}, stone={}, deepslate={}, sand={}",
                describe(slots.get(0)), describe(slots.get(8)), describe(slots.get(9)),
                describe(slots.get(10)), describe(slots.get(11)), describe(slots.get(12)));

        return new ResolvedDhLodTextureSet(List.copyOf(slots));
    }

    private List<ResolvedDhLodTextureSet.Slot> resolveGrassVariants(ResourceManager resourceManager, SnowVariantConfig.DhLodTextures settings) {
        List<ResolvedDhLodTextureSet.Slot> resolvedGrass = new ArrayList<>();
        if (!settings.isEnabled()) {
            for (int i = 0; i < SnowVariantConfig.DH_GRASS_VARIANT_SLOTS; i++) {
                resolvedGrass.add(new ResolvedDhLodTextureSet.Slot(createNeutralWhite(), NEUTRAL_WHITE_ID,
                        ResolvedDhLodTextureSet.SourceType.DISABLED));
            }
            return resolvedGrass;
        }

        List<ResolvedDhLodTextureSet.Slot> validSources = new ArrayList<>();
        List<String> rawVariants = settings.getGrassVariants();
        for (int i = 0; i < SnowVariantConfig.DH_GRASS_VARIANT_SLOTS; i++) {
            String raw = i < rawVariants.size() ? rawVariants.get(i) : "";
            if (raw == null || raw.isBlank()) continue;
            Identifier id = Identifier.tryParse(raw.trim());
            if (id == null) {
                LOGGER.warn("DH grass variant slot {} is not a valid identifier: {}", i, raw);
                continue;
            }
            NativeImage image = loadImage(resourceManager, id);
            if (image != null) {
                validSources.add(new ResolvedDhLodTextureSet.Slot(image, id, ResolvedDhLodTextureSet.SourceType.CONFIGURED));
            } else {
                LOGGER.warn("DH grass variant slot {} could not resolve {}", i, id);
            }
        }

        if (validSources.isEmpty()) {
            for (int i = 0; i < SnowVariantConfig.DH_GRASS_VARIANT_SLOTS; i++) {
                resolvedGrass.add(new ResolvedDhLodTextureSet.Slot(createNeutralWhite(), NEUTRAL_WHITE_ID,
                        ResolvedDhLodTextureSet.SourceType.NEUTRAL_FALLBACK));
            }
            return resolvedGrass;
        }

        for (int i = 0; i < SnowVariantConfig.DH_GRASS_VARIANT_SLOTS; i++) {
            ResolvedDhLodTextureSet.Slot source = validSources.get(i % validSources.size());
            ResolvedDhLodTextureSet.SourceType type = i < validSources.size()
                    ? source.sourceType()
                    : ResolvedDhLodTextureSet.SourceType.PROPAGATED;
            resolvedGrass.add(new ResolvedDhLodTextureSet.Slot(copyImage(source.image()), source.sourceId(), type));
        }

        return resolvedGrass;
    }

    private ResolvedDhLodTextureSet.Slot resolveSingleSlot(ResourceManager resourceManager, boolean enabled, String slotName, String rawPath) {
        if (!enabled) {
            return new ResolvedDhLodTextureSet.Slot(createNeutralWhite(), NEUTRAL_WHITE_ID,
                    ResolvedDhLodTextureSet.SourceType.DISABLED);
        }

        if (rawPath != null && !rawPath.isBlank()) {
            Identifier id = Identifier.tryParse(rawPath.trim());
            if (id == null) {
                LOGGER.warn("DH LOD texture slot {} is not a valid identifier: {}", slotName, rawPath);
            } else {
                NativeImage image = loadImage(resourceManager, id);
                if (image != null) {
                    LOGGER.warn("DH LOD texture slot {} resolved to {}", slotName, id);
                    return new ResolvedDhLodTextureSet.Slot(image, id,
                            ResolvedDhLodTextureSet.SourceType.CONFIGURED);
                }
                LOGGER.warn("DH LOD texture slot {} could not resolve {} — using neutral fallback", slotName, id);
            }
        }

        return new ResolvedDhLodTextureSet.Slot(createNeutralWhite(), NEUTRAL_WHITE_ID,
                ResolvedDhLodTextureSet.SourceType.NEUTRAL_FALLBACK);
    }

    private String describe(ResolvedDhLodTextureSet.Slot slot) {
        return slot.sourceType() + ":" + slot.sourceId();
    }

    private NativeImage loadImage(ResourceManager resourceManager, Identifier id) {
        try {
            Optional<Resource> resource = resourceManager.getResource(id);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream inputStream = resource.get().open()) {
                return NativeImage.read(inputStream);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed reading DH LOD texture {}: {}", id, e.getMessage());
            return null;
        }
    }

    private NativeImage createNeutralWhite() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        image.setPixelABGR(0, 0, 0xFFFFFFFF);
        return image;
    }

    private NativeImage copyImage(NativeImage source) {
        NativeImage copy = new NativeImage(source.format(), source.getWidth(), source.getHeight(), false);
        copy.copyFrom(source);
        return copy;
    }
}
