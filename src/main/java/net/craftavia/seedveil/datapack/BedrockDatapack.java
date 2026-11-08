package net.craftavia.seedveil.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.MinecraftServer;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a data pack from the vanilla world generation files inside the server jar, replacing the
 * "random_name" of the bedrock (and optionally deepslate) gradients with a name that contains a
 * per-server secret token. The game hashes this name together with the world seed, so the bedrock
 * pattern no longer matches the vanilla formula and bedrock-based seed crackers (1.18+) find nothing.
 * Terrain, biomes and everything else stay exactly the same.
 */
public final class BedrockDatapack {

    public static final String PACK_ID = "seedveil-bedrock";
    public static final String NAMESPACE = "seedveil";
    public static final String TOKEN_FILE = "bedrock-token.txt";
    public static final String MANIFEST_FILE = "manifest.json";

    /**
     * Paths relative to worldgen. 26.2 and older: the bedrock rule lives in noise_settings.
     * 26.3+: surface rules moved to the separate material_rule registry (bedrock_floor/bedrock_roof, deepslate in
     * overworld/underground). Files missing from the jar or without a matching random_name are skipped, so the
     * same code works on both versions.
     */
    static final List<String> WORLDGEN_FILES = List.of(
            "noise_settings/overworld", "noise_settings/large_biomes", "noise_settings/amplified",
            "noise_settings/caves", "noise_settings/floating_islands", "noise_settings/nether",
            "material_rule/bedrock_floor", "material_rule/bedrock_roof", "material_rule/overworld/underground");
    private static final String RESOURCE_DIR = "/data/minecraft/worldgen/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public record Options(boolean enabled, boolean deepslate) {
    }

    public record Result(Path root, List<String> files, String packFormat) {
    }

    private BedrockDatapack() {
    }

    /** Defaults when config.yml does not exist yet (first start): enabled, deepslate off. */
    public static Options readOptions(final Path dataDir) {
        final Path config = dataDir.resolve("config.yml");
        if (!Files.exists(config)) {
            return new Options(true, false);
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config.toFile());
        return new Options(
                yaml.getBoolean("bedrock-datapack.enabled", true),
                yaml.getBoolean("bedrock-datapack.randomize-deepslate", false));
    }

    /** The secret part of the name; created once and kept (otherwise the pattern would change on every start). */
    public static String loadOrCreateToken(final Path dataDir) throws IOException {
        final Path file = dataDir.resolve(TOKEN_FILE);
        if (Files.exists(file)) {
            final String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (existing.matches("[a-f0-9]{16,64}")) {
                return existing;
            }
        }
        final byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        final String token = HexFormat.of().formatHex(bytes);
        Files.createDirectories(dataDir);
        Files.writeString(file, token + System.lineSeparator(), StandardCharsets.UTF_8);
        return token;
    }

    /** random_name values to replace and their per-server replacements. */
    static Map<String, String> replacements(final String token, final boolean deepslate) {
        final String floor = NAMESPACE + ":bedrock_floor_" + token;
        final String roof = NAMESPACE + ":bedrock_roof_" + token;
        return deepslate
                ? Map.of("minecraft:bedrock_floor", floor, "minecraft:bedrock_roof", roof,
                        "minecraft:deepslate", NAMESPACE + ":deepslate_" + token)
                : Map.of("minecraft:bedrock_floor", floor, "minecraft:bedrock_roof", roof);
    }

    /** Writes the data pack folder from scratch. Runs on every start, so it follows server updates. */
    public static Result generate(final Path dataDir, final Options options) throws IOException {
        final String token = loadOrCreateToken(dataDir);
        final Map<String, String> replacements = replacements(token, options.deepslate());

        final Path root = dataDir.resolve("datapack").resolve(PACK_ID);
        deleteRecursively(root);
        final Path worldgenDir = root.resolve("data").resolve("minecraft").resolve("worldgen");

        final List<String> written = new ArrayList<>();
        for (final String name : WORLDGEN_FILES) {
            final JsonElement json = readVanilla(RESOURCE_DIR + name + ".json");
            if (json == null) {
                continue;
            }
            if (replaceRandomNames(json, replacements) == 0) {
                continue;
            }
            final Path target = worldgenDir.resolve(name + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, GSON.toJson(json), StandardCharsets.UTF_8);
            written.add(name);
        }
        if (written.isEmpty()) {
            throw new IOException("No noise_settings/material_rule file with a bedrock rule was found in the server jar.");
        }

        final String packFormat = writePackMeta(root);

        // The manifest only feeds /seedveil status; the token itself stays in bedrock-token.txt.
        final JsonObject manifest = new JsonObject();
        manifest.addProperty("packFormat", packFormat);
        manifest.addProperty("deepslate", options.deepslate());
        final JsonArray files = new JsonArray();
        written.forEach(files::add);
        manifest.add("files", files);
        Files.writeString(dataDir.resolve(MANIFEST_FILE), GSON.toJson(manifest), StandardCharsets.UTF_8);

        return new Result(root, List.copyOf(written), packFormat);
    }

    /** The manifest written by the last generate() (for the status screen), or null. */
    public static JsonObject readManifest(final Path dataDir) {
        final Path file = dataDir.resolve(MANIFEST_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    static JsonElement readVanilla(final String resource) throws IOException {
        try (InputStream in = MinecraftServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader);
            }
        }
    }

    /** Replaces every "random_name" value found in the replacement table; returns how many were changed. */
    static int replaceRandomNames(final JsonElement element, final Map<String, String> replacements) {
        int count = 0;
        if (element.isJsonObject()) {
            final JsonObject obj = element.getAsJsonObject();
            for (final Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                final JsonElement value = entry.getValue();
                if ("random_name".equals(entry.getKey()) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    final String replacement = replacements.get(value.getAsString());
                    if (replacement != null) {
                        obj.add(entry.getKey(), new JsonPrimitive(replacement));
                        count++;
                    }
                } else {
                    count += replaceRandomNames(value, replacements);
                }
            }
        } else if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                count += replaceRandomNames(child, replacements);
            }
        }
        return count;
    }

    /**
     * pack.mcmeta: the data pack version is read from the server's version.json.
     * New style (26.x): data_major/data_minor -> min_format/max_format; old style: data -> pack_format.
     */
    private static String writePackMeta(final Path root) throws IOException {
        final JsonObject pack = packSection(readVanilla("/version.json"));
        final JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        Files.writeString(root.resolve("pack.mcmeta"), GSON.toJson(meta), StandardCharsets.UTF_8);
        if (pack.has("min_format")) {
            final JsonArray min = pack.getAsJsonArray("min_format");
            return min.get(0).getAsInt() + "." + min.get(1).getAsInt();
        }
        return String.valueOf(pack.get("pack_format").getAsInt());
    }

    /** Builds the "pack" object of pack.mcmeta from the contents of version.json. */
    static JsonObject packSection(final JsonElement version) throws IOException {
        int major = -1;
        int minor = -1;
        boolean newStyle = false;
        if (version != null && version.isJsonObject() && version.getAsJsonObject().has("pack_version")) {
            final JsonObject pv = version.getAsJsonObject().getAsJsonObject("pack_version");
            if (pv.has("data_major")) {
                major = pv.get("data_major").getAsInt();
                minor = pv.has("data_minor") ? pv.get("data_minor").getAsInt() : 0;
                newStyle = true;
            } else if (pv.has("data")) {
                final JsonElement data = pv.get("data");
                if (data.isJsonArray() && !data.getAsJsonArray().isEmpty()) {
                    major = data.getAsJsonArray().get(0).getAsInt();
                    minor = data.getAsJsonArray().size() > 1 ? data.getAsJsonArray().get(1).getAsInt() : 0;
                    newStyle = true;
                } else {
                    major = data.getAsInt();
                }
            }
        }
        if (major < 0) {
            throw new IOException("Could not read the data pack version from version.json.");
        }

        final JsonObject pack = new JsonObject();
        pack.addProperty("description", "SeedVeil - bedrock pattern protection");
        if (newStyle) {
            final JsonArray min = new JsonArray();
            min.add(major);
            min.add(minor);
            pack.add("min_format", min);
            pack.addProperty("max_format", major);
        } else {
            pack.addProperty("pack_format", major);
        }
        return pack;
    }

    private static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
