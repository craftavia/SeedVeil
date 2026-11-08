package net.craftavia.seedveil;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Runtime snapshot of config.yml (immutable; a new instance is created on reload). */
public record Settings(
        boolean hashedSeed,
        boolean commandsEnabled,
        List<String> blockedCommands,
        int probeThreshold,
        int probeWindowSeconds,
        boolean monitorEnabled,
        int monitorThreshold,
        int monitorWindowMinutes,
        Set<String> trackedStructures,
        int maxRegionFilesWithoutConfirm
) {

    /** Runs for every player command, so the pattern is compiled once. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static Settings from(final FileConfiguration c) {
        final List<String> blocked = new ArrayList<>();
        for (final String raw : c.getStringList("commands.blocked")) {
            final String norm = normalizeCommand(raw);
            if (!norm.isEmpty()) {
                blocked.add(norm);
            }
        }
        final Set<String> tracked = new HashSet<>();
        for (final String raw : c.getStringList("structure-monitor.structures")) {
            final String key = raw.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                tracked.add(key.contains(":") ? key : "minecraft:" + key);
            }
        }
        final ConfigurationSection probe = c.getConfigurationSection("commands.probe-alert");
        return new Settings(
                c.getBoolean("hashed-seed.enabled", true),
                c.getBoolean("commands.enabled", true),
                List.copyOf(blocked),
                Math.max(1, probe == null ? 5 : probe.getInt("threshold", 5)),
                Math.max(1, probe == null ? 30 : probe.getInt("window-seconds", 30)),
                c.getBoolean("structure-monitor.enabled", true),
                Math.max(1, c.getInt("structure-monitor.threshold", 5)),
                Math.max(1, c.getInt("structure-monitor.window-minutes", 30)),
                Set.copyOf(tracked),
                Math.max(0, c.getInt("scramble.max-region-files-without-confirm", 16))
        );
    }

    /**
     * "/Minecraft:SEED  x" -> "seed x". Strips the leading slash and the namespace of the first word,
     * collapses whitespace and lower-cases everything.
     */
    public static String normalizeCommand(final String input) {
        String s = input.trim();
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        final String[] parts = WHITESPACE.split(s.trim().toLowerCase(Locale.ROOT));
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "";
        }
        final int colon = parts[0].indexOf(':');
        if (colon >= 0 && colon < parts[0].length() - 1) {
            parts[0] = parts[0].substring(colon + 1);
        }
        return String.join(" ", parts);
    }

    /** Returns the blocked entry the normalized command matches (exactly or as a subcommand), or null. */
    public String matchBlocked(final String normalized) {
        for (final String pattern : blockedCommands) {
            if (normalized.equals(pattern) || normalized.startsWith(pattern + " ")) {
                return pattern;
            }
        }
        return null;
    }
}
