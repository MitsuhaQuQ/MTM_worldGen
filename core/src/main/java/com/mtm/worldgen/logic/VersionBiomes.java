package com.mtm.worldgen.logic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vanilla biomes newer than 1.20.1, which the build of the Minecraft version that added them places by default. Each
 * build carries the resource {@value #RESOURCE}, one line per list: the list's name, as zone.list (temperate.medium) or
 * caves, then = and the biomes it gains, separated by commas. The 1.20.1 build, the terrain logic's own tests and the
 * terrain preview run without it, and keep the lists as written in {@link WorldgenSettings.Biomes}.
 */
final class VersionBiomes {
    static final String RESOURCE = "/mtm_worldgen/version_biomes.properties";
    /** Read once: the default settings are made many times over, each time a config is read. */
    private static final Map<String, String[]> ADDITIONS = read();

    private VersionBiomes() {
    }

    /** Appends this build's biomes to default lists, in the order the resource names them. */
    static void addTo(WorldgenSettings.Biomes biomes) {
        for (Map.Entry<String, String[]> entry : ADDITIONS.entrySet()) {
            String key = entry.getKey();
            if (key.equals("caves")) {
                biomes.caves = append(biomes.caves, entry.getValue());
                continue;
            }
            int dot = key.indexOf('.');
            WorldgenSettings.BiomeZone zone = switch (dot < 0 ? key : key.substring(0, dot)) {
                case "tropical" -> biomes.tropical;
                case "temperate" -> biomes.temperate;
                case "cold" -> biomes.cold;
                case "polar" -> biomes.polar;
                default -> throw new IllegalStateException("Unknown zone in " + RESOURCE + ": " + key);
            };
            String list = key.substring(dot + 1);
            switch (list) {
                case "dry" -> zone.dry = append(zone.dry, entry.getValue());
                case "medium" -> zone.medium = append(zone.medium, entry.getValue());
                case "wet" -> zone.wet = append(zone.wet, entry.getValue());
                case "swamp" -> zone.swamp = append(zone.swamp, entry.getValue());
                case "highland" -> zone.highland = append(zone.highland, entry.getValue());
                case "dryHighland" -> zone.dryHighland = append(zone.dryHighland, entry.getValue());
                case "alpine" -> zone.alpine = append(zone.alpine, entry.getValue());
                default -> throw new IllegalStateException("Unknown list in " + RESOURCE + ": " + key);
            }
        }
    }

    private static String[] append(String[] list, String[] added) {
        String[] result = Arrays.copyOf(list, list.length + added.length);
        System.arraycopy(added, 0, result, list.length, added.length);
        return result;
    }

    private static Map<String, String[]> read() {
        Map<String, String[]> additions = new LinkedHashMap<>();
        try (InputStream in = VersionBiomes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return additions;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int split = line.indexOf('=');
                if (line.isBlank() || line.startsWith("#") || split < 0) {
                    continue;
                }
                List<String> ids = new ArrayList<>();
                for (String id : line.substring(split + 1).split(",")) {
                    if (!id.isBlank()) {
                        ids.add(id.trim());
                    }
                }
                additions.put(line.substring(0, split).trim(), ids.toArray(new String[0]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
        return additions;
    }
}
