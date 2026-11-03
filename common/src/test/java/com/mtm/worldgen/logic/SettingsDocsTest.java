package com.mtm.worldgen.logic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsDocsTest {
    private static final Path SOURCE = Path.of("src/main/java/com/mtm/worldgen/logic/WorldgenSettings.java");
    private static final Path RESOURCE = Path.of("src/main/resources/mtm_worldgen/settings_docs.properties");

    @Test
    void configCommentsMatchTheSettingsSource() throws IOException {
        List<String> source = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);
        assertEquals(SettingsDocs.format(SettingsDocs.parse(source)), Files.readString(RESOURCE, StandardCharsets.UTF_8),
                "settings_docs.properties is out of date: run gradlew :common:updateSettingsDocs");
    }

    @Test
    void everyGroupHasComments() throws IOException {
        Map<String, String> docs = SettingsDocs.parse(Files.readAllLines(SOURCE, StandardCharsets.UTF_8));
        for (String group : List.of("general", "slopes", "continents", "mountains", "plains", "plateaus", "hills", "isolated",
                "seaStacks", "overhangs", "sinkholes", "deserts", "foreign", "scale", "erosion", "surface", "beaches",
                "islands", "spawn", "climate", "rivers", "biomes")) {
            assertTrue(docs.containsKey(group), "no comment for the " + group + " group");
        }
        assertTrue(docs.containsKey("general.logicRange"));
        assertTrue(docs.containsKey("BiomeZone.lowland") || docs.keySet().stream().anyMatch(k -> k.startsWith("BiomeZone.")),
                "no comments for the biomes of a climate zone");
    }

    @Test
    void commentedConfigReadsBackTheSameSettings() throws IOException {
        Map<String, String> docs = SettingsDocs.parse(Files.readAllLines(SOURCE, StandardCharsets.UTF_8));
        String commented = WorldgenSettings.DEFAULT.toCommentedJson("Header.\n\nSecond paragraph.", docs);
        assertTrue(commented.contains("// "), "no comments written");
        assertEquals(WorldgenSettings.DEFAULT.toJson(), WorldgenSettings.fromJson(commented).toJson());
    }

    @Test
    void logicRangeScalesTheRangesTogether() {
        WorldgenSettings wide = WorldgenSettings.fromJson("{\"general\": {\"logicRange\": 2}}");
        WorldgenSettings resolved = wide.resolved();
        WorldgenSettings base = WorldgenSettings.DEFAULT;
        assertEquals(2.0, wide.general.logicRange, "the setting itself stays as written");
        assertEquals(1.0, resolved.general.logicRange);
        assertEquals(2 * base.general.regionSize, resolved.general.regionSize);
        assertEquals(2 * base.rivers.flowLookAhead, resolved.rivers.flowLookAhead);
        assertEquals(2 * base.rivers.queryReach, resolved.rivers.queryReach);
        assertEquals(2 * base.erosion.margin, resolved.erosion.margin);
        assertEquals(base.rivers.flowCellSize, resolved.rivers.flowCellSize, "landform sizes stay the same");
        assertEquals(base.mountains.zoneWavelength, resolved.mountains.zoneWavelength, "landform sizes stay the same");
        assertEquals(resolved.toJson(), resolved.resolved().toJson(), "resolving twice changes nothing");
        assertTrue(base == base.resolved(), "a range of 1 resolves to the same settings");
    }
}
