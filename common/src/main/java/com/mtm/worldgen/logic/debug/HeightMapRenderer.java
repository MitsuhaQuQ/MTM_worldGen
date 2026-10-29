package com.mtm.worldgen.logic.debug;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;

import java.awt.image.BufferedImage;

/** Renders the terrain logic's surface height as a hypsometric map, for tuning without flying around. */
public final class HeightMapRenderer {
    private static final int REGION_BORDER_RGB = 0xFF00FF;
    private static final int BEACH_RGB = 0xE8D8A0;

    private HeightMapRenderer() {
    }

    /**
     * @param centerX        block X at the image center
     * @param centerZ        block Z at the image center
     * @param sizePx         image edge length in pixels
     * @param blocksPerPixel horizontal scale
     */
    public static BufferedImage render(TerrainLogic logic, int centerX, int centerZ, int sizePx, int blocksPerPixel) {
        WorldgenSettings settings = logic.settings();
        BufferedImage image = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_RGB);
        int originX = centerX - sizePx / 2 * blocksPerPixel;
        int originZ = centerZ - sizePx / 2 * blocksPerPixel;
        for (int py = 0; py < sizePx; py++) {
            for (int px = 0; px < sizePx; px++) {
                int x = originX + px * blocksPerPixel;
                int z = originZ + py * blocksPerPixel;
                double height = logic.sampleSurfaceHeight(x, z);
                boolean beach = height >= settings.seaLevel() - 1 && logic.sampleBeach(x, z);
                int rgb = beach ? BEACH_RGB : color(height, settings);
                if (onRegionBorder(x, blocksPerPixel, settings) || onRegionBorder(z, blocksPerPixel, settings)) {
                    rgb = blend(rgb, REGION_BORDER_RGB, 0.35);
                }
                image.setRGB(px, py, rgb);
            }
        }
        return image;
    }

    private static boolean onRegionBorder(int coordinate, int blocksPerPixel, WorldgenSettings settings) {
        return Math.floorMod(coordinate, settings.regionSize()) < blocksPerPixel;
    }

    private static int color(double height, WorldgenSettings settings) {
        double sea = settings.seaLevel() - 1;
        if (height < sea) {
            double depth = Math.min(1.0, (sea - height) / 40.0);
            return blend(0x5AA0E0, 0x0B2E6B, depth);
        }
        double t = Math.min(1.0, (height - sea) / (settings.maxSurfaceHeight() - sea));
        if (t < 0.3) {
            return blend(0x5DA040, 0xB0B050, t / 0.3);
        }
        if (t < 0.7) {
            return blend(0xB0B050, 0x8A6A4A, (t - 0.3) / 0.4);
        }
        return blend(0x8A6A4A, 0xFFFFFF, (t - 0.7) / 0.3);
    }

    private static int blend(int from, int to, double t) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
