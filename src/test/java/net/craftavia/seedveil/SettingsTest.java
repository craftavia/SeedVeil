package net.craftavia.seedveil;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @Test
    void normalizesCommands() {
        assertEquals("seed", Settings.normalizeCommand("/seed"));
        assertEquals("seed x", Settings.normalizeCommand("/Minecraft:SEED   x"));
        assertEquals("mv info world", Settings.normalizeCommand("  /mv   INFO world "));
        assertEquals("", Settings.normalizeCommand("/"));
        assertEquals("", Settings.normalizeCommand("   "));
    }

    @Test
    void bundledConfigBlocksSeedCommands() throws Exception {
        final Settings settings = Settings.from(bundledConfig());
        assertTrue(settings.hashedSeed());
        assertEquals("seed", settings.matchBlocked(Settings.normalizeCommand("/minecraft:seed")));
        assertEquals("mv info", settings.matchBlocked(Settings.normalizeCommand("/mv info world_nether")));
        assertNull(settings.matchBlocked(Settings.normalizeCommand("/seeds")));
        assertNull(settings.matchBlocked(Settings.normalizeCommand("/mv list")));
        assertTrue(settings.trackedStructures().contains("minecraft:igloo"));
        assertEquals(16, settings.maxRegionFilesWithoutConfirm());
    }

    private static YamlConfiguration bundledConfig() throws Exception {
        try (InputStream in = SettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml is missing from the resources");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        }
    }
}
