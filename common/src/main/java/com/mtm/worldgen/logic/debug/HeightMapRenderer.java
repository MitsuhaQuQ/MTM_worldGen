package com.mtm.worldgen.logic.debug;

import com.mtm.worldgen.logic.FlowRegion;
import com.mtm.worldgen.logic.RiverRegion;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

/** Renders the terrain logic's surface height as a hillshaded hypsometric map, for tuning without flying around. */
public final class HeightMapRenderer {
    private static final int REGION_BORDER_RGB = 0xFF00FF;
    private static final int BEACH_RGB = 0xE8D8A0;
    private static final int SNOW_RGB = 0xFFFFFF;
    private static final int ROCK_RGB = 0x8C8C8C;
    private static final Color LAKE_COLOR = new Color(0x40E0D0);
    private static final Color[] RIVER_COLORS = {null, new Color(0x7FD0FF), new Color(0x2080FF), new Color(0x0020C0), new Color(0xE00020)};
    /** Light from the north-west; brightness change per unit of grade across one pixel. */
    private static final double SHADE_STRENGTH = 1.2;

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

        double[] heights = new double[(sizePx + 1) * (sizePx + 1)];
        for (int py = 0; py <= sizePx; py++) {
            for (int px = 0; px <= sizePx; px++) {
                heights[py * (sizePx + 1) + px] =
                        logic.sampleSurfaceHeight(originX + px * blocksPerPixel, originZ + py * blocksPerPixel);
            }
        }

        double sea = settings.general.seaLevel - 1;
        for (int py = 0; py < sizePx; py++) {
            for (int px = 0; px < sizePx; px++) {
                int x = originX + px * blocksPerPixel;
                int z = originZ + py * blocksPerPixel;
                double height = heights[py * (sizePx + 1) + px];
                boolean beach = height >= sea && logic.sampleBeach(x, z);
                int rgb = beach ? BEACH_RGB : color(height, settings);
                if (height >= sea && logic.sampleSnow(x, z)) {
                    rgb = SNOW_RGB;
                } else if (height >= sea && logic.sampleRock(x, z)) {
                    rgb = ROCK_RGB;
                }
                if (height >= sea) {
                    double gradeX = (heights[py * (sizePx + 1) + px + 1] - height) / blocksPerPixel;
                    double gradeZ = (heights[(py + 1) * (sizePx + 1) + px] - height) / blocksPerPixel;
                    double shade = Math.max(-0.6, Math.min(0.6, -(gradeX + gradeZ) * SHADE_STRENGTH));
                    rgb = shade >= 0 ? blend(rgb, 0xFFFFFF, shade) : blend(rgb, 0x000000, -shade);
                }
                if (onRegionBorder(x, blocksPerPixel, settings) || onRegionBorder(z, blocksPerPixel, settings)) {
                    rgb = blend(rgb, REGION_BORDER_RGB, 0.35);
                }
                image.setRGB(px, py, rgb);
            }
        }
        drawRivers(image, logic, originX, originZ, sizePx, blocksPerPixel);
        return image;
    }

    /** Draws the drainage network, thicker and redder as rivers rise in class, and lakes as turquoise cells. */
    private static void drawRivers(BufferedImage image, TerrainLogic logic, int originX, int originZ, int sizePx, int blocksPerPixel) {
        WorldgenSettings settings = logic.settings();
        int cellSize = settings.rivers.flowCellSize;
        int cells = settings.general.regionSize / cellSize;
        int firstX = Math.floorDiv(originX, settings.general.regionSize);
        int firstZ = Math.floorDiv(originZ, settings.general.regionSize);
        int lastX = Math.floorDiv(originX + sizePx * blocksPerPixel, settings.general.regionSize);
        int lastZ = Math.floorDiv(originZ + sizePx * blocksPerPixel, settings.general.regionSize);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int regionX = firstX; regionX <= lastX; regionX++) {
            for (int regionZ = firstZ; regionZ <= lastZ; regionZ++) {
                FlowRegion flow = logic.flowRegion(regionX, regionZ);
                for (int lz = 0; lz < cells; lz++) {
                    for (int lx = 0; lx < cells; lx++) {
                        int cellX = regionX * cells + lx;
                        int cellZ = regionZ * cells + lz;
                        double fromX = ((cellX + 0.5) * cellSize - originX) / blocksPerPixel;
                        double fromZ = ((cellZ + 0.5) * cellSize - originZ) / blocksPerPixel;
                        if (flow.isLake(lx, lz)) {
                            int cellPixels = Math.max(1, cellSize / blocksPerPixel);
                            graphics.setColor(LAKE_COLOR);
                            graphics.fillRect((int) fromX - cellPixels / 2, (int) fromZ - cellPixels / 2, cellPixels, cellPixels);
                        }
                    }
                }
                RiverRegion rivers = logic.riverRegion(regionX, regionZ);
                for (int segment = 0; segment < rivers.segments(); segment++) {
                    graphics.setColor(RIVER_COLORS[rivers.riverClass(segment).ordinal()]);
                    graphics.setStroke(new BasicStroke((float) Math.max(1.0, rivers.width(segment) / blocksPerPixel)));
                    graphics.draw(new Line2D.Double((rivers.startX(segment) - originX) / blocksPerPixel, (rivers.startZ(segment) - originZ) / blocksPerPixel,
                            (rivers.endX(segment) - originX) / blocksPerPixel, (rivers.endZ(segment) - originZ) / blocksPerPixel));
                }
            }
        }
        graphics.dispose();
    }

    private static boolean onRegionBorder(int coordinate, int blocksPerPixel, WorldgenSettings settings) {
        return Math.floorMod(coordinate, settings.general.regionSize) < blocksPerPixel;
    }

    private static int color(double height, WorldgenSettings settings) {
        double sea = settings.general.seaLevel - 1;
        if (height < sea) {
            double depth = Math.min(1.0, (sea - height) / 40.0);
            return blend(0x5AA0E0, 0x0B2E6B, depth);
        }
        double t = Math.min(1.0, (height - sea) / (settings.general.maxSurfaceHeight - sea));
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
