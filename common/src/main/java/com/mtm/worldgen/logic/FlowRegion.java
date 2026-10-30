package com.mtm.worldgen.logic;

import java.util.Arrays;

/** Drainage of one logic region: per coarse cell its flow direction, catchment and river class. Immutable. */
public final class FlowRegion {
    public enum RiverClass {
        NONE, STREAM, TRIBUTARY, TRUNK, MAJOR_TRUNK
    }

    /** Cell offsets of the eight flow directions. */
    static final int[] DIRECTION_X = {-1, 0, 1, -1, 1, -1, 0, 1};
    static final int[] DIRECTION_Z = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final RiverClass[] CLASSES = RiverClass.values();

    private final int regionX;
    private final int regionZ;
    private final int cells;
    private final byte[] directions;
    private final byte[] inflows;
    private final int[] catchments;
    private final int[] tributaries;
    private final byte[] classes;
    private final float[] lakeLevels;

    FlowRegion(int regionX, int regionZ, int cells, byte[] directions, byte[] inflows, int[] catchments, int[] tributaries, byte[] classes, float[] lakeLevels) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.cells = cells;
        this.directions = directions;
        this.inflows = inflows;
        this.catchments = catchments;
        this.tributaries = tributaries;
        this.classes = classes;
        this.lakeLevels = lakeLevels;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public int cells() {
        return cells;
    }

    /** Whether water leaves a cell; false for the sea. */
    public boolean flows(int localX, int localZ) {
        return directions[localZ * cells + localX] >= 0;
    }

    /** Cell offset X of the downstream neighbor. Only valid where {@link #flows} holds. */
    public int downstreamX(int localX, int localZ) {
        return DIRECTION_X[directions[localZ * cells + localX]];
    }

    /** Cell offset Z of the downstream neighbor. Only valid where {@link #flows} holds. */
    public int downstreamZ(int localX, int localZ) {
        return DIRECTION_Z[directions[localZ * cells + localX]];
    }

    /** Whether a cell has water flowing in from a neighbor. */
    public boolean hasInflow(int localX, int localZ) {
        return inflows[localZ * cells + localX] >= 0;
    }

    /** Cell offset X of the biggest inflow. Only valid where {@link #hasInflow} holds. */
    public int inflowX(int localX, int localZ) {
        return DIRECTION_X[inflows[localZ * cells + localX]];
    }

    /** Cell offset Z of the biggest inflow. Only valid where {@link #hasInflow} holds. */
    public int inflowZ(int localX, int localZ) {
        return DIRECTION_Z[inflows[localZ * cells + localX]];
    }

    /** Catchment area in coarse cells. */
    public int catchment(int localX, int localZ) {
        return catchments[localZ * cells + localX];
    }

    /** Tributaries that joined this trunk upstream. */
    public int tributaries(int localX, int localZ) {
        return tributaries[localZ * cells + localX];
    }

    public RiverClass riverClass(int localX, int localZ) {
        return CLASSES[classes[localZ * cells + localX]];
    }

    public boolean isLake(int localX, int localZ) {
        return !Float.isNaN(lakeLevels[localZ * cells + localX]);
    }

    /** Water level of a lake cell. Only valid where {@link #isLake} holds. */
    public float lakeLevel(int localX, int localZ) {
        return lakeLevels[localZ * cells + localX];
    }

    /** Whether this region holds exactly the same values as another. */
    public boolean contentEquals(FlowRegion other) {
        return regionX == other.regionX && regionZ == other.regionZ && cells == other.cells
                && Arrays.equals(directions, other.directions) && Arrays.equals(inflows, other.inflows)
                && Arrays.equals(catchments, other.catchments)
                && Arrays.equals(tributaries, other.tributaries) && Arrays.equals(classes, other.classes)
                && Arrays.equals(lakeLevels, other.lakeLevels);
    }
}
