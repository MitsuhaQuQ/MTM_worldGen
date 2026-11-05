package com.mtm.worldgen.webmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One map tile's worth of land: per pixel the height of the natural ground, the water surface over it and what
 * covers it. Tiles of level 0 have a pixel per block and cover one region file; each level above merges four tiles
 * of the one below into one, at half the resolution.
 */
final class Raster {
    static final int SIZE = 512;
    /** No ground or water known: nothing has been generated there. */
    static final short UNKNOWN = Short.MIN_VALUE;

    /** Cover, in the low bits; the flags above them. */
    static final byte NONE = 0;
    static final byte GRASS = 1;
    static final byte SOIL = 2;
    static final byte SAND = 3;
    static final byte GRAVEL = 4;
    static final byte ROCK = 5;
    static final byte SNOW = 6;
    static final byte ICE = 7;
    static final byte TERRACOTTA = 8;
    static final byte LAVA = 9;
    static final int KINDS = 10;
    static final int KIND_MASK = 0x0F;
    static final int FOREST = 0x10;
    static final int SNOWY = 0x20;
    static final int FROZEN = 0x40;

    private static final int FORMAT = 1;

    final short[] ground = new short[SIZE * SIZE];
    /** Water surface, the top of its highest block, or UNKNOWN where there is none. */
    final short[] water = new short[SIZE * SIZE];
    final byte[] cover = new byte[SIZE * SIZE];

    Raster() {
        java.util.Arrays.fill(ground, UNKNOWN);
        java.util.Arrays.fill(water, UNKNOWN);
    }

    static int index(int x, int z) {
        return z * SIZE + x;
    }

    boolean known(int i) {
        return (cover[i] & KIND_MASK) != NONE || water[i] != UNKNOWN;
    }

    boolean isEmpty() {
        for (int i = 0; i < cover.length; i++) {
            if (known(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies a tile of the level below into one quarter of this one, two by two pixels into one. Water wins over land
     * as soon as one of the four is water: a stream a few blocks wide would otherwise be gone a level or two up, and
     * rivers and lakes are what the map is for. Land takes the cover most of its pixels have.
     */
    void merge(Raster child, int quarterX, int quarterZ) {
        int half = SIZE / 2;
        int[] votes = new int[KINDS];
        for (int z = 0; z < half; z++) {
            for (int x = 0; x < half; x++) {
                int groundSum = 0;
                int groundCount = 0;
                int waterSum = 0;
                int waterCount = 0;
                int forest = 0;
                int snowy = 0;
                int frozen = 0;
                java.util.Arrays.fill(votes, 0);
                for (int dz = 0; dz < 2; dz++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int i = index(2 * x + dx, 2 * z + dz);
                        if (child.ground[i] != UNKNOWN) {
                            groundSum += child.ground[i];
                            groundCount++;
                        }
                        if (child.water[i] != UNKNOWN) {
                            waterSum += child.water[i];
                            waterCount++;
                        }
                        int c = child.cover[i];
                        votes[c & KIND_MASK]++;
                        forest += (c & FOREST) != 0 ? 1 : 0;
                        snowy += (c & SNOWY) != 0 ? 1 : 0;
                        frozen += (c & FROZEN) != 0 ? 1 : 0;
                    }
                }
                int o = index(quarterX * half + x, quarterZ * half + z);
                ground[o] = groundCount == 0 ? UNKNOWN : (short) Math.round((double) groundSum / groundCount);
                water[o] = waterCount == 0 ? UNKNOWN : (short) Math.round((double) waterSum / waterCount);
                int kind = NONE;
                for (int k = 1; k < KINDS; k++) {
                    if (votes[k] > votes[kind] || kind == NONE && votes[k] > 0) {
                        kind = k;
                    }
                }
                int flags = (forest >= 2 ? FOREST : 0) | (snowy >= 2 ? SNOWY : 0) | (frozen * 2 >= waterCount && frozen > 0 ? FROZEN : 0);
                cover[o] = (byte) (kind | flags);
            }
        }
    }

    void write(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(FORMAT);
        for (short value : ground) {
            out.writeShort(value);
        }
        for (short value : water) {
            out.writeShort(value);
        }
        out.write(cover);
        out.flush();
    }

    /** A raster written by {@link #write}, or null if it was written by another format. */
    static Raster read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != FORMAT) {
            return null;
        }
        Raster raster = new Raster();
        for (int i = 0; i < raster.ground.length; i++) {
            raster.ground[i] = in.readShort();
        }
        for (int i = 0; i < raster.water.length; i++) {
            raster.water[i] = in.readShort();
        }
        in.readFully(raster.cover);
        return raster;
    }
}
