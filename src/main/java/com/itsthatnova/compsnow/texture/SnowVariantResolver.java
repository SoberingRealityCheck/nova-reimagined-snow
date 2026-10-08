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
 * Resolves active resource-pack snow textures into a fixed 4-slot set.
 */
public final class SnowVariantResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("compsnow.variants");
    public static final Identifier VANILLA_SNOW_ID = Identifier.withDefaultNamespace("textures/block/snow.png");

    public ResolvedSnowVariantSet resolve(ResourceManager resourceManager, SnowVariantConfig config) throws IOException {
        List<ResolvedSnowVariantSet.Slot> slots = new ArrayList<>(SnowVariantConfig.VARIANT_SLOTS);

        NativeImage vanillaImage = loadImage(resourceManager, VANILLA_SNOW_ID);
        if (vanillaImage == null) {
            throw new IOException("Unable to resolve vanilla fallback texture " + VANILLA_SNOW_ID);
        }

        List<String> manualVariants = config.getManualVariants();
        for (int i = 0; i < SnowVariantConfig.VARIANT_SLOTS; i++) {
            if (config.getMode() == SnowVariantConfig.Mode.MANUAL_OVERRIDE) {
                String raw = i < manualVariants.size() ? manualVariants.get(i) : "";
                if (raw != null && !raw.isBlank()) {
                    Identifier id = Identifier.tryParse(raw.trim());
                    if (id == null) {
                        LOGGER.warn("Snow variant manual slot {} is not a valid identifier: {}", i, raw);
                    } else {
                        NativeImage image = loadImage(resourceManager, id);
                        if (image != null) {
                            slots.add(new ResolvedSnowVariantSet.Slot(image, id, ResolvedSnowVariantSet.SourceType.MANUAL));
                            LOGGER.warn("Snow variant manual slot {} resolved to {}", i, id);
                            continue;
                        }

                        LOGGER.warn("Snow variant manual slot {} could not resolve {}", i, id);
                    }
                }
            }

            slots.add(new ResolvedSnowVariantSet.Slot(copyImage(vanillaImage), VANILLA_SNOW_ID,
                    ResolvedSnowVariantSet.SourceType.VANILLA_FALLBACK));
        }

        LOGGER.warn("Resolved snow variants: [{}, {}, {}, {}]",
                describeSlot(slots.get(0)),
                describeSlot(slots.get(1)),
                describeSlot(slots.get(2)),
                describeSlot(slots.get(3)));

        vanillaImage.close();
        return new ResolvedSnowVariantSet(List.copyOf(slots));
    }

    private String describeSlot(ResolvedSnowVariantSet.Slot slot) {
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
            LOGGER.warn("Failed reading snow variant texture {}: {}", id, e.getMessage());
            return null;
        }
    }

    private NativeImage copyImage(NativeImage source) {
        NativeImage copy = new NativeImage(source.format(), source.getWidth(), source.getHeight(), false);
        copy.copyFrom(source);
        return copy;
    }
}
