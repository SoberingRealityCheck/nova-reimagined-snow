package com.itsthatnova.compsnow.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Final resolved 4-slot snow texture set published to Iris.
 */
public record ResolvedSnowVariantSet(List<Slot> slots) {
    public record Slot(NativeImage image, Identifier sourceId, SourceType sourceType) {}

    public enum SourceType {
        MANUAL,
        VANILLA_FALLBACK
    }
}
