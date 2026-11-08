package net.craftavia.seedveil.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BedrockDatapackTest {

    @Test
    void replacesOnlyMatchingRandomNames() {
        final JsonElement json = JsonParser.parseString("""
                {"sequence": [
                  {"if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:bedrock_floor"}},
                  {"if_true": {"type": "minecraft:vertical_gradient", "random_name": "minecraft:deepslate"}},
                  {"other": {"random_name": "minecraft:bedrock_roof"}}
                ]}""");
        final int changed = BedrockDatapack.replaceRandomNames(json, BedrockDatapack.replacements("abc123", false));
        assertEquals(2, changed);
        final String text = json.toString();
        assertTrue(text.contains("seedveil:bedrock_floor_abc123"));
        assertTrue(text.contains("seedveil:bedrock_roof_abc123"));
        assertTrue(text.contains("minecraft:deepslate"));
    }

    @Test
    void packMetaFormats() throws Exception {
        final JsonObject modern = BedrockDatapack.packSection(JsonParser.parseString(
                "{\"pack_version\": {\"data_major\": 101, \"data_minor\": 1}}"));
        assertEquals("[101,1]", modern.get("min_format").toString());
        assertEquals(101, modern.get("max_format").getAsInt());

        final JsonObject legacy = BedrockDatapack.packSection(JsonParser.parseString(
                "{\"pack_version\": {\"data\": 71}}"));
        assertEquals(71, legacy.get("pack_format").getAsInt());

        assertThrows(java.io.IOException.class, () -> BedrockDatapack.packSection(JsonParser.parseString("{}")));
    }

    @Test
    void tokenIsKeptBetweenStarts(@TempDir final Path dir) throws Exception {
        final String first = BedrockDatapack.loadOrCreateToken(dir);
        assertTrue(first.matches("[a-f0-9]{32}"));
        assertEquals(first, BedrockDatapack.loadOrCreateToken(dir));
    }

    /** Uses the real vanilla files of the dev bundle server jar. */
    @Test
    void generatesPackFromServerJar(@TempDir final Path dir) throws Exception {
        assumeTrue(BedrockDatapack.readVanilla("/version.json") != null, "server resources not on the test classpath");

        final BedrockDatapack.Result result = BedrockDatapack.generate(dir, new BedrockDatapack.Options(true, false));
        assertFalse(result.files().isEmpty());
        assertTrue(Files.exists(result.root().resolve("pack.mcmeta")));

        final String token = BedrockDatapack.loadOrCreateToken(dir);
        boolean floor = false;
        try (Stream<Path> files = Files.walk(result.root().resolve("data"))) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                final String text = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(text.contains("\"minecraft:bedrock_floor\""), file + " still has the vanilla bedrock name");
                assertFalse(text.contains("\"minecraft:bedrock_roof\""), file + " still has the vanilla bedrock name");
                floor |= text.contains("seedveil:bedrock_floor_" + token);
            }
        }
        assertTrue(floor, "no bedrock floor rule was rewritten");

        final String manifest = Files.readString(dir.resolve(BedrockDatapack.MANIFEST_FILE), StandardCharsets.UTF_8);
        assertFalse(manifest.contains(token), "the manifest must not contain the secret token");
    }
}
