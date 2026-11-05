package com.mtm.worldgen.webmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The tile cache of a world in one SQLite file, instead of a file for every tile: a map of a large world has hundreds
 * of thousands of tiles, and a folder of that many small files is slow to copy, back up and delete. Each tile keeps
 * the stamp it was drawn for, the newest change of the region files under it; a tile whose stamp is out of date is
 * drawn again and replaces the old one.
 *
 * <p>The driver is the xerial sqlite-jdbc library each loader jar carries as a nested jar. It is looked up by name, so
 * core compiles without it and the terrain preview runs without it.
 */
final class TileDatabase implements AutoCloseable {
    /** Raised whenever what is stored changes its meaning; older tiles are dropped then. */
    private static final int SCHEMA = 1;
    static final String RASTERS = "rasters";
    static final String IMAGES = "images";

    private final Connection connection;
    private final PreparedStatement readRaster;
    private final PreparedStatement readImage;
    private final PreparedStatement writeRaster;
    private final PreparedStatement writeImage;

    private TileDatabase(Connection connection) throws SQLException {
        this.connection = connection;
        try (Statement statement = connection.createStatement()) {
            // Readers and the writer do not wait for each other, and a crash loses at most the last tiles
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            int schema;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                schema = result.next() ? result.getInt(1) : 0;
            }
            if (schema != SCHEMA) {
                statement.execute("DROP TABLE IF EXISTS " + RASTERS);
                statement.execute("DROP TABLE IF EXISTS " + IMAGES);
                statement.execute("PRAGMA user_version=" + SCHEMA);
            }
            for (String table : new String[]{RASTERS, IMAGES}) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + table + " (level INTEGER NOT NULL, x INTEGER NOT NULL, "
                        + "z INTEGER NOT NULL, stamp INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (level, x, z))");
            }
        }
        readRaster = read(RASTERS);
        readImage = read(IMAGES);
        writeRaster = write(RASTERS);
        writeImage = write(IMAGES);
    }

    private PreparedStatement read(String table) throws SQLException {
        return connection.prepareStatement("SELECT stamp, data FROM " + table + " WHERE level = ? AND x = ? AND z = ?");
    }

    private PreparedStatement write(String table) throws SQLException {
        return connection.prepareStatement("INSERT OR REPLACE INTO " + table + " (level, x, z, stamp, data) VALUES (?, ?, ?, ?, ?)");
    }

    /** Opens the database at a file, creating it where it does not exist yet. */
    static TileDatabase open(Path file) throws SQLException, ReflectiveOperationException, java.io.IOException {
        Files.createDirectories(file.getParent());
        // Not through DriverManager: it looks for drivers with the caller's class loader, which on Forge and NeoForge
        // is not the one that sees the nested library
        Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, TileDatabase.class.getClassLoader())
                .getDeclaredConstructor().newInstance();
        Connection connection = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        if (connection == null) {
            throw new SQLException("The SQLite driver did not accept " + file);
        }
        try {
            return new TileDatabase(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** The data of a tile drawn for this stamp, or null if there is none or it is out of date. */
    synchronized byte[] read(String table, int level, int x, int z, long stamp) throws SQLException {
        PreparedStatement statement = table.equals(RASTERS) ? readRaster : readImage;
        statement.setInt(1, level);
        statement.setInt(2, x);
        statement.setInt(3, z);
        try (ResultSet result = statement.executeQuery()) {
            return result.next() && result.getLong(1) == stamp ? result.getBytes(2) : null;
        }
    }

    synchronized void write(String table, int level, int x, int z, long stamp, byte[] data) throws SQLException {
        PreparedStatement statement = table.equals(RASTERS) ? writeRaster : writeImage;
        statement.setInt(1, level);
        statement.setInt(2, x);
        statement.setInt(3, z);
        statement.setLong(4, stamp);
        statement.setBytes(5, data);
        statement.executeUpdate();
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
