package com.mtm.worldgen.webmap;

import java.awt.image.BufferedImage;

/**
 * Draws a raster as a topographic map in the manner of OpenTopoMap: cover in flat map colors, hill shading lit from
 * the north west, contour lines, and water in blues that deepen with depth. Places nothing has been generated at
 * stay transparent.
 */
final class TileRenderer {
    private static final int[] COVER_COLORS = new int[Raster.KINDS];

    static {
        COVER_COLORS[Raster.GRASS] = 0xCDEBB0;
        COVER_COLORS[Raster.SOIL] = 0xE2D7B8;
        COVER_COLORS[Raster.SAND] = 0xF5E9C6;
        COVER_COLORS[Raster.GRAVEL] = 0xE0D9CF;
        COVER_COLORS[Raster.ROCK] = 0xD6CEC5;
        COVER_COLORS[Raster.SNOW] = 0xF7F9FB;
        COVER_COLORS[Raster.ICE] = 0xDDECEC;
        COVER_COLORS[Raster.TERRACOTTA] = 0xE4BC9B;
        COVER_COLORS[Raster.LAVA] = 0xF08A3C;
    }

    private static final int FOREST = 0xADD19E;
    private static final int SNOWY = 0xF4F7FA;
    private static final int SHALLOW = 0xB4DAE8;
    private static final int DEEP = 0x7AB0CC;
    private static final int FROZEN_WATER = 0xCFE5EE;
    private static final int CONTOUR = 0x8C6B4A;
    /** Water this deep shows in the deepest blue. */
    private static final double FULL_DEPTH = 24.0;
    /** Hill shading: light from the north west, this high over the horizon, and how strongly it darkens slopes. */
    private static final double SUN_AZIMUTH = Math.toRadians(315.0);
    private static final double SUN_ALTITUDE = Math.toRadians(45.0);
    private static final double SHADE_STRENGTH = 0.55;
    /**
     * Slopes steeper than this (a fraction) draw no minor contours, and those steeper than the second no contours at
     * all: they would run together into a solid band, and the shading shows such cliffs better.
     */
    private static final double MINOR_CONTOUR_GRADE = 1.5;
    private static final double MAJOR_CONTOUR_GRADE = 4.0;

    private TileRenderer() {
    }

    /** Contour interval at a level, in blocks: tighter close in, sparser as more land fits in a pixel. */
    static int contourInterval(int level) {
        return switch (level) {
            case 0, 1 -> 10;
            case 2 -> 20;
            case 3, 4 -> 50;
            case 5 -> 100;
            default -> 200;
        };
    }

    /** @param level the raster's level: a pixel stands for 2^level blocks */
    static BufferedImage render(Raster raster, int level) {
        int size = Raster.SIZE;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double spacing = 1 << level;
        int interval = contourInterval(level);
        double sunX = Math.cos(SUN_ALTITUDE) * Math.sin(SUN_AZIMUTH);
        double sunZ = -Math.cos(SUN_ALTITUDE) * Math.cos(SUN_AZIMUTH);
        double sunY = Math.sin(SUN_ALTITUDE);
        // Shading and contours follow the land smoothed over a few pixels: block by block it steps, and every step
        // would show as a stripe
        double[] heights = smoothed(raster, level <= 1 ? 2 : 1);
        boolean[] forest = forest(raster);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = Raster.index(x, z);
                if (!raster.known(i)) {
                    continue;
                }
                int cover = raster.cover[i];
                int color;
                // Merged pixels can hold water below their mean ground, as along a river: water there still shows
                if (raster.water[i] != Raster.UNKNOWN) {
                    if ((cover & Raster.FROZEN) != 0) {
                        color = FROZEN_WATER;
                    } else {
                        double depth = raster.ground[i] == Raster.UNKNOWN ? FULL_DEPTH : Math.max(0, raster.water[i] - raster.ground[i]);
                        color = mix(SHALLOW, DEEP, Math.min(1.0, depth / FULL_DEPTH));
                    }
                    image.setRGB(x, z, 0xFF000000 | color);
                    continue;
                }
                int kind = cover & Raster.KIND_MASK;
                color = COVER_COLORS[kind];
                if ((cover & Raster.SNOWY) != 0 && kind != Raster.LAVA) {
                    color = SNOWY;
                } else if (forest[i]) {
                    color = FOREST;
                }
                // Slope from the neighbors, the tile's own edge standing in for those beyond it
                double h = heights[i];
                double east = height(heights, x + 1, z, h);
                double west = height(heights, x - 1, z, h);
                double south = height(heights, x, z + 1, h);
                double north = height(heights, x, z - 1, h);
                double gx = (east - west) / (2.0 * spacing);
                double gz = (south - north) / (2.0 * spacing);
                double length = Math.sqrt(gx * gx + gz * gz + 1.0);
                double light = (-gx * sunX - gz * sunZ + sunY) / length;
                double shade = 1.0 + SHADE_STRENGTH * (light / sunY - 1.0);
                color = scale(color, Math.max(0.5, Math.min(1.15, shade)));
                // A contour where the band of heights changes toward the east or south
                int band = (int) Math.floor(h / interval);
                int eastBand = (int) Math.floor(east / interval);
                int southBand = (int) Math.floor(south / interval);
                double grade = Math.sqrt(gx * gx + gz * gz);
                if ((eastBand != band || southBand != band) && grade < MAJOR_CONTOUR_GRADE) {
                    int line = Math.max(band, Math.max(eastBand, southBand));
                    boolean major = Math.floorMod(line, 5) == 0;
                    if (major || grade < MINOR_CONTOUR_GRADE) {
                        color = mix(color, CONTOUR, major ? 0.6 : 0.3);
                    }
                }
                image.setRGB(x, z, 0xFF000000 | color);
            }
        }
        return image;
    }

    private static double height(double[] heights, int x, int z, double fallback) {
        if (x < 0 || z < 0 || x >= Raster.SIZE || z >= Raster.SIZE) {
            return fallback;
        }
        double value = heights[Raster.index(x, z)];
        return Double.isNaN(value) ? fallback : value;
    }

    /** Ground averaged over a square of pixels around each, of the given radius; NaN where nothing is known. */
    private static double[] smoothed(Raster raster, int radius) {
        int size = Raster.SIZE;
        double[] rows = new double[size * size];
        int[] rowCounts = new int[size * size];
        // Two passes of a box filter, along x and then along z
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double sum = 0;
                int count = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int px = x + dx;
                    if (px >= 0 && px < size) {
                        short value = raster.ground[Raster.index(px, z)];
                        if (value != Raster.UNKNOWN) {
                            sum += value;
                            count++;
                        }
                    }
                }
                rows[Raster.index(x, z)] = sum;
                rowCounts[Raster.index(x, z)] = count;
            }
        }
        double[] result = new double[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double sum = 0;
                int count = 0;
                for (int dz = -radius; dz <= radius; dz++) {
                    int pz = z + dz;
                    if (pz >= 0 && pz < size) {
                        sum += rows[Raster.index(x, pz)];
                        count += rowCounts[Raster.index(x, pz)];
                    }
                }
                result[Raster.index(x, z)] = raster.ground[Raster.index(x, z)] == Raster.UNKNOWN || count == 0 ? Double.NaN : sum / count;
            }
        }
        return result;
    }

    /** Forest where most of the pixels around are: single trees and gaps between crowns would show as speckles. */
    private static boolean[] forest(Raster raster) {
        int size = Raster.SIZE;
        boolean[] forest = new boolean[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int trees = 0;
                int land = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int px = x + dx;
                        int pz = z + dz;
                        if (px < 0 || pz < 0 || px >= size || pz >= size) {
                            continue;
                        }
                        int c = raster.cover[Raster.index(px, pz)];
                        if ((c & Raster.KIND_MASK) != Raster.NONE) {
                            land++;
                            trees += (c & Raster.FOREST) != 0 ? 1 : 0;
                        }
                    }
                }
                forest[Raster.index(x, z)] = land > 0 && trees * 2 > land;
            }
        }
        return forest;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int color, double factor) {
        int r = Math.min(255, (int) Math.round(((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) Math.round(((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) Math.round((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }
}
