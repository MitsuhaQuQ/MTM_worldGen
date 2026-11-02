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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
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
 * zooms over the map the {@code /mtm debugmap} command renders. With a seed on the command line and no display, it
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
    private static final int CACHED_TILES = 400;
    private static final int PICTURE_SIZE = 1024;

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
        JFrame frame = new JFrame("MTM-Worldgen terrain preview");
        MapPanel map = new MapPanel();
        JTextField seed = new JTextField(seedText, 18);
        JTextField target = new JTextField(12);
        JButton show = new JButton("Show");
        JButton spawn = new JButton("Spawn");
        JButton go = new JButton("Go to x z");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(new JLabel("Seed"));
        bar.add(seed);
        bar.add(show);
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
        /** Increases with every new seed, so tiles still rendering for an old one are dropped. */
        private final AtomicInteger generation = new AtomicInteger();
        private TerrainLogic logic;
        private long seed;
        private double centerX;
        private double centerZ;
        private int scale = DEFAULT_SCALE;
        private int dragX;
        private int dragZ;
        private JLabel status;

        MapPanel() {
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

                @Override
                public void mouseWheelMoved(MouseWheelEvent event) {
                    int next = event.getWheelRotation() < 0 ? Math.max(MIN_SCALE, scale / 2) : Math.min(MAX_SCALE, scale * 2);
                    if (next != scale) {
                        // Keep the block under the cursor where it is
                        double blockX = centerX + (event.getX() - getWidth() / 2.0) * scale;
                        double blockZ = centerZ + (event.getY() - getHeight() / 2.0) * scale;
                        scale = next;
                        centerX = blockX - (event.getX() - getWidth() / 2.0) * scale;
                        centerZ = blockZ - (event.getY() - getHeight() / 2.0) * scale;
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
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
                double height = detailed ? current.sampleSurfaceHeight(x, z) : current.sampleLandform(x, z);
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

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (logic == null) {
                return;
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
            graphics.setColor(Color.WHITE);
            graphics.drawLine(getWidth() / 2 - 6, getHeight() / 2, getWidth() / 2 + 6, getHeight() / 2);
            graphics.drawLine(getWidth() / 2, getHeight() / 2 - 6, getWidth() / 2, getHeight() / 2 + 6);
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
