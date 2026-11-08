package net.craftavia.seedveil.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigScramblerTest {

    @Test
    void scramblesSpigotAndPaperConfigs(@TempDir final Path root) throws Exception {
        final StringBuilder spigot = new StringBuilder("world-settings:\n  default:\n");
        for (final Map.Entry<String, Object> entry : ConfigScrambler.VANILLA.entrySet()) {
            spigot.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        spigot.append("  custom_world:\n    seed-village: 10387312\n");
        Files.writeString(root.resolve("spigot.yml"), spigot.toString(), StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/paper-world-defaults.yml"),
                "feature-seeds:\n  generate-random-seeds-for-all: false\n", StandardCharsets.UTF_8);

        // 26.x layout: each world folder is world/dimensions/<namespace>/<name>.
        final Path overworld = root.resolve("world/dimensions/minecraft/overworld");
        Files.createDirectories(overworld.resolve("region"));
        Files.createFile(overworld.resolve("region/r.0.0.mca"));
        Files.writeString(overworld.resolve("paper-world.yml"),
                "feature-seeds:\n  generate-random-seeds-for-all: false\n", StandardCharsets.UTF_8);
        final List<Path> worlds = List.of(overworld);

        final ConfigScrambler scrambler = new ConfigScrambler(root);
        final ConfigScrambler.Inspection before = scrambler.inspect(worlds);
        assertEquals(ConfigScrambler.VANILLA.size(), before.vanillaKeys());
        assertEquals(List.of("custom_world"), before.perWorldSections());
        assertFalse(before.featureSeedsOn());
        assertEquals(1, before.regionFiles());

        final ConfigScrambler.ScrambleResult result = scrambler.scramble(worlds);
        assertEquals(ConfigScrambler.VANILLA.size() + 1, result.structureKeysSet());
        assertTrue(result.featureSeedsSet());

        final ConfigScrambler.Inspection after = scrambler.inspect(worlds);
        assertEquals(0, after.vanillaKeys());
        assertTrue(after.featureSeedsOn());
        assertTrue(Files.readString(overworld.resolve("paper-world.yml"), StandardCharsets.UTF_8)
                .contains("generate-random-seeds-for-all: true"));
    }

    @Test
    void vanillaDetection() {
        assertTrue(ConfigScrambler.isVanilla("seed-village", 10387312));
        assertTrue(ConfigScrambler.isVanilla("seed-village", null));
        assertTrue(ConfigScrambler.isVanilla("seed-stronghold", "default"));
        assertFalse(ConfigScrambler.isVanilla("seed-village", 5));
        assertFalse(ConfigScrambler.isVanilla("seed-stronghold", 123));
    }
}
