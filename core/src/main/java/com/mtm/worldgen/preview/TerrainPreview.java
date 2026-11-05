package com.mtm.worldgen.preview;

import com.mtm.worldgen.logic.BlockColumn;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.logic.debug.HeightMapRenderer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Previews the terrain logic of a seed outside the game: {@code java -jar} on the mod jar opens a window that pans and
 * zooms over the map the {@code /mwg debugmap} command renders. With a seed on the command line and no display, it
 * writes one picture instead. Only the default settings are used, since the JSON library comes with the game.
 */
public final class TerrainPreview {
    private static final int TILE = 256;
    private static final int MIN_SCALE = 1;
    private static final int MAX_SCALE = 128;
    /** Above this many blocks per pixel the map leaves out rivers, which would need the drainage of a vast area. */
    private static final int DETAILED_SCALE = 16;
    /** Logic regions kept: a window full of map covers far more of them than a game does around its players. */
    private static final int REGION_CACHE = 256;
    private static final int DEFAULT_SCALE = 16;
    /** Scroll that makes one zoom step: a wheel notch, or a trackpad slide adding up to one. */
    private static final double SCROLL_STEP = 1.0;
    /** Trackpad pinch, as macOS measures its magnification, that makes one zoom step. */
    private static final double PINCH_STEP = 0.3;
    /** Pixels a sideways scroll pans per wheel notch. */
    private static final double PAN_PER_NOTCH = 32.0;
    private static final int CACHED_TILES = 400;
    private static final int PICTURE_SIZE = 1024;
    /** Samples per side of the view when counting kinds of land, and how long the view has to rest first. */
    private static final int CENSUS_GRID = 64;
    private static final int CENSUS_DELAY_MS = 250;

    private TerrainPreview() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && (GraphicsEnvironment.isHeadless() || args.length >= 5)) {
            writePicture(args);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("Usage: java -jar <mod jar> <seed> [x z blocksPerPixel picture.png]");
            return;
        }
        SwingUtilities.invokeLater(() -> openWindow(args.length > 0 ? args[0] : ""));
    }

    /** A seed as the game reads it from the world creation screen: a number stands for itself, text for its hash. */
    static long parseSeed(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new java.util.Random().nextLong();
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return trimmed.hashCode();
        }
    }

    private static void writePicture(String[] args) throws IOException {
        long seed = parseSeed(args[0]);
        TerrainLogic logic = new TerrainLogic(seed, previewSettings());
        BlockColumn spawn = args.length >= 3 ? new BlockColumn(Integer.parseInt(args[1]), Integer.parseInt(args[2])) : logic.findSpawn();
        int scale = args.length >= 4 ? Integer.parseInt(args[3]) : DEFAULT_SCALE;
        File out = new File(args.length >= 5 ? args[4] : "mtm_preview_" + seed + ".png");
        ImageIO.write(HeightMapRenderer.render(logic, spawn.x(), spawn.z(), PICTURE_SIZE, scale, scale <= DETAILED_SCALE), "png", out);
        System.out.println("Seed " + seed + ", centered on " + spawn.x() + " " + spawn.z() + ", written to " + out.getAbsolutePath());
        LogicShutdown.exit();
    }

    private static WorldgenSettings previewSettings() {
        WorldgenSettings settings = new WorldgenSettings();
        settings.general.regionCacheSize = REGION_CACHE;
        return settings;
    }

    private static void openWindow(String seedText) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            // The default look works as well
        }
        JFrame frame = new JFrame("Realistic Landforms terrain preview");
        MapPanel map = new MapPanel();
        JTextField seed = new JTextField(seedText, 18);
        JTextField target = new JTextField(12);
        JButton show = new JButton("Show");
        JButton random = new JButton("Random");
        JButton spawn = new JButton("Spawn");
        JButton go = new JButton("Go to x z");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Seed"));
        bar.add(seed);
        bar.add(show);
        bar.add(random);
        bar.add(spawn);
        bar.add(target);
        bar.add(go);
        JLabel status = new JLabel(" ");
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        map.status = status;
        show.addActionListener(event -> {
            map.show(parseSeed(seed.getText()));
            seed.setText(Long.toString(map.seed));
        });
        seed.addActionListener(event -> show.doClick());
        random.addActionListener(event -> {
            seed.setText(Long.toString(new java.util.Random().nextLong()));
            show.doClick();
        });
        spawn.addActionListener(event -> map.goToSpawn());
        go.addActionListener(event -> {
            String[] parts = target.getText().trim().split("[\\s,]+");
            if (parts.length == 2) {
                try {
                    map.goTo(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                } catch (NumberFormatException e) {
                    status.setText("Enter two numbers: x z");
                }
            }
        });
        target.addActionListener(event -> go.doClick());
        frame.add(bar, BorderLayout.NORTH);
        frame.add(map, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1100, 850);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        show.doClick();
    }

    /** The map: tiles rendered in the background as they come into view, cached while they stay near it. */
    private static final class MapPanel extends JPanel {
        private final ExecutorService workers = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), runnable -> {
            Thread thread = new Thread(runnable, "MTM preview");
            thread.setDaemon(true);
            return thread;
        });
        private final Map<String, BufferedImage> tiles = new LinkedHashMap<>(64, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > CACHED_TILES;
            }
        };
        private final Map<String, Boolean> pending = new LinkedHashMap<>();
        /** The readout under the cursor gets a thread of its own, or it would wait behind every tile in the queue. */
        private final ExecutorService readout = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MTM preview readout");
            thread.setDaemon(true);
            return thread;
        });
        /** Only the latest position under the cursor is worth looking up. */
        private final AtomicInteger readoutRequest = new AtomicInteger();
        /** Shares of the kinds of land in view, worked out after the view stops moving. */
        private final ExecutorService census = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MTM preview census");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicInteger censusRequest = new AtomicInteger();
        private final javax.swing.Timer censusDelay = new javax.swing.Timer(CENSUS_DELAY_MS, event -> takeCensus());
        private volatile double[] shares;
        /** Increases with every new seed, so tiles still rendering for an old one are dropped. */
        private final AtomicInteger generation = new AtomicInteger();
        private TerrainLogic logic;
        private long seed;
        private double centerX;
        private double centerZ;
        private int scale = DEFAULT_SCALE;
        private final ZoomGesture scrollZoom = new ZoomGesture(SCROLL_STEP);
        private final ZoomGesture pinchZoom = new ZoomGesture(PINCH_STEP);
        private int dragX;
        private int dragZ;
        private JLabel status;

        MapPanel() {
            censusDelay.setRepeats(false);
            setPreferredSize(new Dimension(1024, 768));
            setBackground(new Color(0x202020));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    dragX = event.getX();
                    dragZ = event.getY();
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    centerX -= (event.getX() - dragX) * (double) scale;
                    centerZ -= (event.getY() - dragZ) * (double) scale;
                    dragX = event.getX();
                    dragZ = event.getY();
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    describe(event.getX(), event.getY());
                }

                /** Swing only counts a click when the mouse has not moved since the press, so drags never copy. */
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        copyPosition(event.getX(), event.getY());
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    double amount = event.getPreciseWheelRotation();
                    if (event.isShiftDown()) {
                        // Sideways: a shifted wheel, and how macOS delivers a sideways trackpad slide
                        centerX += amount * PAN_PER_NOTCH * scale;
                        repaint();
                        return;
                    }
                    zoomAt(event.getX(), event.getY(), scrollZoom.add(amount));
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            listenForPinch();
        }

        /** Zooms by whole steps, keeping the block under a point of the panel where it is. */
        private void zoomAt(int px, int py, int steps) {
            int next = scale;
            for (int i = 0; i < Math.abs(steps); i++) {
                next = steps < 0 ? Math.max(MIN_SCALE, next / 2) : Math.min(MAX_SCALE, next * 2);
            }
            if (next == scale) {
                return;
            }
            double blockX = centerX + (px - getWidth() / 2.0) * scale;
            double blockZ = centerZ + (py - getHeight() / 2.0) * scale;
            scale = next;
            centerX = blockX - (px - getWidth() / 2.0) * scale;
            centerZ = blockZ - (py - getHeight() / 2.0) * scale;
            repaint();
        }

        /**
         * Zooms with a trackpad pinch on macOS. Swing has no pinch events, but the macOS JDK sends them through
         * com.apple.eawt.event, which the jar manifest exports to this class. It is looked up by reflection, so
         * nothing fails where it does not exist.
         */
        private void listenForPinch() {
            try {
                Class<?> utilities = Class.forName("com.apple.eawt.event.GestureUtilities");
                Class<?> listener = Class.forName("com.apple.eawt.event.GestureListener");
                Class<?> magnification = Class.forName("com.apple.eawt.event.MagnificationListener");
                Method amount = Class.forName("com.apple.eawt.event.MagnificationEvent").getMethod("getMagnification");
                InvocationHandler handler = (proxy, method, args) -> {
                    if (method.getName().equals("magnify") && args != null && args.length == 1) {
                        Point at = getMousePosition();
                        int px = at != null ? at.x : getWidth() / 2;
                        int py = at != null ? at.y : getHeight() / 2;
                        // Spreading the fingers magnifies, which is zooming in
                        int steps = pinchZoom.add(-(double) amount.invoke(args[0]));
                        SwingUtilities.invokeLater(() -> zoomAt(px, py, steps));
                        return null;
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "pinch zoom";
                        default -> null;
                    };
                };
                Object pinch = Proxy.newProxyInstance(magnification.getClassLoader(), new Class<?>[]{magnification}, handler);
                utilities.getMethod("addGestureListenerTo", javax.swing.JComponent.class, listener).invoke(null, this, pinch);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Not macOS, or a JDK without trackpad gestures: the wheel and dragging still work
            }
        }

        void show(long newSeed) {
            seed = newSeed;
            generation.incrementAndGet();
            synchronized (tiles) {
                tiles.clear();
                pending.clear();
            }
            logic = new TerrainLogic(newSeed, previewSettings());
            goToSpawn();
        }

        void goToSpawn() {
            TerrainLogic current = logic;
            status.setText("Finding the spawn...");
            workers.submit(() -> {
                BlockColumn spawn = current.findSpawn();
                SwingUtilities.invokeLater(() -> {
                    goTo(spawn.x(), spawn.z());
                    status.setText("Seed " + seed + ", spawn at " + spawn.x() + " " + spawn.z());
                });
            });
        }

        void goTo(int x, int z) {
            centerX = x;
            centerZ = z;
            repaint();
        }

        /**
         * Copies the block position under a point of the panel as "x z", the form the Go to field takes, so it can be
         * pasted back there or into a command.
         */
        private void copyPosition(int px, int py) {
            int x = (int) Math.floor(centerX + (px - getWidth() / 2.0) * scale);
            int z = (int) Math.floor(centerZ + (py - getHeight() / 2.0) * scale);
            String text = x + " " + z;
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
                status.setText("Copied " + text);
            } catch (IllegalStateException e) {
                // Another program holds the clipboard for a moment
                status.setText("Could not copy " + text + ": the clipboard is busy");
            }
        }

        private void describe(int px, int py) {
            TerrainLogic current = logic;
            if (current == null) {
                return;
            }
            int x = (int) Math.floor(centerX + (px - getWidth() / 2.0) * scale);
            int z = (int) Math.floor(centerZ + (py - getHeight() / 2.0) * scale);
            int at = generation.get();
            int request = readoutRequest.incrementAndGet();
            boolean detailed = scale <= DETAILED_SCALE;
            readout.submit(() -> {
                if (request != readoutRequest.get()) {
                    return;
                }
                // Far out, only what the overview shows: anything else would compute drainage the map leaves out
                double height = detailed ? current.sampleSurfaceHeight(x, z) : current.sampleOverviewHeight(x, z);
                double water = detailed ? current.sampleWaterLevel(x, z) : Double.NaN;
                String biome = detailed ? current.sampleBiome(x, z).replace("minecraft:", "") : "overview, zoom in for rivers and biomes";
                String text = String.format("x %d  z %d   height %.0f%s   %s   (%d blocks per pixel)", x, z, height,
                        Double.isNaN(water) ? "" : String.format("   water %.0f", water), biome, scale);
                SwingUtilities.invokeLater(() -> {
                    if (at == generation.get()) {
                        status.setText(text);
                    }
                });
            });
        }

        /** Samples the view on a grid and counts each kind of land, for the box in the corner. */
        private void takeCensus() {
            TerrainLogic current = logic;
            if (current == null) {
                return;
            }
            int request = censusRequest.incrementAndGet();
            double left = centerX - getWidth() / 2.0 * scale;
            double top = centerZ - getHeight() / 2.0 * scale;
            double width = getWidth() * (double) scale;
            double height = getHeight() * (double) scale;
            census.submit(() -> {
                TerrainLogic.Landform[] kinds = TerrainLogic.Landform.values();
                int[] counts = new int[kinds.length];
                for (int i = 0; i < CENSUS_GRID && request == censusRequest.get(); i++) {
                    for (int j = 0; j < CENSUS_GRID; j++) {
                        double x = left + (i + 0.5) * width / CENSUS_GRID;
                        double z = top + (j + 0.5) * height / CENSUS_GRID;
                        counts[current.sampleLandformKind(x, z).ordinal()]++;
                    }
                }
                if (request != censusRequest.get()) {
                    return;
                }
                double[] result = new double[kinds.length];
                for (int k = 0; k < kinds.length; k++) {
                    result[k] = counts[k] / (double) (CENSUS_GRID * CENSUS_GRID);
                }
                shares = result;
                SwingUtilities.invokeLater(this::repaint);
            });
        }

        private void drawShares(Graphics graphics) {
            double[] current = shares;
            if (current == null) {
                return;
            }
            String[] labels = {"Ocean", "Plains", "Hills", "Plateau", "Mountains"};
            Color[] colors = {new Color(0x3A7BD5), new Color(0x6DB24A), new Color(0xB0B050), new Color(0x9A7A50), new Color(0xE8E8E8)};
            int lineHeight = graphics.getFontMetrics().getHeight();
            int boxWidth = 170;
            int boxHeight = lineHeight * (labels.length + 1) + 8;
            int x = getWidth() - boxWidth - 10;
            int y = getHeight() - boxHeight - 10;
            graphics.setColor(new Color(0, 0, 0, 170));
            graphics.fillRect(x, y, boxWidth, boxHeight);
            graphics.setColor(Color.WHITE);
            graphics.drawString("In view", x + 8, y + lineHeight);
            for (int k = 0; k < labels.length; k++) {
                int line = y + lineHeight * (k + 2);
                graphics.setColor(colors[k]);
                graphics.fillRect(x + 8, line - lineHeight + 5, 10, 10);
                graphics.setColor(Color.WHITE);
                graphics.drawString(String.format("%-10s %5.1f%%", labels[k], 100.0 * current[k]), x + 26, line);
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (logic == null) {
                return;
            }
            if (lastCensusView == null || !lastCensusView.equals(viewKey())) {
                lastCensusView = viewKey();
                censusDelay.restart();
            }
            double left = centerX - getWidth() / 2.0 * scale;
            double top = centerZ - getHeight() / 2.0 * scale;
            int span = TILE * scale;
            int firstX = (int) Math.floor(left / span);
            int firstZ = (int) Math.floor(top / span);
            int lastX = (int) Math.floor((left + getWidth() * (double) scale) / span);
            int lastZ = (int) Math.floor((top + getHeight() * (double) scale) / span);
            for (int tileZ = firstZ; tileZ <= lastZ; tileZ++) {
                for (int tileX = firstX; tileX <= lastX; tileX++) {
                    BufferedImage tile = tile(tileX, tileZ);
                    int screenX = (int) Math.round((tileX * (double) span - left) / scale);
                    int screenZ = (int) Math.round((tileZ * (double) span - top) / scale);
                    if (tile != null) {
                        graphics.drawImage(tile, screenX, screenZ, null);
                    }
                }
            }
            drawShares(graphics);
            graphics.setColor(Color.WHITE);
            graphics.drawLine(getWidth() / 2 - 6, getHeight() / 2, getWidth() / 2 + 6, getHeight() / 2);
            graphics.drawLine(getWidth() / 2, getHeight() / 2 - 6, getWidth() / 2, getHeight() / 2 + 6);
        }

        private String lastCensusView;

        private String viewKey() {
            return seed + ":" + scale + ":" + Math.round(centerX) + ":" + Math.round(centerZ) + ":" + getWidth() + ":" + getHeight();
        }

        /** Whether a tile shows in the window, or lies right next to it. */
        private boolean inView(int tileX, int tileZ, int size) {
            double span = (double) TILE * size;
            double left = centerX - getWidth() / 2.0 * size - span;
            double top = centerZ - getHeight() / 2.0 * size - span;
            double right = centerX + getWidth() / 2.0 * size + span;
            double bottom = centerZ + getHeight() / 2.0 * size + span;
            return tileX * span < right && (tileX + 1) * span > left && tileZ * span < bottom && (tileZ + 1) * span > top;
        }

        /** A rendered tile, or null while it renders in the background. */
        private BufferedImage tile(int tileX, int tileZ) {
            String key = scale + ":" + tileX + ":" + tileZ;
            synchronized (tiles) {
                BufferedImage tile = tiles.get(key);
                if (tile != null || pending.containsKey(key)) {
                    return tile;
                }
                pending.put(key, Boolean.TRUE);
            }
            TerrainLogic current = logic;
            int at = generation.get();
            int size = scale;
            workers.submit(() -> {
                // Tiles left behind by panning or zooming are not worth rendering any more
                if (at != generation.get() || size != scale || !inView(tileX, tileZ, size)) {
                    synchronized (tiles) {
                        pending.remove(key);
                    }
                    return;
                }
                int centerOfTileX = tileX * TILE * size + TILE / 2 * size;
                int centerOfTileZ = tileZ * TILE * size + TILE / 2 * size;
                BufferedImage image = HeightMapRenderer.render(current, centerOfTileX, centerOfTileZ, TILE, size, size <= DETAILED_SCALE);
                synchronized (tiles) {
                    pending.remove(key);
                    if (at == generation.get()) {
                        tiles.put(key, image);
                    }
                }
                SwingUtilities.invokeLater(this::repaint);
            });
            return null;
        }
    }

    /** Lets the command line version end even though the terrain logic keeps worker threads around. */
    private static final class LogicShutdown {
        static void exit() {
            System.exit(0);
        }
    }
}
