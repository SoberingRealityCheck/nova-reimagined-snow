package com.itsthatnova.compsnow.texture;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 26.x NativeImage.getPixel returns ARGB. The 1.21 getColor returned ABGR and the
 * shader-side bit layout was built on that. Keep ABGR everywhere in this mod.
 * (setPixelABGR exists. There is no getPixelABGR, so we swap R and B here.)
 */
public final class Pix {
    private Pix() {}

    public static int getAbgr(NativeImage image, int x, int y) {
        int argb = image.getPixel(x, y);
        return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
    }
}
