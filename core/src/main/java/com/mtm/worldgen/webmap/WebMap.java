package com.mtm.worldgen.webmap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A web map of a world's overworld as the game has generated it, served over HTTP by the server itself: rivers,
 * lakes and land as they really are, without what has been built on them. In single player the integrated server
 * runs it. It only reads region files, on threads of its own, so it never holds up the game.
 */
public final class WebMap {
    /** Settings of the map, in a file of their own: they are the server's, not the world's. */
    public static final class Config {
        /** Off unless the server's owner turns it on, since it opens a port. */
        public boolean enabled = false;
        /** Address to listen on: 0.0.0.0 for every network, 127.0.0.1 for this computer only. */
        public String bind = "0.0.0.0";
        public int port = 8130;
        /** Threads drawing tiles at once. */
        public int threads = 2;
    }

    /** Where the map reports to: the game's log, which core knows nothing of. */
    public interface Log {
        void info(String message);

        void warn(String message, Throwable error);
    }

    private static final Log SILENT = new Log() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable error) {
        }
    };
    private static final String CONFIG_FILE = "mtm_worldgen-webmap.json";
    private static final String DATABASE_FILE = "webmap.db";
    /** Where versions up to 0.7.4 kept a file for every tile. */
    private static final String OLD_CACHE = "webmap";
    private static final Pattern TILE = Pattern.compile("/tiles/(\\d+)/(-?\\d+)/(-?\\d+)\\.png");
    private static WebMap running;
    private static Log log = SILENT;

    private final HttpServer server;
    private final ExecutorService workers;
    private final TileStore tiles;
    private final TileDatabase database;
    private final String worldName;
    private final Config config;

    private WebMap(Config config, Path regions, Path data, String worldName) throws IOException {
        this.config = config;
        this.worldName = worldName;
        this.server = HttpServer.create(new InetSocketAddress(config.bind, config.port), 0);
        this.database = openDatabase(data.resolve(DATABASE_FILE));
        this.tiles = new TileStore(regions, database);
        AtomicInteger count = new AtomicInteger();
        // Daemon threads: a map left running must never keep the game's process alive
        this.workers = Executors.newFixedThreadPool(Math.max(1, config.threads), runnable -> {
            Thread thread = new Thread(runnable, "MTM web map " + count.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * Starts the map of a world if its config turns it on, and stops one that is running. Called once the overworld
     * of a server has loaded.
     *
     * @param root      the world folder, where tiles are cached in mtm_worldgen/webmap.db
     * @param regions   where the overworld keeps its region files
     * @param configDir the game's config folder
     * @param gameLog   where to report
     */
    public static synchronized void start(Path root, Path regions, Path configDir, String worldName, Log gameLog) {
        stop();
        log = gameLog;
        Config config = loadConfig(configDir.resolve(CONFIG_FILE));
        if (!config.enabled) {
            return;
        }
        try {
            Path data = root.resolve("mtm_worldgen");
            removeOldCache(data.resolve(OLD_CACHE));
            running = new WebMap(config, regions, data, worldName);
            log.info("Web map of " + worldName + " at " + running.address());
        } catch (IOException | RuntimeException | LinkageError e) {
            log.warn("Could not start the web map on " + config.bind + ":" + config.port, e);
        }
    }

    public static synchronized void stop() {
        if (running != null) {
            running.server.stop(0);
            running.workers.shutdownNow();
            if (running.database != null) {
                try {
                    running.database.close();
                } catch (SQLException e) {
                    log.warn("Could not close the web map's tile database", e);
                }
            }
            running = null;
        }
    }

    /**
     * The tile database, or null if SQLite cannot load here, as on a platform its library has no native code for:
     * the map then keeps tiles in memory only, and draws them again after a restart.
     */
    private static TileDatabase openDatabase(Path file) {
        try {
            return TileDatabase.open(file);
        } catch (SQLException | IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            log.warn("Could not open the web map's tile database " + file + ", tiles are kept in memory only", e);
            return null;
        }
    }

    /**
     * Deletes the folder of tile files older versions wrote, on a thread of its own since it can hold hundreds of
     * thousands of them. Only the tile files themselves go, and the folders they leave empty.
     */
    private static void removeOldCache(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try (Stream<Path> levels = Files.list(folder)) {
                for (Path level : levels.toList()) {
                    if (!Files.isDirectory(level) || !level.getFileName().toString().matches("[0-9]+")) {
                        continue;
                    }
                    try (Stream<Path> files = Files.list(level)) {
                        for (Path file : files.toList()) {
                            if (file.getFileName().toString().matches("-?[0-9]+[.]-?[0-9]+[.](png|bin)([.]tmp[0-9]+)?")) {
                                Files.deleteIfExists(file);
                            }
                        }
                    }
                    deleteIfEmpty(level);
                }
                deleteIfEmpty(folder);
                log.info("Removed the old web map tile files in " + folder);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not remove the old web map tile files in " + folder, e);
            }
        }, "MTM web map cleanup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void deleteIfEmpty(Path folder) throws IOException {
        try (Stream<Path> left = Files.list(folder)) {
            if (left.findAny().isEmpty()) {
                Files.delete(folder);
            }
        }
    }

    /** Where the map is reachable, or null if it is not running. */
    public static synchronized String status() {
        return running == null ? null : running.address();
    }

    private String address() {
        String host = config.bind.equals("0.0.0.0") ? "<this server's address>" : config.bind;
        return "http://" + host + ":" + server.getAddress().getPort() + "/";
    }

    /** Reads the config file, writing it with the defaults where it does not exist yet. */
    static Config loadConfig(Path file) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(file)) {
                Config config = gson.fromJson(Files.readString(file), Config.class);
                return config != null ? config : new Config();
            }
            Config defaults = new Config();
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(defaults));
            return defaults;
        } catch (IOException | JsonParseException e) {
            log.warn("Could not read " + file + ", the web map stays off", e);
            return new Config();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain", new byte[0]);
            } else if (path.equals("/") || path.equals("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", resource("index.html"));
            } else if (path.equals("/info.json")) {
                send(exchange, 200, "application/json", info().getBytes(StandardCharsets.UTF_8));
            } else {
                Matcher tile = TILE.matcher(path);
                if (!tile.matches()) {
                    send(exchange, 404, "text/plain", new byte[0]);
                    return;
                }
                byte[] png = tiles.image(Integer.parseInt(tile.group(1)), Integer.parseInt(tile.group(2)), Integer.parseInt(tile.group(3)));
                if (png == null) {
                    send(exchange, 404, "text/plain", new byte[0]);
                } else {
                    exchange.getResponseHeaders().set("Cache-Control", "max-age=30");
                    send(exchange, 200, "image/png", png);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Web map request for " + exchange.getRequestURI() + " failed", e);
            try {
                send(exchange, 500, "text/plain", new byte[0]);
            } catch (IOException | RuntimeException ignored) {
                // The response had already started
            }
        }
    }

    private String info() {
        int[] bounds = tiles.bounds();
        Gson gson = new Gson();
        StringBuilder json = new StringBuilder("{\"name\":").append(gson.toJson(worldName))
                .append(",\"tileSize\":").append(Raster.SIZE).append(",\"maxLevel\":").append(TileStore.MAX_LEVEL);
        if (bounds != null) {
            json.append(",\"bounds\":[").append(bounds[0]).append(',').append(bounds[1]).append(',')
                    .append(bounds[2]).append(',').append(bounds[3]).append(']');
        }
        return json.append('}').toString();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = WebMap.class.getResourceAsStream("/mtm_worldgen/webmap/" + name)) {
            if (in == null) {
                throw new IOException("Missing web map resource " + name);
            }
            return in.readAllBytes();
        }
    }

    private static void send(HttpExchange exchange, int code, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
