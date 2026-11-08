package net.craftavia.seedveil.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Edits the structure seeds (salts) in spigot.yml and Paper's feature seed setting on disk.
 * File IO only; callers run it off the main thread. Changes take effect after a restart.
 */
public final class ConfigScrambler {

    static final String FEATURE_SEEDS = "feature-seeds.generate-random-seeds-for-all";

    /** Spigot's default structure seeds (SpigotWorldConfig). A key with this value counts as "vanilla". */
    static final Map<String, Object> VANILLA = new LinkedHashMap<>();

    static {
        VANILLA.put("seed-village", 10387312);
        VANILLA.put("seed-desert", 14357617);
        VANILLA.put("seed-igloo", 14357618);
        VANILLA.put("seed-jungle", 14357619);
        VANILLA.put("seed-swamp", 14357620);
        VANILLA.put("seed-monument", 10387313);
        VANILLA.put("seed-ocean", 14357621);
        VANILLA.put("seed-shipwreck", 165745295);
        VANILLA.put("seed-slime", 987234911);
        VANILLA.put("seed-outpost", 165745296);
        VANILLA.put("seed-endcity", 10387313);
        VANILLA.put("seed-nether", 30084232);
        VANILLA.put("seed-mansion", 10387319);
        VANILLA.put("seed-fossil", 14357921);
        VANILLA.put("seed-portal", 34222645);
        VANILLA.put("seed-ancientcity", 20083232);
        VANILLA.put("seed-trailruins", 83469867);
        VANILLA.put("seed-trialchambers", 94251327);
        VANILLA.put("seed-buriedtreasure", 10387320);
        VANILLA.put("seed-mineshaft", "default");
        VANILLA.put("seed-stronghold", "default");
    }

    public record Inspection(
            boolean spigotFound,
            int structureKeys,
            int vanillaKeys,
            List<String> perWorldSections,
            boolean paperFound,
            boolean featureSeedsOn,
            boolean antiXrayOn,
            int regionFiles
    ) {
    }

    public record ScrambleResult(int structureKeysSet, boolean featureSeedsSet) {
    }

    private final Path spigotYml;
    private final Path paperWorldDefaults;
    private final SecureRandom random = new SecureRandom();

    public ConfigScrambler(final Path serverRoot) {
        this.spigotYml = serverRoot.resolve("spigot.yml");
        this.paperWorldDefaults = serverRoot.resolve("config").resolve("paper-world-defaults.yml");
    }

    public Inspection inspect(final List<Path> worldFolders) throws IOException {
        boolean spigotFound = false;
        int keys = 0;
        int vanilla = 0;
        final List<String> perWorld = new ArrayList<>();
        if (Files.exists(spigotYml)) {
            spigotFound = true;
            final YamlConfiguration spigot = load(spigotYml);
            final ConfigurationSection worlds = spigot.getConfigurationSection("world-settings");
            final ConfigurationSection defaults = worlds == null ? null : worlds.getConfigurationSection("default");
            for (final Map.Entry<String, Object> entry : VANILLA.entrySet()) {
                keys++;
                final Object value = defaults == null ? null : defaults.get(entry.getKey());
                if (isVanilla(entry.getKey(), value)) {
                    vanilla++;
                }
            }
            if (worlds != null) {
                for (final String name : worlds.getKeys(false)) {
                    if ("default".equals(name)) {
                        continue;
                    }
                    final ConfigurationSection section = worlds.getConfigurationSection(name);
                    if (section != null && section.getKeys(false).stream().anyMatch(k -> k.startsWith("seed-"))) {
                        perWorld.add(name);
                    }
                }
            }
        }

        boolean paperFound = false;
        boolean featureSeeds = false;
        boolean antiXray = false;
        if (Files.exists(paperWorldDefaults)) {
            paperFound = true;
            final YamlConfiguration paper = load(paperWorldDefaults);
            featureSeeds = paper.getBoolean(FEATURE_SEEDS, false);
            antiXray = paper.getBoolean("anticheat.anti-xray.enabled", false);
        }

        return new Inspection(spigotFound, keys, vanilla, List.copyOf(perWorld), paperFound, featureSeeds, antiXray,
                countRegionFiles(worldFolders));
    }

    /** Randomises the seed-* keys of every world-settings section and turns Paper's feature seeds on. */
    public ScrambleResult scramble(final List<Path> worldFolders) throws IOException {
        int set = 0;
        if (Files.exists(spigotYml)) {
            final YamlConfiguration spigot = load(spigotYml);
            ConfigurationSection worlds = spigot.getConfigurationSection("world-settings");
            if (worlds == null) {
                worlds = spigot.createSection("world-settings");
            }
            ConfigurationSection defaults = worlds.getConfigurationSection("default");
            if (defaults == null) {
                defaults = worlds.createSection("default");
            }
            for (final String key : VANILLA.keySet()) {
                defaults.set(key, randomSalt());
                set++;
            }
            for (final String name : worlds.getKeys(false)) {
                if ("default".equals(name)) {
                    continue;
                }
                final ConfigurationSection section = worlds.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                for (final String key : section.getKeys(false)) {
                    if (key.startsWith("seed-")) {
                        section.set(key, randomSalt());
                        set++;
                    }
                }
            }
            spigot.save(spigotYml.toFile());
        }

        boolean featureSet = false;
        if (Files.exists(paperWorldDefaults)) {
            final YamlConfiguration paper = load(paperWorldDefaults);
            paper.set(FEATURE_SEEDS, true);
            paper.save(paperWorldDefaults.toFile());
            featureSet = true;
        }
        // If a per-world paper-world.yml explicitly turns it off, turn it on there too.
        for (final Path folder : worldFolders) {
            final Path perWorld = folder.resolve("paper-world.yml");
            if (!Files.exists(perWorld)) {
                continue;
            }
            final YamlConfiguration yaml = load(perWorld);
            if (yaml.contains(FEATURE_SEEDS) && !yaml.getBoolean(FEATURE_SEEDS)) {
                yaml.set(FEATURE_SEEDS, true);
                yaml.save(perWorld.toFile());
            }
        }
        return new ScrambleResult(set, featureSet);
    }

    /** Number of generated region files; used to guess whether the world is "new". */
    public int countRegionFiles(final List<Path> worldFolders) throws IOException {
        int count = 0;
        for (final Path folder : worldFolders) {
            for (final Path dir : List.of(folder.resolve("region"),
                    folder.resolve("DIM-1").resolve("region"), folder.resolve("DIM1").resolve("region"))) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(dir)) {
                    count += (int) files.filter(p -> p.getFileName().toString().endsWith(".mca")).count();
                }
            }
        }
        return count;
    }

    static boolean isVanilla(final String key, final Object value) {
        if (value == null) {
            return true;
        }
        final Object expected = VANILLA.get(key);
        if (expected instanceof Integer i && value instanceof Number n) {
            return n.intValue() == i;
        }
        return Objects.equals(String.valueOf(expected), String.valueOf(value));
    }

    private int randomSalt() {
        return random.nextInt(1, Integer.MAX_VALUE);
    }

    private static YamlConfiguration load(final Path file) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (final InvalidConfigurationException e) {
            throw new IOException("Invalid YAML: " + file, e);
        }
        return yaml;
    }
}
